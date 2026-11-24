package com.detox.spelllock

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.random.Random

/**
 * 全屏验证页（两种模式）：
 * - 拼写模式（默认）：随机 10 个考研词，给中文释义 + 音标，输入英文单词
 * - 选择模式：先给英文单词 + 4 个中文释义；选错的单词再进入拼写
 * 公共约束：
 * - 禁用返回键（物理键 + 手势返回都无效），没有跳过按钮
 * - 词表加载失败 / 校验不通过：永久停留，不放行
 * - 放行方式：记录解锁时间戳 -> finishAndRemoveTask()，下层目标 App 原样显现
 * - 急事直通：输入管理员密码可立刻放行
 */
class SpellCheckActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val TAG = "SpellLock"
        private const val OPTION_COUNT = 4
    }

    private lateinit var container: LinearLayout
    private lateinit var choiceContainer: LinearLayout
    private lateinit var choiceTitle: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var progressText: TextView
    private lateinit var verifyButton: Button
    private lateinit var retryButton: Button
    private lateinit var homeButton: Button
    private lateinit var adminLink: TextView
    private lateinit var rootScroll: ScrollView

    /** 本轮抽到的词（选择题阶段用） */
    private var quizWords: List<WordEntry> = emptyList()

    /** 当前要拼写的词（拼写阶段用；选择模式下只放选错的词） */
    private var words: List<WordEntry> = emptyList()

    private val answerInputs = mutableListOf<EditText>()
    private val answerTexts = mutableListOf<TextView>()
    private val mnemonicTexts = mutableListOf<TextView>()

    /** 选择题里选错的词 */
    private val wrongWords = mutableListOf<WordEntry>()
    private var answeredCount = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spell_check)
        SelfGuard.onSpellCheckCreated()

        container = findViewById(R.id.wordContainer)
        choiceContainer = findViewById(R.id.choiceContainer)
        choiceTitle = findViewById(R.id.choiceTitle)
        subtitleText = findViewById(R.id.subtitleText)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        progressText = findViewById(R.id.progressText)
        verifyButton = findViewById(R.id.verifyButton)
        retryButton = findViewById(R.id.retryButton)
        homeButton = findViewById(R.id.homeButton)
        adminLink = findViewById(R.id.adminLink)
        rootScroll = findViewById(R.id.rootScroll)

        hideSystemBars()
        setupImeHandling()
        blockBackKey()
        updateTargetLabel(intent)
        initTts()

        verifyButton.setOnClickListener { verifyAnswers() }
        retryButton.setOnClickListener {
            WordRepository.invalidateCache()
            loadWords()
        }
        homeButton.setOnClickListener { goHome() }
        adminLink.setOnClickListener { askAdminPassword() }

        loadWords()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateTargetLabel(intent)
    }

    override fun onStart() {
        super.onStart()
        SelfGuard.onSpellCheckShown()
        // 验证页已经盖在最上面，遮罩没必要留着
        OverlayLock.hide()
    }

    override fun onStop() {
        SelfGuard.onSpellCheckHidden()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 TTS 失败", t)
        }
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    // ---------- 语音朗读 ----------

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    try {
                        tts?.setLanguage(Locale.US)
                    } catch (t: Throwable) {
                        Log.w(TAG, "设置 TTS 语言失败", t)
                    }
                } else {
                    Log.w(TAG, "系统 TTS 初始化失败")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "创建 TTS 失败", t)
            tts = null
            ttsReady = false
        }
    }

    private fun speakWord(word: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            Toast.makeText(this, "系统语音引擎未就绪，稍后再点一下", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            engine.stop()
            engine.speak(word, TextToSpeech.QUEUE_FLUSH, null, "spelllock_word")
        } catch (t: Throwable) {
            Log.e(TAG, "朗读失败", t)
        }
    }

    // ---------- 页面文案 ----------

    private fun updateTargetLabel(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (target.isNullOrBlank()) {
            subtitleText.text = getString(R.string.verify_subtitle)
            return
        }
        subtitleText.text = getString(R.string.verify_subtitle) +
            "\n目标应用：" + AppCatalog.label(this, target)
    }

    // ---------- 返回键拦截 ----------

    private fun blockBackKey() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 故意留空：验证未通过，不允许退出
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 故意留空：兼容旧系统/旧版本的返回键分发
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 全屏 ----------

    /**
     * 边到边全屏下，系统不会自动给输入法让位（windowSoftInputMode=adjustResize 只对
     * decorFitsSystemWindows=true 生效），所以这里手动把输入法高度换成滚动区域的底部留白，
     * 内容会随键盘上移，输入框不会被挡住。
     */
    private fun setupImeHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(0, 0, 0, if (imeBottom > navBottom) imeBottom else navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootScroll)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 回到桌面：走系统 HOME，不把目标 App 顶到前台。 */
    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (t: Throwable) {
            Log.e(TAG, "打开桌面失败", t)
            moveTaskToBack(true)
        }
    }

    // ---------- 词表加载 ----------

    private fun loadWords() {
        showLoading()
        Thread {
            try {
                val drawn = WordRepository.draw(applicationContext)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showWords(drawn)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "词表加载失败，阻止进入", t)
                val message = t.message ?: t.javaClass.simpleName
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showWordError(message)
                }
            }
        }.start()
    }

    private fun showLoading() {
        quizWords = emptyList()
        words = emptyList()
        wrongWords.clear()
        answeredCount = 0
        answerInputs.clear()
        answerTexts.clear()
        mnemonicTexts.clear()
        container.removeAllViews()
        choiceContainer.removeAllViews()
        choiceContainer.visibility = View.GONE
        choiceTitle.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        homeButton.visibility = View.GONE
        verifyButton.visibility = View.VISIBLE
        verifyButton.isEnabled = false
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.loading_words)
        progressText.text = ""
    }

    private fun showWords(drawn: List<WordEntry>) {
        quizWords = drawn
        wrongWords.clear()
        answeredCount = 0
        container.removeAllViews()
        choiceContainer.removeAllViews()
        statusText.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        homeButton.visibility = View.GONE

        if (AppPrefs.choiceMode(this)) {
            startChoicePhase()
        } else {
            choiceContainer.visibility = View.GONE
            choiceTitle.visibility = View.GONE
            startSpellingPhase(drawn)
        }
    }

    // ---------- 第一阶段：四选一 ----------

    private fun startChoicePhase() {
        choiceContainer.removeAllViews()
        choiceContainer.visibility = View.VISIBLE
        choiceTitle.visibility = View.VISIBLE
        choiceTitle.text = getString(R.string.choice_title) + "\n" + getString(R.string.choice_hint)
        container.removeAllViews()
        verifyButton.visibility = View.GONE
        verifyButton.isEnabled = false
        progressText.text = "已选 0 / " + quizWords.size

        val pool = try {
            WordRepository.load(applicationContext)
        } catch (t: Throwable) {
            quizWords
        }

        quizWords.forEach { entry ->
            val options = buildOptions(entry, pool)
            choiceContainer.addView(buildChoiceCard(entry, options))
        }
    }

    /** 生成 4 个选项：1 个正确释义 + 3 个干扰释义（同词表内随机取，去重）。 */
    private fun buildOptions(entry: WordEntry, pool: List<WordEntry>): List<Pair<String, Boolean>> {
        val picked = LinkedHashSet<String>()
        picked.add(entry.meaning)
        var guard = 0
        while (picked.size < OPTION_COUNT && guard < 400) {
            guard++
            val other = pool[Random.nextInt(pool.size)]
            if (other.word == entry.word) continue
            if (other.meaning.isBlank()) continue
            picked.add(other.meaning)
        }
        val all = picked.map { it to (it == entry.meaning) }
        return all.shuffled()
    }

    private fun buildChoiceCard(entry: WordEntry, options: List<Pair<String, Boolean>>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = entry.word
            setTextColor(ContextCompat.getColor(this@SpellCheckActivity, R.color.text_primary))
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (entry.phonetic.isNotBlank()) {
            head.addView(TextView(this).apply {
                text = entry.phonetic
                setTextColor(ContextCompat.getColor(this@SpellCheckActivity, R.color.accent))
                textSize = 13f
                setPadding(dp(10), 0, 0, 0)
            })
        }
        card.addView(head)

        var lockedOut = false
        val buttons = mutableListOf<Button>()
        val correctIndex = options.indexOfFirst { it.second }

        options.forEachIndexed { index, pair ->
            val isRight = pair.second
            val label = ('A' + index) + ". " + pair.first
            val button = Button(this).apply {
                text = label
                textSize = 15f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_secondary_button)
                setTextColor(ContextCompat.getColor(this@SpellCheckActivity, R.color.text_primary))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            button.layoutParams = lp
            buttons.add(button)

            button.setOnClickListener {
                if (lockedOut) return@setOnClickListener
                lockedOut = true
                answeredCount++
                if (isRight) {
                    button.setBackgroundResource(R.drawable.bg_option_correct)
                } else {
                    button.setBackgroundResource(R.drawable.bg_option_wrong)
                    buttons.getOrNull(correctIndex)
                        ?.setBackgroundResource(R.drawable.bg_option_correct)
                    wrongWords.add(entry)
                }
                buttons.forEach { b -> b.isEnabled = false }
                progressText.text = "已选 " + answeredCount + " / " + quizWords.size
                if (answeredCount >= quizWords.size) {
                    choiceContainer.postDelayed({ afterChoicePhase() }, 700L)
                }
            }
            card.addView(button)
        }
        return card
    }

    private fun afterChoicePhase() {
        if (isFinishing || isDestroyed) return
        if (wrongWords.isEmpty()) {
            unlockAndFinish()
            return
        }
        startSpellingPhase(wrongWords.toList())
    }

    // ---------- 第二阶段：拼写 ----------

    private fun startSpellingPhase(entries: List<WordEntry>) {
        words = entries
        answerInputs.clear()
        answerTexts.clear()
        mnemonicTexts.clear()
        container.removeAllViews()
        choiceContainer.visibility = View.GONE
        choiceTitle.visibility = View.GONE
        statusText.visibility = View.GONE
        errorText.visibility = View.GONE
        retryButton.visibility = View.GONE
        homeButton.visibility = View.GONE
        verifyButton.visibility = View.VISIBLE
        verifyButton.isEnabled = true

        if (entries.isEmpty()) {
            unlockAndFinish()
            return
        }

        if (quizWords.isNotEmpty() && entries.size < quizWords.size) {
            choiceTitle.visibility = View.VISIBLE
            choiceTitle.text = getString(R.string.spelling_title)
        }

        entries.forEachIndexed { index, entry -> addWordRow(index, entry) }

        // 模式二：一开始就把正确拼写摆出来（先看再写）
        if (AppPrefs.answerRevealMode(this) == AppPrefs.ANSWER_MODE_ALWAYS) {
            entries.indices.forEach { revealAnswer(it) }
        }
        updateProgress()
    }

    private fun addWordRow(index: Int, entry: WordEntry) {
        val row = layoutInflater.inflate(R.layout.item_word_input, container, false)
        val meaningText = row.findViewById<TextView>(R.id.meaningText)
        val phoneticText = row.findViewById<TextView>(R.id.phoneticText)
        val phoneticBox = row.findViewById<View>(R.id.phoneticBox)
        val answerText = row.findViewById<TextView>(R.id.answerText)
        val mnemonicText = row.findViewById<TextView>(R.id.mnemonicText)
        val input = row.findViewById<EditText>(R.id.answerInput)

        meaningText.text = (index + 1).toString() + ". " + entry.meaning

        if (entry.phonetic.isBlank()) {
            phoneticText.visibility = View.GONE
        } else {
            phoneticText.text = entry.phonetic
        }
        // 点音标胶囊即可朗读这个单词
        phoneticBox.contentDescription = "朗读 " + entry.word
        phoneticBox.setOnClickListener { speakWord(entry.word) }

        input.hint = "第 " + (index + 1) + " 题：输入英文单词"
        val action = if (index == words.size - 1) {
            EditorInfo.IME_ACTION_DONE
        } else {
            EditorInfo.IME_ACTION_NEXT
        }
        // 保留 XML 里的 flagNoExtractUi，避免全屏输入框遮盖验证页
        input.imeOptions = action or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                verifyAnswers()
                true
            } else {
                false
            }
        }

        // 已经给出正确答案时：用户一动手输入，正确答案立刻消失（防止照着抄）。
        // 连带把后面 3 题（index+1 ~ index+3）的答案也收起来，
        // 避免「抄完这一题就翻到下一题的答案」。
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) return
                hideAnswersFrom(index, 4)
            }
        })

        // 键盘弹出后，把当前这一行顶到可见区域，别被输入法遮住
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            row.postDelayed({
                val rect = Rect(0, 0, row.width, row.height + dp(120))
                row.requestRectangleOnScreen(rect, true)
            }, 320L)
        }

        container.addView(row)
        answerInputs.add(input)
        answerTexts.add(answerText)
        mnemonicTexts.add(mnemonicText)
    }

    /**
     * 从 [fromIndex] 开始，连续收起 [count] 道题的答案（含巧记）。
     * 用户开始输入某一题时调用：本题 + 后面 3 题的答案一起消失。
     */
    private fun hideAnswersFrom(fromIndex: Int, count: Int) {
        val end = minOf(fromIndex + count, answerTexts.size)
        for (i in fromIndex until end) {
            val answer = answerTexts.getOrNull(i) ?: continue
            if (answer.visibility == View.VISIBLE) {
                answer.text = ""
                answer.visibility = View.GONE
            }
            val mnemonic = mnemonicTexts.getOrNull(i) ?: continue
            if (mnemonic.visibility == View.VISIBLE) {
                mnemonic.text = ""
                mnemonic.visibility = View.GONE
            }
        }
    }

    /** 展示某一题的正确拼写；词书里有「巧记」时一并展示。 */
    private fun revealAnswer(index: Int) {
        val text = answerTexts.getOrNull(index) ?: return
        val entry = words.getOrNull(index) ?: return
        text.text = getString(R.string.answer_prefix) + entry.word
        text.visibility = View.VISIBLE

        val mnemonicView = mnemonicTexts.getOrNull(index) ?: return
        if (entry.mnemonic.isBlank()) {
            mnemonicView.visibility = View.GONE
        } else {
            mnemonicView.text = getString(R.string.mnemonic_prefix) + entry.mnemonic
            mnemonicView.visibility = View.VISIBLE
        }
    }

    /**
     * 词表异常：不提供任何放行路径。
     * 「回到桌面」只是回 HOME，不会解除限制；下次打开目标 App 仍会重新拦截。
     */
    private fun showWordError(message: String) {
        quizWords = emptyList()
        words = emptyList()
        answerInputs.clear()
        answerTexts.clear()
        mnemonicTexts.clear()
        container.removeAllViews()
        choiceContainer.removeAllViews()
        choiceContainer.visibility = View.GONE
        choiceTitle.visibility = View.GONE
        statusText.visibility = View.GONE
        progressText.text = ""
        verifyButton.visibility = View.GONE
        verifyButton.isEnabled = false
        errorText.visibility = View.VISIBLE
        errorText.text = "词表异常，已阻止进入目标应用。\n\n" + message
        retryButton.visibility = View.VISIBLE
        homeButton.visibility = View.VISIBLE
    }

    // ---------- 校验与放行 ----------

    private fun verifyAnswers() {
        if (words.isEmpty()) {
            showWordError("本次没有需要拼写的单词，视为词表异常")
            return
        }
        if (answerInputs.size != words.size) {
            showWordError("验证页状态异常，请重新加载词表")
            return
        }

        val wrongIndices = mutableListOf<Int>()
        words.forEachIndexed { index, entry ->
            val input = answerInputs[index]
            if (!input.isEnabled) return@forEachIndexed
            val typed = (input.text?.toString() ?: "").trim().lowercase(Locale.ROOT)
            if (typed.isNotEmpty() && typed == entry.word.lowercase(Locale.ROOT)) {
                input.isEnabled = false
                input.setTextColor(ContextCompat.getColor(this, R.color.ok_green))
                input.setBackgroundResource(R.drawable.bg_input_correct)
            } else {
                wrongIndices.add(index)
                input.setText("")
            }
        }

        updateProgress()

        if (wrongIndices.isEmpty()) {
            unlockAndFinish()
            return
        }

        // 答错后按设置决定是否给出正确拼写（然后由用户再次拼写）
        if (AppPrefs.answerRevealMode(this) != AppPrefs.ANSWER_MODE_NEVER) {
            wrongIndices.forEach { revealAnswer(it) }
        }
        errorText.text = getString(R.string.spell_error)
        errorText.visibility = View.VISIBLE
        answerInputs.firstOrNull { it.isEnabled && it.text.isNullOrBlank() }?.requestFocus()
    }

    private fun updateProgress() {
        if (words.isEmpty()) {
            progressText.text = ""
            return
        }
        val done = answerInputs.count { !it.isEnabled }
        progressText.text = "已完成 " + done + " / " + words.size
    }

    // ---------- 急事直通 ----------

    private fun askAdminPassword() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.admin_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.admin_ok) { _, _ ->
                if (AppPrefs.isAdminPassword(this, input.text?.toString())) {
                    UnlockGate.grant(this)
                    OverlayLock.hide()
                    Toast.makeText(this, R.string.admin_pass, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                } else {
                    Toast.makeText(this, R.string.admin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun unlockAndFinish() {
        UnlockGate.grant(this)
        OverlayLock.hide()
        val minutes = UnlockGate.intervalMs(this) / 60_000L
        Toast.makeText(
            this,
            "验证通过：" + minutes + " 分钟内不再拦截（到点重新验证）",
            Toast.LENGTH_SHORT
        ).show()
        // 只关闭验证页，不重新启动目标 App：下层目标 App 原样显现
        finishAndRemoveTask()
    }
}
