package com.detox.spelllock

import android.content.Context

object AppPrefs {

    private const val FILE = "spelllock_prefs"
    private const val KEY_NOTIFICATION_FALLBACK = "notification_fallback"
    private const val KEY_ANSWER_MODE = "answer_reveal_mode"
    private const val KEY_CHOICE_MODE = "choice_mode"
    private const val KEY_WORD_BOOK = "word_book"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"
    private const val KEY_GUARD_ENABLED = "guard_enabled"

    /** 验证页答案展示方式 */
    const val ANSWER_MODE_NEVER = 0      // 不显示（纯考核）
    const val ANSWER_MODE_ON_ERROR = 1   // 答错后显示正确拼写，然后要求重拼（默认）
    const val ANSWER_MODE_ALWAYS = 2     // 一开始就显示正确拼写（先看再写）

    /**
     * 守护开关：true = 无障碍被系统关掉时自动写回。
     * 设成 false 后守护服务不再动手（用户想彻底停用时用，避免被"自动打开"惹烦）。
     */
    fun guardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUARD_ENABLED, true)

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply()
    }

    /** 急事直通密码：输入后立刻放行，不答题。默认 123456，可在主页改。 */
    private const val KEY_ADMIN_PASSWORD = "admin_password"
    const val DEFAULT_ADMIN_PASSWORD = "123456"

    /**
     * 固定间隔：验证通过后，不管用不用目标 App，时间一到就得重新验证。
     * 从 v1.2.0 起只有这一个时间设置。
     */
    const val DEFAULT_INTERVAL_MINUTES = 30
    const val MIN_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 480

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true = 不走「后台直接弹 Activity」，改成发高优先级通知。 */
    fun useNotificationFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_FALLBACK, false)

    fun setUseNotificationFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_FALLBACK, enabled).apply()
    }

    fun answerRevealMode(context: Context): Int =
        prefs(context).getInt(KEY_ANSWER_MODE, ANSWER_MODE_ON_ERROR)

    fun setAnswerRevealMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_ANSWER_MODE, mode).apply()
    }

    /** true = 选择题模式：先给英文 + 4 个中文释义，选错的词再做拼写。 */
    fun choiceMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHOICE_MODE, false)

    fun setChoiceMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHOICE_MODE, enabled).apply()
    }

    /** 当前词书 id（kaoyan / cet4 / cet6 / custom）。 */
    fun wordBook(context: Context): String =
        prefs(context).getString(KEY_WORD_BOOK, WordRepository.BOOK_KAOYAN)
            ?: WordRepository.BOOK_KAOYAN

    fun setWordBook(context: Context, id: String) {
        prefs(context).edit().putString(KEY_WORD_BOOK, id).apply()
    }

    /** 固定间隔（分钟）：验证通过后多久必须重新验证，与是否使用无关。 */
    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(MIN_MINUTES, MAX_INTERVAL_MINUTES)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_INTERVAL_MINUTES, minutes.coerceIn(MIN_MINUTES, MAX_INTERVAL_MINUTES))
            .apply()
    }

    /** 当前生效的急事直通密码（没改过就是默认 123456）。 */
    fun adminPassword(context: Context): String {
        val saved = prefs(context).getString(KEY_ADMIN_PASSWORD, null)
        return if (saved.isNullOrBlank()) DEFAULT_ADMIN_PASSWORD else saved
    }

    fun setAdminPassword(context: Context, password: String) {
        prefs(context).edit().putString(KEY_ADMIN_PASSWORD, password).apply()
    }

    fun isAdminPassword(context: Context, input: String?): Boolean {
        val text = input?.trim()
        return !text.isNullOrEmpty() && text == adminPassword(context)
    }
}
