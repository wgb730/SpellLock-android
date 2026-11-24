package com.detox.spelllock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 后台守护：
 * 1) 常驻前台通知，让系统少杀一点（国产 ROM 仍然可能杀，所以还有第 2 步）
 * 2) 每 20 秒检查一次无障碍服务是否还在：被系统删掉就从设置里写回去；
 *    没有写权限就发通知，点一下直达无障碍设置。
 *
 * 启动点：App 启动、开机、无障碍服务连上。
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "SpellLock"
        private const val CHANNEL_ID = "spelllock_keepalive"
        private const val NOTIFICATION_ID = 4200
        private const val ALERT_NOTIFICATION_ID = 4210
        private const val CHECK_INTERVAL_MS = 20_000L
        private const val RESTORE_COOLDOWN_MS = 120_000L

        /** 连续几次巡检都发现"设置里有、服务没连上"才动手重绑，避免一次抖动就摘写设置。 */
        private const val REBIND_STREAK_THRESHOLD = 3

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "守护服务启动失败：" + it.message, it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastRestoreAt = 0L
    private var lastAlertAt = 0L
    private var disconnectedStreak = 0

    private val ticker = object : Runnable {
        override fun run() {
            checkOnce()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.post(ticker)
        GuardAlarm.schedule(this)
        Log.i(TAG, "守护服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkOnce()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Log.w(TAG, "守护服务已停止")
        super.onDestroy()
    }

    private fun checkOnce() {
        // 用户在主页把守护关掉了：只看不动手
        if (!AppPrefs.guardEnabled(this)) return
        val listed = A11yGuard.isListed(this)
        val connected = AppAccessibilityService.connected
        if (listed && connected) {
            // 服务正常：清掉抖动计数与提醒，总开关只在真的被置 0 时才写
            disconnectedStreak = 0
            if (!A11yGuard.isGlobalSwitchOn(this)) A11yGuard.fixGlobalSwitch(this)
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(ALERT_NOTIFICATION_ID)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (A11yGuard.canWriteSecure(this)) {
            if (now - lastRestoreAt < RESTORE_COOLDOWN_MS) return
            if (!listed) {
                // 设置里被删了：直接写回（restore 内部幂等，不需要额外判断）
                lastRestoreAt = now
                disconnectedStreak = 0
                if (A11yGuard.restore(this)) Log.i(TAG, "检测到无障碍被系统关闭，已自动写回设置")
                return
            }
            // 设置里还在、服务却没连上：可能是系统把服务记成 crashed，也可能只是一次正常重绑的瞬间。
            // 连续几次巡检都不通才认为真的挂了，避免抖动期间反复摘写设置（每次写都会触发系统重绑）。
            disconnectedStreak += 1
            if (disconnectedStreak < REBIND_STREAK_THRESHOLD) {
                Log.w(TAG, "服务暂未连上（第 " + disconnectedStreak + " 次巡检），继续观察")
                return
            }
            disconnectedStreak = 0
            lastRestoreAt = now
            // 系统把它记成了 crashed service 时不会自动重绑，必须先把组件摘掉再写回去。
            Log.w(TAG, "无障碍在设置里但服务没连上（crashed），触发重新绑定")
            A11yGuard.dropFromList(this)
            handler.postDelayed({ A11yGuard.restore(this) }, 700L)
            return
        }
        if (now - lastAlertAt < RESTORE_COOLDOWN_MS) return
        lastAlertAt = now
        Log.w(TAG, "无障碍服务不见了，且没有写权限，发通知引导用户手动开启")
        postRepairNotification()
    }

    private fun postRepairNotification() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.guard_lost_title))
            .setContentText(getString(R.string.guard_lost_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.guard_lost_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ALERT_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.guard_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.guard_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
