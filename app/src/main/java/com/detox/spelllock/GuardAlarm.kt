package com.detox.spelllock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 定时唤醒（看门狗）。
 *
 * 为什么需要：进程被系统回收后，守护服务也跟着没了，单靠"应用内每 20 秒检查"根本跑不到。
 * 用系统闹钟每 5 分钟把应用叫醒一次，起来后 GuardService 会把无障碍服务重新绑上。
 *
 * setAndAllowWhileIdle：不需要精确闹钟权限，Doze 下也能穿透（系统限流时最多约 9 分钟一次）。
 */
object GuardAlarm {

    private const val TAG = "SpellLock"
    private const val INTERVAL_MS = 5 * 60_000L
    private const val REQUEST_CODE = 4300

    fun schedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, GuardAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }.onFailure { Log.w(TAG, "定时唤醒设置失败：" + it.message, it) }
    }
}

class GuardAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 先排下一次，万一本次处理失败也不会断链
        GuardAlarm.schedule(context)
        GuardService.start(context)
    }
}
