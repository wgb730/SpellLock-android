package com.detox.spelllock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机自启：把守护服务拉起来，顺便尝试恢复被系统删掉的无障碍服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("SpellLock", "开机完成，启动守护服务")
        GuardService.start(context)
        GuardAlarm.schedule(context)
        A11yGuard.restore(context)
    }
}
