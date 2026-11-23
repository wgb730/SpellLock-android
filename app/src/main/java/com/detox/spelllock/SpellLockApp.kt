package com.detox.spelllock

import android.app.Application

class SpellLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 降级方案需要通知渠道，进程一启动就建好
        BlockNotifier.ensureChannel(this)
    }
}
