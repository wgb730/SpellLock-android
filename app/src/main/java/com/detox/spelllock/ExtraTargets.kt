package com.detox.spelllock

import android.content.Context

/**
 * 在手机本机上确认并添加的额外目标包名（例如「抖音精选」）。
 *
 * 为什么需要它：抖音精选的包名不能靠猜，也不能硬编码。
 * 用户在 App 主页「扫描已安装应用」里实测确认后点「添加」，
 * 这里把包名存进 SharedPreferences，无障碍服务启动时一并生效。
 * 这样既不用硬编码未知包名，也不用改代码重编。
 */
object ExtraTargets {

    private const val FILE = "extra_targets"
    private const val KEY_PACKAGES = "packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun packages(context: Context): Set<String> {
        return try {
            prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    fun add(context: Context, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val current = packages(context).toMutableSet()
        current.add(pkg)
        prefs(context).edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun remove(context: Context, packageName: String) {
        val current = packages(context).toMutableSet()
        current.remove(packageName.trim())
        prefs(context).edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PACKAGES).apply()
    }

    /** 整体替换：勾选页保存时用，取消勾选即从拦截名单移除，不会留下残留。 */
    fun setAll(context: Context, packageNames: Collection<String>) {
        val cleaned = packageNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs(context).edit().putStringSet(KEY_PACKAGES, cleaned).apply()
    }

    fun isTarget(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in packages(context)
    }
}
