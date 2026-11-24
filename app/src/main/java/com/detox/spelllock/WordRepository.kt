package com.detox.spelllock

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

data class WordEntry(
    val word: String,
    val meaning: String,
    /** 音标，形如 /əˈbændən/；没有音标时为空字符串。 */
    val phonetic: String = "",
    /** 巧记：这个词为什么这么背（摘自词书）；没有则空字符串。 */
    val mnemonic: String = ""
)

/** 一本词书的元数据。builtin=true 的来自 assets，false 的是用户导入的 txt。 */
data class WordBook(
    val id: String,
    val title: String,
    val assetPath: String?,
    val expectedWords: Int,
    val builtin: Boolean = true
)

class WordLoadException(message: String) : Exception(message)

/**
 * 词表加载 + 严格校验（1.1.0 起支持多本词书）。
 *
 * 词书来源：
 * - 考研核心词 assets/books/kaoyan.txt：600 词 PDF 精选 + 懒笔记考研高频补充
 * - 四级核心词 assets/books/cet4.txt：懒笔记四六级高频词表 cet4 句频 ≥ 8
 * - 六级核心词 assets/books/cet6.txt：懒笔记四六级高频词表 cet6 句频 ≥ 8
 * - 自定义词书 filesDir/custom_book.txt：用户在主页导入的 txt
 *
 * 格式：
 * - 内置词书每行 word|音标|中文释义|巧记（巧记可空）
 * - 自定义词书每行 单词|中文释义|巧记（巧记可空，也可用 Tab 分隔）
 *
 * 校验（硬性）：每一行的格式、单词字符合法性、每个内置词书的词数必须精确等于 EXPECTED_* 常量。
 * 只要词表被加词/删词/换文件，一律判为异常 —— 这样"改一个文件就让验证失效"的尝试会被拦住。
 * 任何异常都不放行；可用词不足 REQUIRED_WORDS 个同样判为异常。
 */
object WordRepository {

    private const val TAG = "SpellLock"

    const val REQUIRED_WORDS = 10

    const val BOOK_KAOYAN = "kaoyan"
    const val BOOK_CET4 = "cet4"
    const val BOOK_CET6 = "cet6"
    const val BOOK_CUSTOM = "custom"

    /** 出现过的词 5 天内不再出现。 */
    private const val HISTORY_FILE = "word_history"
    private const val HISTORY_KEY = "recent"
    private const val HISTORY_TTL_MS = 5L * 24L * 60L * 60L * 1000L

    private const val CUSTOM_FILE = "custom_book.txt"

    /** 内置词书的精确词数。改动词表必须同步改这里 + 更新 README。 */
    const val EXPECTED_KAOYAN_WORDS = 1483
    const val EXPECTED_CET4_WORDS = 1616
    const val EXPECTED_CET6_WORDS = 2143

    private val BUILTIN_BOOKS = listOf(
        WordBook(BOOK_KAOYAN, "考研核心词", "books/kaoyan.txt", EXPECTED_KAOYAN_WORDS),
        WordBook(BOOK_CET4, "四级核心词", "books/cet4.txt", EXPECTED_CET4_WORDS),
        WordBook(BOOK_CET6, "六级核心词", "books/cet6.txt", EXPECTED_CET6_WORDS)
    )

    private val CUSTOM_BOOK = WordBook(BOOK_CUSTOM, "自定义词书", null, 0, builtin = false)

    private val WORD_PATTERN = Regex("[A-Za-z][A-Za-z'-]*")

    /** id -> 已加载词表。切换词书时按 id 分开缓存。 */
    private val cache = HashMap<String, List<WordEntry>>()

    /** 内置词书（不含自定义）。 */
    fun builtinBooks(): List<WordBook> = BUILTIN_BOOKS

    fun customBookFile(context: Context): File =
        File(context.applicationContext.filesDir, CUSTOM_FILE)

    fun hasCustomBook(context: Context): Boolean {
        val file = customBookFile(context)
        return file.isFile && file.length() > 0L
    }

    /** 当前可选的词书：三本内置 +（有导入过的）自定义。 */
    fun books(context: Context): List<WordBook> =
        if (hasCustomBook(context)) BUILTIN_BOOKS + CUSTOM_BOOK else BUILTIN_BOOKS

