package com.detox.spelllock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 主方案：监听 TYPE_WINDOW_STATE_CHANGED，判断前台包名是否命中目标应用。
 *
 * 命中后的处理顺序：
 * 1. 若固定间隔倒计时还没走完（UnlockGate.hasActiveSession，默认 30 分钟，主页可改）：直接放行。
 * 2. 否则先在目标应用上盖一层无障碍遮罩（OverlayLock）：遮罩吞掉全部触摸，
 *    用户不答题就无法操作下面的应用，"切后台回来继续用"这条路被堵死。
 * 3. 再启动全屏验证页叠在上面；验证页一出现就撤掉遮罩。
 *
 * 计时规则：从验证通过那一刻起按真实时间倒计时（elapsedRealtime），不管用不用都在走，
 * 走完就重新上锁；间隔长度见 AppPrefs.intervalMinutes（1–480 分钟）。
 *
 * 目标包来源（两者并集）：
 * 1) TargetApps.kt 里代码配置的包名
 * 2) ExtraTargets：用户在 App 内实测确认后添加的包名（例如抖音精选）
 */
class AppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SpellLock"

        /** 同一次切换会连发多个窗口事件，800ms 内只处理一次。 */
        private const val DEBOUNCE_MS = 800L

        /** 检查"验证页是否真的启动了"的延迟（后台启动被静默拒绝时用来补通知）。 */
        private const val FALLBACK_CHECK_DELAY_MS = 4500L

        /** 无障碍服务是否真的连上了：设置里有不代表真的在跑（crashed 状态就只是"名义开启"）。 */
        @Volatile
        var connected: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastHandledAt = 0L
    private var pendingFallback: Runnable? = null

    /** 当前停在前台的目标应用（离开目标应用后清空）。 */
    private var foregroundTarget: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayLock.attach(this)
        connected = true
        Log.i(TAG, "无障碍服务已连接")
        // 服务连上就把守护进程拉起来：它负责在服务被系统删掉时自动写回设置
        GuardService.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "无障碍服务已断开，保护失效")
        // 断连时让守护进程去看一眼：是临时解绑（返回 true 会自动重绑），
        // 还是系统把本服务从设置里删了（那就得写回设置或提醒用户）
        GuardService.start(this)
        connected = false
        handler.removeCallbacksAndMessages(null)
        pendingFallback = null
        foregroundTarget = null
        OverlayLock.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        OverlayLock.hide()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 本服务不需要中断处理
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        if (safeEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = safeEvent.packageName?.toString() ?: return
        handleForegroundChange(packageName)
    }

    private fun handleForegroundChange(packageName: String) {
        // 1) 过滤自身包名、SystemUI、Launcher、输入法、电话/系统界面
        if (ForegroundFilter.shouldIgnore(this, packageName)) return

        // 2) 不是在目标应用上：结算计时，撤掉遮罩，清空前台标记
        val isTarget = TargetApps.isTarget(packageName) ||
            ExtraTargets.isTarget(this, packageName)
        if (!isTarget) {
            foregroundTarget = null
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        foregroundTarget = packageName

        // 3) 验证页已在前台：不重复叠层，避免闪屏/死循环（答题时间也不计入使用时长）
        if (SelfGuard.spellCheckVisible) {
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        // 4) 固定间隔还没到点：放行（倒计时与用不用无关，这里不做任何计时）
        if (UnlockGate.hasActiveSession(this)) {
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        // 5) 去抖
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandledAt < DEBOUNCE_MS) return
        lastHandledAt = now

        Log.i(TAG, "命中目标应用：" + packageName)

        // 6) 用户开启了降级模式：走通知（带全屏 Intent，能直接拉起验证页）
        if (AppPrefs.useNotificationFallback(this)) {
            BlockNotifier.postGuardNotification(this, packageName)
            return
        }

        // 8) 先上锁（遮罩），再拉起验证页
        OverlayLock.show(this, packageName)

        if (!startSpellCheck(packageName)) {
            BlockNotifier.postGuardNotification(this, packageName)
            return
        }

        // 9) 检查页面是否真的出现（部分 ROM 静默拒绝后台启动 Activity）
        scheduleFallbackCheck(packageName, now)
    }

    private fun startSpellCheck(packageName: String): Boolean {
        val intent = Intent(this, SpellCheckActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(SpellCheckActivity.EXTRA_TARGET_PACKAGE, packageName)
        }
        return try {
            startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动验证页抛异常，改用通知降级", t)
            false
        }
    }

    private fun scheduleFallbackCheck(packageName: String, launchAt: Long) {
        pendingFallback?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingFallback = null
            val acked = SelfGuard.lastShownAtElapsed >= launchAt
            val visible = SelfGuard.spellCheckVisible
            val inGrace = UnlockGate.hasActiveSession(this)
            if (!acked && !visible && !inGrace) {
                Log.w(
                    TAG,
                    "验证页未出现在前台（可能被系统限制后台启动）：遮罩保持上锁，另发通知提醒"
                )
                if (!OverlayLock.isShowing()) OverlayLock.show(this, packageName)
                BlockNotifier.postGuardNotification(this, packageName)
            } else {
                Log.i(TAG, "验证页已接管前台，本次不补通知")
            }
        }
        pendingFallback = runnable
        handler.postDelayed(runnable, FALLBACK_CHECK_DELAY_MS)
    }
}
