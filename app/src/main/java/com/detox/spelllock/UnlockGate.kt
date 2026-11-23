package com.detox.spelllock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * 放行状态记录（两个量）：
 * 1) 免检放行：验证通过后 5 分钟内不重复弹验证（避免连续切换应用时反复打断）。
 * 2) 累计可用时长：哔哩哔哩 / 抖音 / 抖音精选 三个应用合计使用满 20 分钟，必须重新验证。
 *    只有目标应用在前台时才计时，切到别处不计；验证通过后计数清零。
 *
 * 同时记录 elapsedRealtime 和开机计数（BOOT_COUNT）：
 * - elapsedRealtime 不受改系统时间影响
 * - BOOT_COUNT 变化说明设备重启过，旧记录一律作废
 */
object UnlockGate {

    /** 验证通过后的免检放行时长：5 分钟 */
    const val GRACE_PERIOD_MS = 5L * 60L * 1000L

    /** 三款目标应用累计可用时长：20 分钟，用完必须重测 */
    const val USAGE_LIMIT_MS = 20L * 60L * 1000L

    private const val FILE = "unlock_gate"
    private const val KEY_LAST_UNLOCK_ELAPSED = "last_unlock_elapsed"
    private const val KEY_USED_MILLIS = "used_millis"
    private const val KEY_GRANTED = "granted"
    private const val KEY_BOOT_COUNT = "boot_count"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun currentBootCount(context: Context): Int {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1
            )
        } catch (t: Throwable) {
            -1
        }
    }

    private fun isBootValid(context: Context): Boolean {
        val p = prefs(context)
        val stored = p.getInt(KEY_BOOT_COUNT, -1)
        if (stored < 0) return false
        return stored == currentBootCount(context)
    }

    /** 记录解锁时刻并清零累计使用时长（仅在验证通过 / 管理员直通时调用）。 */
    fun grant(context: Context) {
        val now = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putLong(KEY_LAST_UNLOCK_ELAPSED, now)
            .putLong(KEY_USED_MILLIS, 0L)
            .putBoolean(KEY_GRANTED, true)
            .putInt(KEY_BOOT_COUNT, currentBootCount(context))
            .apply()
    }

    /** 撤销全部放行状态（下次打开目标应用必须验证）。 */
    fun revoke(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_UNLOCK_ELAPSED)
            .remove(KEY_USED_MILLIS)
            .remove(KEY_GRANTED)
            .apply()
    }

    /** 累加一次目标应用的前台使用时长；累计用满 20 分钟后本次放行作废。 */
    fun addUsage(context: Context, millis: Long) {
        if (millis <= 0L) return
        if (!hasActiveSession(context)) return
        val p = prefs(context)
        val used = p.getLong(KEY_USED_MILLIS, 0L) + millis
        val edit = p.edit().putLong(KEY_USED_MILLIS, used)
        if (used >= USAGE_LIMIT_MS) {
            edit.putBoolean(KEY_GRANTED, false)
        }
        edit.apply()
    }

    fun isInGracePeriod(context: Context): Boolean = remainingGraceMillis(context) > 0L

    fun remainingSeconds(context: Context): Long {
        val left = remainingGraceMillis(context)
        if (left <= 0L) return 0L
        return (left + 999L) / 1000L
    }

    /** 本次放行累计已用掉多少毫秒。 */
    fun usedMillis(context: Context): Long {
        if (!isBootValid(context)) {
            revoke(context)
            return 0L
        }
        return prefs(context).getLong(KEY_USED_MILLIS, 0L)
    }

    /** true = 还有可用时长（验证通过过、且累计没用满 20 分钟）。 */
    fun hasActiveSession(context: Context): Boolean {
        if (!isBootValid(context)) {
            revoke(context)
            return false
        }
        val p = prefs(context)
        if (!p.getBoolean(KEY_GRANTED, false)) return false
        return p.getLong(KEY_USED_MILLIS, 0L) < USAGE_LIMIT_MS
    }

    /** true = 累计用满 20 分钟，必须重新验证。 */
    fun isUsageExhausted(context: Context): Boolean {
        if (!isBootValid(context)) return false
        val p = prefs(context)
        if (!p.getBoolean(KEY_GRANTED, false) && p.getLong(KEY_USED_MILLIS, 0L) < USAGE_LIMIT_MS) {
            return false
        }
        return p.getLong(KEY_USED_MILLIS, 0L) >= USAGE_LIMIT_MS
    }

    /** 累计还能用多少毫秒；0 表示已用完或还没验证过。 */
    fun usageRemainingMillis(context: Context): Long {
        if (!hasActiveSession(context)) return 0L
        val left = USAGE_LIMIT_MS - usedMillis(context)
        return if (left > 0L) left else 0L
    }

    private fun remainingGraceMillis(context: Context): Long {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_UNLOCK_ELAPSED, -1L)
        if (last < 0L) return 0L
        // 重启过（或拿不到 BOOT_COUNT 时按失效处理）：状态作废
        if (!isBootValid(context)) {
            revoke(context)
            return 0L
        }
        val now = SystemClock.elapsedRealtime()
        if (now < last) {
            revoke(context)
            return 0L
        }
        val left = GRACE_PERIOD_MS - (now - last)
        return if (left > 0L) left else 0L
    }
}
