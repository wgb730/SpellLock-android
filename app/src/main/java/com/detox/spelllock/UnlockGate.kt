package com.detox.spelllock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * 放行状态：固定间隔模式（v1.2.0 起）。
 *
 * 验证通过 → 记下这一刻；之后**不管你有没有在用目标应用，时间都在走**；
 * 到了「固定间隔」（默认 30 分钟，主页可改）就作废，下次打开目标应用必须重新验证。
 *
 * 同时记录 elapsedRealtime 与开机计数（BOOT_COUNT）：
 * - elapsedRealtime 不受改系统时间影响
 * - BOOT_COUNT 变了说明设备重启过，旧记录一律作废
 */
object UnlockGate {

    /** 固定间隔时长（毫秒）。 */
    fun intervalMs(context: Context): Long = AppPrefs.intervalMinutes(context) * 60_000L

    private const val FILE = "unlock_gate"
    private const val KEY_LAST_UNLOCK_ELAPSED = "last_unlock_elapsed"
    private const val KEY_GRANTED = "granted"
    private const val KEY_BOOT_COUNT = "boot_count"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun currentBootCount(context: Context): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (t: Throwable) {
            -1
        }
    }

    private fun isBootValid(context: Context): Boolean {
        val stored = prefs(context).getInt(KEY_BOOT_COUNT, -1)
        if (stored < 0) return false
        return stored == currentBootCount(context)
    }

    /** 记录放行时刻（验证通过 / 急事直通时调用）。 */
    fun grant(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_UNLOCK_ELAPSED, SystemClock.elapsedRealtime())
            .putBoolean(KEY_GRANTED, true)
            .putInt(KEY_BOOT_COUNT, currentBootCount(context))
            .apply()
    }

    /** 撤销放行：下次打开目标应用必须验证。 */
    fun revoke(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_UNLOCK_ELAPSED)
            .remove(KEY_GRANTED)
            .remove(KEY_BOOT_COUNT)
            .apply()
    }

    /** 本次放行还剩多少毫秒；0 = 已到点或从没验证过。 */
    fun remainingMillis(context: Context): Long {
        if (!isBootValid(context)) {
            revoke(context)
            return 0L
        }
        val p = prefs(context)
        if (!p.getBoolean(KEY_GRANTED, false)) return 0L
        val last = p.getLong(KEY_LAST_UNLOCK_ELAPSED, -1L)
        if (last < 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        if (now < last) {
            revoke(context)
            return 0L
        }
        val left = intervalMs(context) - (now - last)
        return if (left > 0L) left else 0L
    }

    fun remainingSeconds(context: Context): Long {
        val left = remainingMillis(context)
        return if (left <= 0L) 0L else (left + 999L) / 1000L
    }

    /** true = 这次放行还没到点。 */
    fun hasActiveSession(context: Context): Boolean = remainingMillis(context) > 0L

    /** 兼容旧调用名：语义已经统一成「固定间隔还没走完」。 */
    fun isInGracePeriod(context: Context): Boolean = hasActiveSession(context)
}
