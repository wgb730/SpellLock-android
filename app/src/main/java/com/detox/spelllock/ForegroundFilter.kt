package com.detox.spelllock

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/**
 * 前台包名过滤：自身包名、SystemUI、Launcher、输入法、电话/系统界面一律忽略。
 * 目的是避免验证页/系统弹窗造成循环唤醒，也让服务只处理"真的用户应用"。
 * （真正的防重弹由 SelfGuard + 800ms 去抖 + 30 秒宽限期共同保证。）
 */
object ForegroundFilter {

    private const val CACHE_MS = 60_000L

    private val SYSTEM_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "android",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.settings",
        "com.google.android.settings"
    )

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedHome: Set<String> = emptySet()

    @Volatile
    private var cachedIme: Set<String> = emptySet()

    fun shouldIgnore(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (packageName == context.packageName) return true
        if (packageName in SYSTEM_PACKAGES) return true
        refreshCacheIfNeeded(context)
        if (packageName in cachedHome) return true
        if (packageName in cachedIme) return true
        return false
    }

    private fun refreshCacheIfNeeded(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) return
        cachedHome = homePackages(context)
        cachedIme = imePackages(context)
        cachedAt = now
    }

    private fun homePackages(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        try {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0).forEach { info ->
                info.activityInfo?.packageName?.let { result.add(it) }
            }
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.let {
                result.add(it)
            }
        } catch (t: Throwable) {
            result.clear()
        }
        return result
    }

    private fun imePackages(context: Context): Set<String> {
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
    }
}
