package com.detox.spelllock

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务自检与自愈。
 *
 * 背景（真机实测，vivo V2426A / Android 15）：
 * 进程一旦被系统 force-stop（国产 ROM 的内存回收就会这么做），
 * 系统会把本应用从 Settings.Secure 的 enabled_accessibility_services 里**删掉**，
 * 并把 accessibility_enabled 置 0 —— 光靠 onUnbind 回调是接不住的，因为它根本不会被调用。
 *
 * 所以需要「另一个进程组件」定期检查 + 尝试写回系统设置：
 * - 能写（用户用 adb 授权过 WRITE_SECURE_SETTINGS）→ 直接恢复，用户无感
 * - 不能写 → 发通知引导一键去设置里打开
 */
object A11yGuard {

    private const val TAG = "SpellLock"

    const val SERVICE_CLASS = "com.detox.spelllock.AppAccessibilityService"

    /** 系统归一化后的短组件名，写进 Settings 时用这个最稳。 */
    const val SHORT_COMPONENT = "com.detox.spelllock/.AppAccessibilityService"

    private const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"
    private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"

    private val fullComponent: ComponentName
        get() = ComponentName("com.detox.spelllock", SERVICE_CLASS)

    /** 系统设置里是否登记了本服务（被 force-stop 后会消失）。 */
    fun isListed(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, KEY_ENABLED_SERVICES)
        }.getOrNull().orEmpty()
        if (raw.isEmpty()) return false
        return raw.split(':').any { item ->
            val name = item.trim()
            name == SHORT_COMPONENT || name == fullComponent.flattenToString()
        }
    }

    /** 当前是否真的处于启用状态（设置里有 + 系统把它列进 enabled 列表）。 */
    fun isEnabled(context: Context): Boolean {
        if (!isListed(context)) return false
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return true
        val enabled = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull().orEmpty()
        if (enabled.isEmpty()) return true   // 刚写完设置、系统还没绑定时不要误判成"掉了"
        return enabled.any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            si.packageName == context.packageName && si.name == SERVICE_CLASS
        }
    }

    /**
     * accessibility_enabled 这个总开关是否为 1。
     * 实测会碰到"服务已经绑定、但这个值还是 0"的脏状态（系统重置时留下的），
     * 这种状态重启后大概率真的掉线，所以发现不对就顺手写回 1。
     */
    fun isGlobalSwitchOn(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, KEY_ACCESSIBILITY_ENABLED, 1) == 1
    }.getOrDefault(true)

    /** 只把总开关写回 1（服务已在列表里、只是开关被置 0 的情况）。 */
    fun fixGlobalSwitch(context: Context): Boolean {
        if (!canWriteSecure(context)) return false
        return runCatching {
            Settings.Secure.putInt(context.contentResolver, KEY_ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "无障碍总开关被置 0，已写回 1")
            true
        }.getOrDefault(false)
    }

    /** 是否拿到了 WRITE_SECURE_SETTINGS（adb 一次性授权：pm grant ...）。 */
    fun canWriteSecure(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 把本服务写回系统设置。
     * @return true = 已经写成功（系统通常 1 秒内自动绑定）
     */
    fun restore(context: Context): Boolean {
        if (!canWriteSecure(context)) return false
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, KEY_ENABLED_SERVICES).orEmpty()
            val parts = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            val already = parts.any { it == SHORT_COMPONENT || it == fullComponent.flattenToString() }
            if (!already) parts.add(SHORT_COMPONENT)
            Settings.Secure.putString(resolver, KEY_ENABLED_SERVICES, parts.joinToString(":"))
            Settings.Secure.putInt(resolver, KEY_ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "已自动恢复无障碍服务：" + parts.joinToString(":"))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "自动恢复无障碍失败：" + t.message, t)
            false
        }
    }
}
