package com.detox.spelllock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.text.Collator
import java.util.Comparator
import java.util.Locale

/**
 * 应用清单与显示名解析。
 *
 * 两件事：
 * 1. launchableApps()：列出手机里能打开的全部应用（有启动图标的应用），供「选择要拦截的应用」页勾选。
 * 2. label()：把包名翻译成中文应用名，内置目标用手写名，其余查系统，拿不到才退回包名。
 *
 * 为什么用 queryIntentActivities(MAIN + LAUNCHER)：Android 11+ 的包可见性默认收紧，
 * 但 Manifest 里已经声明了对应 <queries>，因此不用 QUERY_ALL_PACKAGES 也能读到带启动图标的应用。
 */
object AppCatalog {

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    /** 手机里能打开的全部应用，按名字排序，已排除本 App 自身。 */
    fun launchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            emptyList()
        }

        val apps = resolves.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            } catch (t: Throwable) {
                pkg
            }
            val icon = try {
                info.loadIcon(pm)
            } catch (t: Throwable) {
                null
            }
            InstalledApp(pkg, label, icon)
        }.distinctBy { it.packageName }

        val collator = Collator.getInstance(Locale.CHINA)
        return apps.sortedWith(Comparator<InstalledApp> { a, b -> collator.compare(a.label, b.label) })
    }

    /** 显示名：内置目标用手写名，其余查系统应用名，都拿不到才退回包名。 */
    fun label(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "目标应用"
        val builtIn = TargetApps.displayName(packageName)
        if (builtIn != packageName) return builtIn
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().ifBlank { packageName }
        } catch (t: Throwable) {
            packageName
        }
    }
}
