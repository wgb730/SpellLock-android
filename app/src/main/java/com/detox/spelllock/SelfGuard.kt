package com.detox.spelllock

import android.os.SystemClock

/**
 * 进程内的验证页状态：
 * - spellCheckVisible：验证页是否在前台（防重复启动）
 * - lastShownAtElapsed：最近一次验证页 onCreate 的时间戳（判断后台启动是否真的成功）
 */
object SelfGuard {

    @Volatile
    var spellCheckVisible: Boolean = false
        private set

    @Volatile
    var lastShownAtElapsed: Long = -1L
        private set

    fun onSpellCheckCreated() {
        lastShownAtElapsed = SystemClock.elapsedRealtime()
    }

    fun onSpellCheckShown() {
        spellCheckVisible = true
    }

    fun onSpellCheckHidden() {
        spellCheckVisible = false
    }
}
