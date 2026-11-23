package com.detox.spelllock

import android.content.Context
import android.util.Log
import java.util.Locale

data class WordEntry(
    val word: String,
    val meaning: String,
    /** 音标，形如 /əˈbændən/；没有音标时为空字符串。 */
    val phonetic: String = "",
    /** 巧记：这个词为什么这么背（摘自词书）；没有则空字符串。 */
    val mnemonic: String = ""
)

class WordLoadException(message: String) : Exception(message)

/**
 * 词表加载 + 严格校验。
 *
 * 规则（硬性）：
 * 1. assets/words.txt 每行格式 word|中文释义|巧记（第三段巧记可省略；# 开头为注释）。
 * 2. 每个单词必须出现在 assets/kaoyan_core_set.txt（考研英语词表快照）里。
 * 3. 白名单本身必须是完整的考研词表快照：首行有来源标记、每行格式合法、词数精确等于
 *    EXPECTED_KAOYAN_WORDS。只要白名单被加词/删词/换文件，一律判为异常 ——
 *    这样"同时改两个文件"也绕不过词表范围校验。
 * 4. 任何异常都不放行；可用词不足 REQUIRED_WORDS 个同样判为异常。
 */
object WordRepository {

    private const val TAG = "SpellLock"
    private const val WORDS_ASSET = "words.txt"
    private const val PHONETICS_ASSET = "phonetics.txt"
    private const val KAOYAN_INDEX_ASSET = "kaoyan_core_set.txt"

    const val REQUIRED_WORDS = 10

    /** 出现过的词 5 天内不再出现。 */
    private const val HISTORY_FILE = "word_history"
    private const val HISTORY_KEY = "recent"
    private const val HISTORY_TTL_MS = 5L * 24L * 60L * 60L * 1000L

    /** 考研词表白名单快照的精确词数。改动白名单必须同步改这里 + 更新 README。 */
    const val EXPECTED_KAOYAN_WORDS = 4801 // 与 assets/kaoyan_core_set.txt 的单词行数严格一致

    private val WORD_PATTERN = Regex("[A-Za-z][A-Za-z'-]*")
    private val KAOYAN_LINE_PATTERN = Regex("[a-z][a-z'-]*")

    @Volatile
    private var cache: List<WordEntry>? = null

    fun load(context: Context): List<WordEntry> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = parseAndValidate(context.applicationContext)
            cache = loaded
            return loaded
        }
    }

    fun invalidateCache() {
        synchronized(this) { cache = null }
    }

    /**
     * 抽 REQUIRED_WORDS 个不重复的考研词。
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
            "本次出题 " + picked.size + " 个词；5 天内已出现 " + recent.size +
                " 个，可选新词 " + fresh.size + " 个"
        )
        return picked
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

    private fun parseAndValidate(context: Context): List<WordEntry> {
        val wordsText = readAsset(context, WORDS_ASSET)
            ?: throw WordLoadException("缺少词表文件 assets/" + WORDS_ASSET)
        val indexText = readAsset(context, KAOYAN_INDEX_ASSET)
            ?: throw WordLoadException("缺少考研词汇校验文件 assets/" + KAOYAN_INDEX_ASSET)

        val kaoyanWords = parseKaoyanIndex(indexText)
        if (kaoyanWords.size != EXPECTED_KAOYAN_WORDS) {
            throw WordLoadException(
                "考研词汇校验文件异常：应为 " + EXPECTED_KAOYAN_WORDS + " 个词，实际 " +
                    kaoyanWords.size + " 个。白名单被改动过（增删词都会让校验失效），请恢复原始文件。"
            )
        }

        val phonetics = parsePhonetics(readAsset(context, PHONETICS_ASSET))
        val entries = parseWords(wordsText).map { entry ->
            entry.copy(phonetic = phonetics[entry.word.lowercase(Locale.ROOT)] ?: "")
        }
        if (entries.isEmpty()) {
            throw WordLoadException("词表为空，或格式不是 word|中文释义|巧记")
        }

        val outOfScope = entries.filter { it.word.lowercase(Locale.ROOT) !in kaoyanWords }
        if (outOfScope.isNotEmpty()) {
            val sample = outOfScope.take(5).joinToString("，") { it.word }
            throw WordLoadException(
                "词表异常：" + outOfScope.size + " 个词不在考研词汇表范围内（例如 " +
                    sample + "）。请把范围外的词移出 assets/words.txt 后重试。"
            )
        }

        if (entries.size < REQUIRED_WORDS) {
            throw WordLoadException(
                "可用考研词不足 " + REQUIRED_WORDS + " 个（当前 " + entries.size + " 个）"
            )
        }

        Log.i(TAG, "词表校验通过：可用考研词 " + entries.size + " 个")
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

    /** 音标表是可选增强：文件缺失或个别词没有音标都不影响出题。 */
    private fun parsePhonetics(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val result = HashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split("|")
            if (parts.size != 2) return@forEach
            val word = parts[0].trim().lowercase(Locale.ROOT)
            val ipa = parts[1].trim()
            if (word.isNotEmpty() && ipa.isNotEmpty()) {
                result[word] = ipa
            }
        }
        return result
    }

    private fun parseKaoyanIndex(text: String): Set<String> {
        // 来源标记：防止随便换一份"看起来像词表"的文件冒充考研词表
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!firstLine.startsWith("#") || !firstLine.contains("考研词汇")) {
            throw WordLoadException("考研词汇校验文件缺少来源标记（首行应标注「考研词汇」）")
        }
        val result = HashSet<String>(text.length / 6)
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (!KAOYAN_LINE_PATTERN.matches(line)) {
                throw WordLoadException(
                    "考研词汇校验文件格式错误：第 " + (index + 1) + " 行（" + line + "）"
                )
            }
            result.add(line.lowercase(Locale.ROOT))
        }
        return result
    }

    private fun parseWords(text: String): List<WordEntry> {
        val map = LinkedHashMap<String, WordEntry>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split("|", limit = 3)
            if (parts.size < 2) {
                throw WordLoadException("词表格式错误：第 " + (index + 1) + " 行不是 word|中文释义|巧记")
            }
            val word = parts[0].trim()
            val meaning = parts[1].trim()
            val mnemonic = if (parts.size >= 3) parts[2].trim() else ""
            if (word.isEmpty() || !WORD_PATTERN.matches(word)) {
                throw WordLoadException("词表格式错误：第 " + (index + 1) + " 行单词不合法（" + word + "）")
            }
            if (meaning.isEmpty()) {
                throw WordLoadException("词表格式错误：第 " + (index + 1) + " 行缺少中文释义")
            }
            val key = word.lowercase(Locale.ROOT)
            if (!map.containsKey(key)) {
                map[key] = WordEntry(word = key, meaning = meaning, mnemonic = mnemonic)
            }
        }
        return map.values.toList()
    }
}