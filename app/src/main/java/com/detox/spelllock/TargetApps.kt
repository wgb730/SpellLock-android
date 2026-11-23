package com.detox.spelllock

/**
 * 目标应用集中配置：后续增删应用只改这里。
 *
 * 包名确认记录（2026-11-23，设备 V2426A / Android 15）：
 *   电脑：adb shell pm list packages | grep -i aweme   （只列出 aweme 系，抖音精选不在其中）
 *   实测：抖音精选 = com.ss.android.yumme.video（应用名「抖音精选」，已在本机安装并核对）
 *   哔哩哔哩 = tv.danmaku.bili，抖音 = com.ss.android.ugc.aweme
 * 以后遇到无法确认的新应用，不要猜：先用 App 内「扫描已安装应用」实测或下面的 adb 命令确认，
 * 再决定是写进这里，还是用主页「添加为额外目标」临时加入（ExtraTargets）。
 */
object TargetApps {

    /**
     * 未确认包名的占位符：不会被当作真实包名使用，也不会参与拦截。
     * 新增目标应用时，优先写真实包名；确实未确认时再用这个占位符并标注 TODO。
     */
    const val TODO_PACKAGE = "TODO://unconfirmed-target"

    data class TargetApp(
        val packageName: String,
        val displayName: String,
        val confirmedOnDevice: Boolean = true
    )

    /** 目标应用列表（支持任意多包名）。 */
    val apps: List<TargetApp> = listOf(
        TargetApp(packageName = "tv.danmaku.bili", displayName = "哔哩哔哩"),
        TargetApp(packageName = "com.ss.android.ugc.aweme", displayName = "抖音"),
        // 2026-11-23 在目标设备上实测确认（不是猜的）
        TargetApp(packageName = "com.ss.android.yumme.video", displayName = "抖音精选"),

        // 需要时可直接追加，例如：
        // TargetApp("com.ss.android.ugc.aweme.lite", "抖音极速版"),
        // TargetApp("com.xingin.xhs", "小红书"),
    )

    /** 真正参与拦截的包名集合（自动剔除空值与占位符）。 */
    val activePackages: Set<String> = apps
        .map { it.packageName.trim() }
        .filter { it.isNotEmpty() && it != TODO_PACKAGE }
        .toSet()

    fun isTarget(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in activePackages
    }

    fun displayName(packageName: String): String {
        return apps.firstOrNull { it.packageName == packageName }?.displayName ?: packageName
    }

    /** 「包名检测」页用的关键字：抖音精选 / 抖音极速版 等变体都能扫到。 */
    val scannerKeywords: List<String> = listOf("aweme", "douyin", "yumme", "bili")
}
