package com.detox.spelllock

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 降级方案：
 * 部分机型（尤其国产 ROM）禁止后台启动 Activity，这时发一条高优先级通知：
 * - 通知带 setFullScreenIntent：系统允许时会直接把验证页拉到前台（系统发起，不受后台启动限制）
 * - 不允许全屏 Intent 时，用户点通知也能进入验证页（PendingIntent 由系统发起，同样豁免）
 */
object BlockNotifier {

    private const val TAG = "SpellLock"
    private const val CHANNEL_ID = "spelllock_guard"
    private const val BASE_NOTIFICATION_ID = 4100

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用使用验证提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "目标应用被打开、但系统不允许自动弹出验证页时，用这条通知提醒你完成拼写验证。"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Android 14+ 可能不授予全屏 Intent 权限，这没关系：点击通知仍可进入验证页。 */
    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            return manager?.canUseFullScreenIntent() ?: false
        }
        return true
    }

    fun postGuardNotification(context: Context, targetPackage: String?) {
        if (!canPostNotification(context)) {
            Log.w(TAG, "通知权限不可用，降级方案无法提醒用户")
            return
        }
        ensureChannel(context)

        val displayName = AppCatalog.label(context, targetPackage)

        val intent = Intent(context, SpellCheckActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SpellCheckActivity.EXTRA_TARGET_PACKAGE, targetPackage)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(targetPackage),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("你打开了" + displayName)
            .setContentText("先完成这组单词（选择题 + 拼写），才能继续使用。点此进入。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setOngoing(false)

        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(targetPackage), builder.build())
            Log.i(TAG, "已发送验证提醒通知：" + displayName)
        } catch (t: Throwable) {
            Log.e(TAG, "发送通知失败", t)
        }
    }

    /** 每个目标应用用独立通知 id，避免多个提醒互相覆盖。 */
    private fun notificationId(targetPackage: String?): Int {
        val hash = (targetPackage ?: "").hashCode() and 0xFF
        return BASE_NOTIFICATION_ID + hash
    }
}
