package com.detox.spelllock

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错词本页面：把答错过的单词列出来，按答错次数降序（同次数按最近答错时间降序）。
 *
 * 交互：
 * - 长按某一行 → 单独删掉这个词
 * - 底部「清空错词本」→ 二次确认后全清
 * - 进入/返回本页都会重新读文件，所以在验证页答完题回来就能看到最新记录
 */
class WrongBookActivity : AppCompatActivity() {

    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnClear: Button

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_book)

        summaryText = findViewById(R.id.wrongSummary)
        emptyText = findViewById(R.id.wrongEmpty)
        listContainer = findViewById(R.id.wrongList)
        btnClear = findViewById(R.id.btnWrongClear)

        findViewById<View>(R.id.btnWrongBack).setOnClickListener { finish() }
        btnClear.setOnClickListener { confirmClear() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val words = WrongBook.all(this)
        summaryText.text = if (words.isEmpty()) {
            getString(R.string.wrong_summary_empty)
        } else {
            getString(R.string.wrong_summary, words.size)
        }
        emptyText.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        btnClear.isEnabled = words.isNotEmpty()
        btnClear.alpha = if (words.isNotEmpty()) 1f else 0.4f

        listContainer.removeAllViews()
        words.forEachIndexed { index, entry ->
            listContainer.addView(buildRow(index + 1, entry))
        }
    }

    private fun buildRow(rank: Int, entry: WrongWord): View {
        val row = layoutInflater.inflate(R.layout.item_wrong_word, listContainer, false)
        row.findViewById<TextView>(R.id.wrongRank).text = "#" + rank

        val wordText = row.findViewById<TextView>(R.id.wrongWord)
        wordText.text = entry.word

        val phoneticText = row.findViewById<TextView>(R.id.wrongPhonetic)
        if (entry.phonetic.isBlank()) {
            phoneticText.visibility = View.GONE
        } else {
            phoneticText.text = entry.phonetic
            phoneticText.visibility = View.VISIBLE
        }

        row.findViewById<TextView>(R.id.wrongMeaning).text = entry.meaning

        val countText = row.findViewById<TextView>(R.id.wrongCount)
        countText.text = getString(R.string.wrong_count_format, entry.count)
        countText.setTextColor(
            ContextCompat.getColor(
                this,
                if (entry.count >= 3) R.color.error_red else R.color.ok_green
            )
        )

        val timeText = row.findViewById<TextView>(R.id.wrongTime)
        timeText.text = if (entry.lastAt > 0L) {
            getString(R.string.wrong_time_format, timeFormat.format(Date(entry.lastAt)))
        } else {
            ""
        }

        val mnemonicText = row.findViewById<TextView>(R.id.wrongMnemonic)
        if (entry.mnemonic.isBlank()) {
            mnemonicText.visibility = View.GONE
        } else {
            mnemonicText.text = getString(R.string.mnemonic_prefix) + entry.mnemonic
            mnemonicText.visibility = View.VISIBLE
        }

        row.setOnLongClickListener {
            confirmRemove(entry)
            true
        }
        return row
    }

    private fun confirmRemove(entry: WrongWord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.wrong_remove_title)
            .setMessage(getString(R.string.wrong_remove_confirm, entry.word))
            .setPositiveButton(R.string.wrong_remove_ok) { _, _ ->
                WrongBook.remove(this, entry.word)
                render()
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (WrongBook.size(this) == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.wrong_clear)
            .setMessage(R.string.wrong_clear_confirm)
            .setPositiveButton(R.string.wrong_clear_ok) { _, _ ->
                WrongBook.clear(this)
                render()
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }
}
