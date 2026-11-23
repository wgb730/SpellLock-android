package com.detox.spelllock

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 「选择要拦截的应用」页：把手机里全部可启动应用列出来，勾选后写进 ExtraTargets。
 *
 * - 内置目标（TargetApps.kt 里的三款）固定勾选、不能取消，避免用户手滑把自己锁在外面。
 * - 保存时整体替换 ExtraTargets 的内容（不是逐个增删），取消勾选即从拦截名单里移除。
 * - 用 ListView + BaseAdapter，不额外引入 RecyclerView 依赖。
 */
class AppPickerActivity : AppCompatActivity() {

    private data class Row(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val builtIn: Boolean,
        val lockable: Boolean,
        var checked: Boolean
    )

    private lateinit var listView: ListView
    private lateinit var countText: TextView
    private lateinit var searchInput: EditText
    private lateinit var adapter: RowAdapter

    private val rows = mutableListOf<Row>()
    private var visible: List<Row> = emptyList()
    private var keyword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        listView = findViewById(R.id.appListView)
        countText = findViewById(R.id.appCountText)
        searchInput = findViewById(R.id.appSearchInput)
        adapter = RowAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = visible.getOrNull(position) ?: return@setOnItemClickListener
            if (row.builtIn) {
                toast("「" + row.label + "」是内置目标，始终生效")
                return@setOnItemClickListener
            }
            if (!row.lockable) {
                toast("「" + row.label + "」属于系统 / 桌面 / 输入法，不受锁限制")
                return@setOnItemClickListener
            }
            row.checked = !row.checked
            adapter.notifyDataSetChanged()
            updateCount()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { selectVisible(true) }
        findViewById<Button>(R.id.btnSelectNone).setOnClickListener { selectVisible(false) }
        findViewById<Button>(R.id.btnSaveApps).setOnClickListener { saveAndFinish() }

        loadApps()
    }

    private fun loadApps() {
        val extras = ExtraTargets.packages(this)
        Thread {
            val loaded = AppCatalog.launchableApps(applicationContext).map { app ->
                val builtIn = TargetApps.isTarget(app.packageName)
                val lockable = !ForegroundFilter.shouldIgnore(applicationContext, app.packageName)
                Row(
                    packageName = app.packageName,
                    label = app.label,
                    icon = app.icon,
                    builtIn = builtIn,
                    lockable = lockable,
                    checked = lockable && (builtIn || app.packageName in extras)
                )
            }
            runOnUiThread {
                rows.clear()
                rows.addAll(loaded)
                applyFilter()
                if (loaded.isEmpty()) toast("没有读到已安装的应用")
            }
        }.start()
    }

    private fun applyFilter() {
        visible = if (keyword.isEmpty()) {
            rows.toList()
        } else {
            rows.filter { row ->
                row.label.contains(keyword, ignoreCase = true) ||
                    row.packageName.contains(keyword, ignoreCase = true)
            }
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun selectVisible(checked: Boolean) {
        visible.forEach { row -> if (!row.builtIn && row.lockable) row.checked = checked }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        val picked = rows.count { it.checked && !it.builtIn }
        val builtIn = rows.count { it.builtIn }
        countText.text = "已选 " + picked + " 个自定义目标，另有 " + builtIn +
            " 个内置目标始终生效；当前显示 " + visible.size + " 个应用"
    }

    private fun saveAndFinish() {
        val picked = rows.filter { it.checked && !it.builtIn }.map { it.packageName }.toSet()
        ExtraTargets.setAll(this, picked)
        toast("已保存：自定义 " + picked.size + " 个 + 内置 " + TargetApps.activePackages.size + " 个")
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = visible.size

        override fun getItem(position: Int): Any? = visible.getOrNull(position)

        override fun getItemId(position: Int): Long =
            (visible.getOrNull(position)?.packageName?.hashCode() ?: position).toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_app_pick, parent, false)
            val row = visible.getOrNull(position) ?: return view
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(row.icon)
            view.findViewById<TextView>(R.id.appName).text = row.label
            view.findViewById<TextView>(R.id.appPackage).text = when {
                row.builtIn -> row.packageName + " · 内置目标"
                !row.lockable -> row.packageName + " · 系统/桌面/输入法，不受锁限制"
                else -> row.packageName
            }
            val check = view.findViewById<CheckBox>(R.id.appCheck)
            check.isChecked = row.checked
            check.isEnabled = !row.builtIn && row.lockable
            return view
        }
    }
}
