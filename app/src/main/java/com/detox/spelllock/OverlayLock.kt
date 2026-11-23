package com.detox.spelllock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 应用锁遮罩：用无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）把目标应用整屏盖住。
 *
 * 为什么需要它：只弹验证 Activity 时，用户一按 HOME 回到桌面再点开目标应用，
 * 验证页就不在前台了，应用照常可用。盖一层不透明遮罩后：
 * - 手指点不到下面的应用（遮罩吞掉全部触摸）
 * - 用户只能点「继续验证」回到验证页，或者直接回桌面（回桌面也算没在用）
 */
object OverlayLock {

    private const val TAG = "SpellLock"

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var serviceRef: AccessibilityService? = null

    fun isShowing(): Boolean = root != null

    fun attach(service: AccessibilityService) {
        serviceRef = service
    }

    fun show(service: AccessibilityService, packageName: String): Boolean {
        attach(service)
        if (root != null) return true
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.w(TAG, "拿不到 WindowManager，无法盖遮罩")
            return false
        }

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#F20B0E14"))
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }

        panel.addView(TextView(service).apply {
            text = AppCatalog.label(service, packageName) + " 已上锁"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        panel.addView(TextView(service).apply {
            text = "答完这一组单词才能继续使用\n（或在验证页输入急事密码直通）"
            setTextColor(Color.parseColor("#B9C2D0"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        })

        val verifyButton = Button(service).apply {
            text = "继续验证"
            textSize = 16f
            setOnClickListener { requestSpellCheck(service, packageName) }
        }
        panel.addView(verifyButton, marginParams(42))

        val homeButton = Button(service).apply {
            text = "先回桌面"
            textSize = 15f
            setOnClickListener { goHome(service) }
        }
        panel.addView(homeButton, marginParams(18))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        root = FrameLayout(service)
        (root as FrameLayout).addView(panel)
        (root as FrameLayout).setOnTouchListener { _, _ -> true }

        return try {
            wm.addView(root, params)
            windowManager = wm
            Log.i(TAG, "遮罩已盖上：" + packageName)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "盖遮罩失败", t)
            root = null
            windowManager = null
            false
        }
    }

    private fun marginParams(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    fun hide() {
        val view = root ?: return
        try {
            windowManager?.removeView(view)
            Log.i(TAG, "遮罩已移除")
        } catch (t: Throwable) {
            Log.w(TAG, "移除遮罩失败", t)
        }
        root = null
        windowManager = null
    }

    private fun requestSpellCheck(service: AccessibilityService, packageName: String) {
        val intent = Intent(service, SpellCheckActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(SpellCheckActivity.EXTRA_TARGET_PACKAGE, packageName)
        }
        try {
            service.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "从遮罩拉起验证页失败", t)
        }
    }

    private fun goHome(service: AccessibilityService) {
        hide()
        try {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(home)
        } catch (t: Throwable) {
            Log.e(TAG, "回桌面失败", t)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }
}