    fun bookById(context: Context, id: String): WordBook? = books(context).firstOrNull { it.id == id }

    fun currentBookId(context: Context): String {
        val saved = AppPrefs.wordBook(context)
        return if (bookById(context, saved) != null) saved else BOOK_KAOYAN
    }

    fun currentBook(context: Context): WordBook =
        bookById(context, currentBookId(context)) ?: BUILTIN_BOOKS[0]

    /** 切换词书：记到 prefs 并清掉加载缓存。 */
    fun selectBook(context: Context, id: String) {
        if (bookById(context, id) == null) throw WordLoadException("没有这本词书：" + id)
        AppPrefs.setWordBook(context, id)
        invalidateCache()
        Log.i(TAG, "切换词书：" + id)
    }

    fun load(context: Context): List<WordEntry> = loadBook(context, currentBookId(context))

    fun loadBook(context: Context, id: String): List<WordEntry> {
        cache[id]?.let { return it }
        synchronized(this) {
            cache[id]?.let { return it }
            val loaded = parseBook(context.applicationContext, id)
            cache[id] = loaded
            return loaded
        }
    }

    fun invalidateCache() {
        synchronized(this) { cache.clear() }
    }

    /**
     * 从当前词书抽 REQUIRED_WORDS 个不重复的词。
     * 5 天内出现过的词优先排除；排除后不够用时退回全量词表（宁可重复也不能少题）。
     * 抽中的词立刻写入历史，保证下次不再重复。任何异常都抛出，由验证页负责「不得放行」。
     */
    fun draw(context: Context): List<WordEntry> {
        val pool = load(context)
        if (pool.size < REQUIRED_WORDS) {
            throw WordLoadException(
                "词表可用单词不足 " + REQUIRED_WORDS + " 个（当前 " + pool.size + " 个）"
            )
        }
        val now = System.currentTimeMillis()
        val recent = readRecent(context, now)
        val fresh = pool.filter { recent[it.word.lowercase(Locale.ROOT)] == null }
        val source = if (fresh.size >= REQUIRED_WORDS) fresh else pool
        val picked = source.shuffled().take(REQUIRED_WORDS)
        writeRecent(context, now, recent, picked.map { it.word.lowercase(Locale.ROOT) })
        Log.i(
            TAG,
            "本次出题 " + picked.size + " 个词（词书 " + currentBookId(context) + "）；5 天内已出现 " +
                recent.size + " 个，可选新词 " + fresh.size + " 个"
        )
        return picked
    }

    /**
     * 导入自定义词书：先整篇校验，全部通过才落盘，并把当前词书切成自定义。
     * 返回导入的单词数；格式有问题抛 WordLoadException（提示语可直接给用户看）。
     */
    fun importCustomBook(context: Context, raw: String): Int {
        val entries = parseLines(raw, builtin = false)
        if (entries.size < REQUIRED_WORDS) {
            throw WordLoadException("词书里只有 " + entries.size + " 个词，至少要 " + REQUIRED_WORDS + " 个")
        }
        val normalized = entries.joinToString("\n") {
            listOf(it.word, it.meaning, it.mnemonic).joinToString("|")
        }
        val file = customBookFile(context)
        try {
            file.writeText(normalized + "\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "保存自定义词书失败", t)
            throw WordLoadException("保存词书失败：" + (t.message ?: t.javaClass.simpleName))
        }
        invalidateCache()
        AppPrefs.setWordBook(context, BOOK_CUSTOM)
        Log.i(TAG, "导入自定义词书：" + entries.size + " 个词")
        return entries.size
    }

    /** 读取 5 天内出现过的词（顺带把过期记录清掉）。 */
    private fun readRecent(context: Context, now: Long): MutableMap<String, Long> {
        val p = context.applicationContext
            .getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE)
        val raw = p.getString(HISTORY_KEY, "").orEmpty()
        val result = HashMap<String, Long>()
        raw.split(",").forEach { item ->
            if (item.isEmpty()) return@forEach
            val sep = item.lastIndexOf(':')
            if (sep <= 0) return@forEach
            val word = item.substring(0, sep)
            val ts = item.substring(sep + 1).toLongOrNull() ?: return@forEach
            if (now - ts in 0..HISTORY_TTL_MS) {
                result[word] = ts
            }
        }
        return result
    }

