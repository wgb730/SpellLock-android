package com.detox.spelllock

import android.content.Context

object AppPrefs {

    private const val FILE = "spelllock_prefs"
    private const val KEY_NOTIFICATION_FALLBACK = "notification_fallback"
    private const val KEY_ANSWER_MODE = "answer_reveal_mode"
    private const val KEY_CHOICE_MODE = "choice_mode"
    private const val KEY_WORD_BOOK = "word_book"
    private const val KEY_GRACE_MINUTES = "grace_minutes"
    private const val KEY_USAGE_MINUTES = "usage_minutes"
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

    /** 时间设置：免检间隔（验证通过后多久内不再拦截）与使用上限（目标 App 累计可用多久）。 */
    const val DEFAULT_GRACE_MINUTES = 5
    const val DEFAULT_USAGE_MINUTES = 20
    const val MIN_MINUTES = 1
    const val MAX_GRACE_MINUTES = 120
    const val MAX_USAGE_MINUTES = 480

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

    /** 验证通过后的免检间隔（分钟）。 */
    fun graceMinutes(context: Context): Int =
        prefs(context).getInt(KEY_GRACE_MINUTES, DEFAULT_GRACE_MINUTES)
            .coerceIn(MIN_MINUTES, MAX_GRACE_MINUTES)

    fun setGraceMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_GRACE_MINUTES, minutes.coerceIn(MIN_MINUTES, MAX_GRACE_MINUTES))
            .apply()
    }

    /** 目标应用累计可用时长（分钟）。 */
    fun usageMinutes(context: Context): Int =
        prefs(context).getInt(KEY_USAGE_MINUTES, DEFAULT_USAGE_MINUTES)
            .coerceIn(MIN_MINUTES, MAX_USAGE_MINUTES)

    fun setUsageMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_USAGE_MINUTES, minutes.coerceIn(MIN_MINUTES, MAX_USAGE_MINUTES))
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
