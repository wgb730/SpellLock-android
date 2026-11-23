package com.detox.spelllock

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RadioGroup
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION = 1001
    }

    private lateinit var accessibilityStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var targetList: TextView
    private lateinit var scannerResult: TextView
    private lateinit var scanResultContainer: LinearLayout
    private lateinit var manualPackageInput: EditText
    private lateinit var extraTargetsText: TextView
    private lateinit var graceStatus: TextView
    private lateinit var switchFallback: MaterialSwitch
    private lateinit var switchChoiceMode: MaterialSwitch

    private val handler = Handler(Looper.getMainLooper())

    private val graceTicker = object : Runnable {
        override fun run() {
            refreshGraceText()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        notificationStatus = findViewById(R.id.notificationStatus)
        targetList = findViewById(R.id.targetList)
        scannerResult = findViewById(R.id.scannerResult)
        scanResultContainer = findViewById(R.id.scanResultContainer)
        manualPackageInput = findViewById(R.id.manualPackageInput)
        extraTargetsText = findViewById(R.id.extraTargetsText)
        graceStatus = findViewById(R.id.graceStatus)
        switchFallback = findViewById(R.id.switchFallback)
        switchChoiceMode = findViewById(R.id.switchChoiceMode)

        scannerResult.text = "可在电脑上执行：adb shell pm list packages | grep -i aweme"

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                toast("无法打开无障碍设置，请手动到 设置 -> 无障碍")
            }
        }

        findViewById<Button>(R.id.btnNotification).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            } else {
                toast("当前系统版本无需单独申请通知权限")
            }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            startActivity(Intent(this, SpellCheckActivity::class.java))
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { scanInstalledApps() }

        findViewById<Button>(R.id.btnPickApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        findViewById<Button>(R.id.btnAddManual).setOnClickListener {
            val input = manualPackageInput.text?.toString()?.trim().orEmpty()
            if (input.isEmpty() || !input.contains(".")) {
                toast("请输入正确的包名，例如 com.xxx.yyy")
                return@setOnClickListener
            }
            if (TargetApps.isTarget(input)) {
                toast("这个包名已经在 TargetApps.kt 里了")
                return@setOnClickListener
            }
            ExtraTargets.add(this, input)
            manualPackageInput.setText("")
            refreshAll()
            toast("已添加额外目标：" + input)
        }

        findViewById<Button>(R.id.btnClearExtra).setOnClickListener {
            ExtraTargets.clear(this)
            refreshAll()
            toast("已清空额外目标")
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = scannerResult.text?.toString().orEmpty()
            if (text.isEmpty()) {
                toast("没有可复制的内容")
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("包名检测结果", text))
                toast("已复制")
            }
        }

        switchFallback.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setUseNotificationFallback(this, checked)
        }

        findViewById<Button>(R.id.btnResetGrace).setOnClickListener {
            UnlockGate.revoke(this)
            refreshGraceText()
            toast("已清除宽限期，下次打开目标 App 会立即验证")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        handler.post(graceTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(graceTicker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) refreshAll()
    }

    private fun refreshAll() {
        val accessibilityOn = isAccessibilityServiceEnabled()
        accessibilityStatus.text = if (accessibilityOn) {
            "✅ 无障碍服务已开启"
        } else {
            "⚠️ 无障碍服务未开启：目标 App 被打开时不会拦截"
        }

        val notificationOn = BlockNotifier.canPostNotification(this)
        notificationStatus.text = if (notificationOn) {
            "✅ 通知权限正常（降级方案可用）"
        } else {
            "⚠️ 通知权限未开启（后台启动被限制时无法提醒）"
        }

        switchFallback.setOnCheckedChangeListener(null)
        switchFallback.isChecked = AppPrefs.useNotificationFallback(this)
        switchFallback.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setUseNotificationFallback(this, checked)
        }

        switchChoiceMode.setOnCheckedChangeListener(null)
        switchChoiceMode.isChecked = AppPrefs.choiceMode(this)
        switchChoiceMode.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setChoiceMode(this, checked)
        }

        targetList.text = buildTargetListText()
        extraTargetsText.text = buildExtraTargetsText()
        syncAnswerModeUi()
        refreshGraceText()
    }

    private fun refreshGraceText() {
        val grace = UnlockGate.remainingSeconds(this)
        val usageLeftSec = UnlockGate.usageRemainingMillis(this) / 1000L
        val usedSec = UnlockGate.usedMillis(this) / 1000L
        val limitSec = UnlockGate.USAGE_LIMIT_MS / 1000L
        val usageText = if (usageLeftSec > 0L) {
            "本次放行：已用 " + (usedSec / 60L) + " 分 " + (usedSec % 60L) + " 秒，还可再用 " +
                (usageLeftSec / 60L) + " 分 " + (usageLeftSec % 60L) +
                " 秒（三款应用累计满 " + (limitSec / 60L) + " 分钟会再测一次）"
        } else {
            "本次放行已用完：下次打开目标 App 需要重新验证"
        }
        graceStatus.text = "免检放行剩余：" + grace + " 秒\n" + usageText
    }

    private fun buildTargetListText(): String {
        val installed = installedPackages()
        val builder = StringBuilder()
        TargetApps.apps.forEach { app ->
            val state = when {
                app.packageName == TargetApps.TODO_PACKAGE -> "包名待确认（TODO）"
                app.packageName in installed -> "已安装 ✓"
                else -> "未安装"
            }
            builder.append("• ").append(app.displayName).append("\n")
            builder.append("    ").append(app.packageName).append(" · ").append(state).append("\n")
        }
        return builder.toString().trim()
    }

    private fun buildExtraTargetsText(): String {
        val extras = ExtraTargets.packages(this).sortedBy { AppCatalog.label(this, it) }
        if (extras.isEmpty()) {
            return "额外目标：无。\n点上面的「从全部应用里挑」可以勾选任意应用加入拦截名单。"
        }
        val builder = StringBuilder("额外目标（自定义 " + extras.size + " 个）：")
        extras.forEach { pkg ->
            builder.append("\n• ").append(AppCatalog.label(this, pkg))
                .append("（").append(pkg).append("）")
        }
        return builder.toString()
    }

    private fun syncAnswerModeUi() {
        val group = findViewById<RadioGroup>(R.id.answerModeGroup)
        group.setOnCheckedChangeListener(null)
        when (AppPrefs.answerRevealMode(this)) {
            AppPrefs.ANSWER_MODE_NEVER -> group.check(R.id.modeNever)
            AppPrefs.ANSWER_MODE_ALWAYS -> group.check(R.id.modeAlways)
            else -> group.check(R.id.modeOnError)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            AppPrefs.setAnswerRevealMode(this, answerModeFromId(checkedId))
        }
    }

    private fun answerModeFromId(checkedId: Int): Int {
        return when (checkedId) {
            R.id.modeNever -> AppPrefs.ANSWER_MODE_NEVER
            R.id.modeAlways -> AppPrefs.ANSWER_MODE_ALWAYS
            else -> AppPrefs.ANSWER_MODE_ON_ERROR
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AppAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.isNotEmpty()) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (item in splitter) {
                val component = ComponentName.unflattenFromString(item) ?: continue
                if (component == expected) return true
            }
        }
        return try {
            val manager = getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    serviceInfo != null &&
                        serviceInfo.packageName == expected.packageName &&
                        serviceInfo.name == expected.className
                } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): Set<String> {
        return installedLauncherApps().map { it.first }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun installedLauncherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = mutableListOf<Pair<String, String>>()
        try {
            packageManager.queryIntentActivities(intent, 0).forEach { info ->
                val packageName = info.activityInfo?.packageName ?: return@forEach
                val label = try {
                    info.loadLabel(packageManager).toString()
                } catch (t: Throwable) {
                    packageName
                }
                result.add(packageName to label)
            }
        } catch (t: Throwable) {
            // 忽略
        }
        return result.distinctBy { it.first }.sortedBy { it.second }
    }

    private fun scanInstalledApps() {
        val apps = installedLauncherApps()
        val keywords = TargetApps.scannerKeywords
        val hits = apps.filter { (packageName, label) ->
            keywords.any { packageName.contains(it, ignoreCase = true) } ||
                label.contains("抖音") ||
                label.contains("哔哩") ||
                label.contains("bilibili", ignoreCase = true)
        }

        scanResultContainer.removeAllViews()

        if (hits.isEmpty()) {
            scannerResult.text = "没有扫描到抖音 / 哔哩哔哩相关应用。\n" +
                "请确认目标应用已安装，或用电脑执行：\n" +
                "adb shell pm list packages | grep -i aweme"
            return
        }

        scannerResult.text = "共找到 " + hits.size + " 个候选应用，点按钮即可加入目标列表："
        hits.forEach { (packageName, label) ->
            val button = AppCompatButton(this)
            button.text = "添加：" + label + "（" + packageName + "）"
            button.isAllCaps = false
            button.setTextColor(ContextCompat.getColor(this, R.color.accent))
            button.setOnClickListener {
                ExtraTargets.add(this, packageName)
                refreshAll()
                toast("已加入目标应用：" + label)
            }
            scanResultContainer.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}