    private fun writeRecent(
        context: Context,
        now: Long,
        recent: Map<String, Long>,
        added: List<String>
    ) {
        val merged = LinkedHashMap<String, Long>()
        merged.putAll(recent)
        added.forEach { merged[it] = now }
        val text = merged.entries
            .sortedByDescending { it.value }
            .joinToString(",") { it.key + ":" + it.value }
        context.applicationContext
            .getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(HISTORY_KEY, text)
            .apply()
    }

    private fun parseBook(context: Context, id: String): List<WordEntry> {
        if (id == BOOK_CUSTOM) {
            val file = customBookFile(context)
            if (!file.isFile || file.length() == 0L) {
                throw WordLoadException("还没有导入自定义词书")
            }
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.e(TAG, "读取自定义词书失败", t)
                throw WordLoadException("读取自定义词书失败：" + (t.message ?: t.javaClass.simpleName))
            }
            val entries = parseLines(text, builtin = false)
            if (entries.size < REQUIRED_WORDS) {
                throw WordLoadException(
                    "自定义词书只有 " + entries.size + " 个词，至少要 " + REQUIRED_WORDS + " 个"
                )
            }
            Log.i(TAG, "自定义词书加载完成：" + entries.size + " 个词")
            return entries
        }

        val book = bookById(context, id) ?: throw WordLoadException("未知词书：" + id)
        val assetPath = book.assetPath ?: throw WordLoadException("词书缺少资源文件：" + id)
        val text = readAsset(context, assetPath)
            ?: throw WordLoadException("缺少词表文件 assets/" + assetPath)
        val entries = parseLines(text, builtin = true)
        if (entries.size != book.expectedWords) {
            throw WordLoadException(
                "词书「" + book.title + "」应为 " + book.expectedWords + " 个词，实际 " +
                    entries.size + " 个；词表被改动过（增删词都会让校验失效），请恢复原始文件。"
            )
        }
        Log.i(TAG, "词书「" + book.title + "」校验通过：" + entries.size + " 个词")
        return entries
    }

    private fun readAsset(context: Context, name: String): String? {
        return try {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.e(TAG, "读取 assets/" + name + " 失败", t)
            null
        }
    }

    /**
     * 解析词表文本。
     * builtin=true：word|音标|释义|巧记（音标、巧记都可空）
     * builtin=false：单词|释义|巧记（也接受 Tab 分隔）
     */
    private fun parseLines(text: String, builtin: Boolean): List<WordEntry> {
        val map = LinkedHashMap<String, WordEntry>()
        text.lineSequence().forEachIndexed { index, raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (!builtin && line.indexOf('|') < 0 && line.contains('\t')) {
                line = line.replace('\t', '|')
            }
            val parts = if (builtin) line.split("|", limit = 4) else line.split("|", limit = 3)
            val minParts = if (builtin) 3 else 2
            if (parts.size < minParts) {
                throw WordLoadException(
                    if (builtin) {
                        "词表格式错误：第 " + (index + 1) + " 行应为 word|音标|释义|巧记"
                    } else {
                        "第 " + (index + 1) + " 行格式不对，应为 单词|中文释义（可选再跟一段巧记）"
                    }
                )
            }
            val word = parts[0].trim()
            val phonetic = if (builtin) parts[1].trim() else ""
            val meaning = if (builtin) parts[2].trim() else parts[1].trim()
            val mnemonic = when {
                builtin && parts.size > 3 -> parts[3].trim()
                !builtin && parts.size > 2 -> parts[2].trim()
                else -> ""
            }
            if (word.isEmpty() || !WORD_PATTERN.matches(word)) {
                throw WordLoadException("第 " + (index + 1) + " 行不是合法英文单词（" + word + "）")
            }
            if (meaning.isEmpty()) {
                throw WordLoadException("第 " + (index + 1) + " 行缺少中文释义（" + word + "）")
            }
            val key = word.lowercase(Locale.ROOT)
            if (!map.containsKey(key)) {
                map[key] = WordEntry(
                    word = key,
                    meaning = meaning,
                    phonetic = phonetic,
                    mnemonic = mnemonic
                )
            }
        }
        if (map.isEmpty()) {
            throw WordLoadException("词表是空的，或者每行都缺格式")
        }
        return map.values.toList()
    }
}
