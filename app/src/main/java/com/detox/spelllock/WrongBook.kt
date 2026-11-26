package com.detox.spelllock

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 错词本里的一条记录：单词本身 + 答错次数 + 最近一次答错时间。 */
data class WrongWord(
    val word: String,
    val meaning: String,
    val phonetic: String,
    val mnemonic: String,
    /** 记下来时用的词书 id，便于看出这个词来自哪本书。 */
    val book: String,
    /** 累计答错次数。 */
    val count: Int,
    /** 最近一次答错的时间戳（毫秒）。 */
    val lastAt: Long
)

/**
 * 错词本：答错的单词自动记下来，按「答错次数降序 → 最近答错时间降序」排列。
 *
 * 存储：filesDir/wrong_book.json，一个 JSON 数组，字段用短名省空间：
 * w=单词 m=释义 p=音标 k=巧记 b=词书id c=次数 t=最近时间。
 * 容错：文件损坏/字段缺失都退化成空列表或跳过该条，绝不抛给界面。
 */
object WrongBook {

    private const val TAG = "SpellLock"
    private const val FILE_NAME = "wrong_book.json"

    private val lock = Any()

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    /** 全部错词，已按要求排好序：次数多的在前，同次数最近的在前。 */
    fun all(context: Context): List<WrongWord> = read(context).sortedWith(
        compareByDescending<WrongWord> { it.count }
            .thenByDescending { it.lastAt }
            .thenBy { it.word }
    )

    fun size(context: Context): Int = read(context).size

    /** 记一次答错：已有该词则次数 +1 并刷新时间，否则新增。 */
    fun record(context: Context, entry: WordEntry, bookId: String) {
        val word = entry.word.trim()
        if (word.isEmpty()) return
        synchronized(lock) {
            val list = read(context)
            val index = list.indexOfFirst { it.word.equals(word, ignoreCase = true) }
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val old = list[index]
                list[index] = old.copy(
                    meaning = entry.meaning.ifBlank { old.meaning },
                    phonetic = entry.phonetic.ifBlank { old.phonetic },
                    mnemonic = entry.mnemonic.ifBlank { old.mnemonic },
                    book = bookId,
                    count = old.count + 1,
                    lastAt = now
                )
            } else {
                list.add(
                    WrongWord(
                        word = word,
                        meaning = entry.meaning,
                        phonetic = entry.phonetic,
                        mnemonic = entry.mnemonic,
                        book = bookId,
                        count = 1,
                        lastAt = now
                    )
                )
            }
            write(context, list)
            val times = list.firstOrNull { it.word.equals(word, ignoreCase = true) }?.count ?: 1
            Log.i(TAG, "错词本已记录：" + word + "（第 " + times + " 次答错）")
        }
    }

    fun remove(context: Context, word: String) {
        synchronized(lock) {
            val list = read(context)
            val removed = list.removeAll { it.word.equals(word, ignoreCase = true) }
            if (removed) write(context, list)
            Log.i(TAG, "错词本移除：" + word + "，结果 " + removed)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            write(context, mutableListOf())
            Log.i(TAG, "错词本已清空")
        }
    }

    private fun read(context: Context): MutableList<WrongWord> {
        val file = file(context)
        if (!file.isFile || file.length() == 0L) return mutableListOf()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val out = mutableListOf<WrongWord>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val word = item.optString("w").trim()
                if (word.isEmpty()) continue
                out.add(
                    WrongWord(
                        word = word,
                        meaning = item.optString("m"),
                        phonetic = item.optString("p"),
                        mnemonic = item.optString("k"),
                        book = item.optString("b"),
                        count = item.optInt("c", 1).coerceAtLeast(1),
                        lastAt = item.optLong("t", 0L)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "错词本读取失败", t)
            mutableListOf()
        }
    }

    private fun write(context: Context, list: List<WrongWord>) {
        try {
            val array = JSONArray()
            list.forEach { entry ->
                array.put(JSONObject().apply {
                    put("w", entry.word)
                    put("m", entry.meaning)
                    put("p", entry.phonetic)
                    put("k", entry.mnemonic)
                    put("b", entry.book)
                    put("c", entry.count)
                    put("t", entry.lastAt)
                })
            }
            val target = file(context)
            val temp = File(target.parentFile, FILE_NAME + ".tmp")
            temp.writeText(array.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(array.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "错词本写入失败", t)
        }
    }
}
