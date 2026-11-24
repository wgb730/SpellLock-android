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
 * 1. 若在免检放行期（验证通过后「免检间隔」，默认 5 分钟）或累计使用时长还没满「使用上限」（默认 20 分钟）：直接放行，同时开始计时。
 * 2. 否则先在目标应用上盖一层无障碍遮罩（OverlayLock）：遮罩吞掉全部触摸，
 *    用户不答题就无法操作下面的应用，"切后台回来继续用"这条路被堵死。
 * 3. 再启动全屏验证页叠在上面；验证页一出现就撤掉遮罩。
 *
 * 计时规则：只统计目标应用在前台的时长；验证页自己的答题时间不计入。
 * 三款目标应用合计满「使用上限」（UnlockGate.usageLimitMs）立刻重新上锁。
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

        /** 使用时长结算间隔：15 秒累加一次，误差不超过 15 秒。 */
        private const val USAGE_TICK_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastHandledAt = 0L
    private var pendingFallback: Runnable? = null

    /** 当前停在前台的目标应用（离开目标应用后清空）。 */
    private var foregroundTarget: String? = null

    /** 计时锚点：>0 表示正在计时，值 = 上一次结算的时刻。 */
    private var usageAnchor = 0L
    private var tickerArmed = false

    private val usageTicker = Runnable { tickUsage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayLock.attach(this)
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "无障碍服务已断开，保护失效")
        stopAccruing()
        handler.removeCallbacksAndMessages(null)
        pendingFallback = null
        foregroundTarget = null
        OverlayLock.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAccruing()
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
            stopAccruing()
            foregroundTarget = null
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        foregroundTarget = packageName

        // 3) 验证页已在前台：不重复叠层，避免闪屏/死循环（答题时间也不计入使用时长）
        if (SelfGuard.spellCheckVisible) {
            stopAccruing()
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        // 4) 免检放行期 / 还有累计可用时长：放行并开始计时
        if (UnlockGate.isInGracePeriod(this) || UnlockGate.hasActiveSession(this)) {
            startAccruing()
            if (OverlayLock.isShowing()) OverlayLock.hide()
            return
        }

        // 5) 去抖
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandledAt < DEBOUNCE_MS) return
        lastHandledAt = now

        Log.i(TAG, "命中目标应用：" + packageName)

        // 6) 先把上一段时长结算掉，再拦截
        stopAccruing()

        // 7) 用户开启了降级模式：走通知（带全屏 Intent，能直接拉起验证页）
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

    // ---------- 使用时长计时 ----------

    /** 开始/继续计时。 */
    private fun startAccruing() {
        if (usageAnchor == 0L) usageAnchor = SystemClock.elapsedRealtime()
        if (!tickerArmed) {
            tickerArmed = true
            handler.postDelayed(usageTicker, USAGE_TICK_MS)
        }
    }

    /** 结算并停止计时。 */
    private fun stopAccruing() {
        accrueUsage()
        usageAnchor = 0L
        tickerArmed = false
        handler.removeCallbacks(usageTicker)
    }

    /** 把锚点到现在这段时长记进 UnlockGate；验证页在前台时不计。 */
    private fun accrueUsage() {
        if (usageAnchor <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (SelfGuard.spellCheckVisible) {
            usageAnchor = now
            return
        }
        val delta = now - usageAnchor
        usageAnchor = now
        if (delta > 0L) UnlockGate.addUsage(this, delta)
    }

    private fun tickUsage() {
        tickerArmed = false
        if (usageAnchor <= 0L) return
        accrueUsage()
        val exhausted = !UnlockGate.hasActiveSession(this) &&
            !UnlockGate.isInGracePeriod(this)
        if (exhausted) {
            val pkg = foregroundTarget
            Log.i(TAG, "三款应用累计使用已满 20 分钟，重新上锁：" + pkg)
            usageAnchor = 0L
            if (pkg != null && !SelfGuard.spellCheckVisible) {
                OverlayLock.show(this, pkg)
                if (!startSpellCheck(pkg)) {
                    BlockNotifier.postGuardNotification(this, pkg)
                }
            }
            return
        }
        tickerArmed = true
        handler.postDelayed(usageTicker, USAGE_TICK_MS)
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
            val inGrace = UnlockGate.isInGracePeriod(this)
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
