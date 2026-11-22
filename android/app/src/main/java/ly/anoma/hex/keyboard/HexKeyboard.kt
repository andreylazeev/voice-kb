package ly.anoma.hex.keyboard

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.view.Window
import android.view.ContextThemeWrapper
import android.view.WindowInsetsController
import android.view.WindowManager
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import java.util.concurrent.Executors
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import ly.anoma.hex.Appearance
import ly.anoma.hex.voiceColor
import ly.anoma.hex.R
import ly.anoma.hex.speech.LocalSpeech
import ly.anoma.hex.speech.MicCapture
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.speech.SpeechOptions
import ly.anoma.hex.speech.SpeechPreferences
import ly.anoma.hex.speech.Style
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.GlideDecoder
import ly.anoma.hex.typing.FutoLayout
import ly.anoma.hex.typing.FutoSwipeDecoder
import ly.anoma.hex.typing.FutoSwipeModel
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.LearningMode
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.PersonalWords
import org.json.JSONObject
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord

/** The input method: the editor's lifecycle, the views and dictation. Typing lives in [KeyboardEngine]. */
/** Dictation's notes arrive from MicCapture as English sentences: the ones a person sees, in the phone's
 * language (one not listed here shows as it is). */
internal fun Context.speechNote(note: String?) = when (note) {
    "Allow the microphone in Hex" -> getString(R.string.keyboard_hint_microphone)
    "Microphone unavailable. Try again." -> getString(R.string.keyboard_note_microphone)
    "Couldn’t transcribe. Try again." -> getString(R.string.keyboard_note_transcribe)
    "No speech heard" -> getString(R.string.keyboard_note_no_speech)
    "Speech model unavailable" -> getString(R.string.keyboard_note_model)
    else -> note
}

class HexKeyboard : InputMethodService() {
    private var view: KeyboardView? = null
    private var foldSurface: FoldKeyboardSurface? = null
    private val panels by lazy { PanelPreferences(this) }
    internal val appearance by lazy { Appearance(this) }
    private var stopObservingTheme: (() -> Unit)? = null
    private lateinit var profile: ScreenProfile
    internal val panelSettings get() = panels.read(profile)
    internal fun chooseMode(mode: KeyboardMode) = updatePanel(panelSettings.copy(mode = mode))
    internal fun updatePanel(value: PanelSettings) {
        panels.write(profile, value)
        foldSurface?.apply(value)
        configurePanelWindow()
    }
    internal fun toggleLayout() {
        feedback.layout = if (layoutLanguage == Spoken.Russian) Spoken.English else Spoken.Russian
        engine.configureTyping()
        view?.status(capture.phase, idleHint())
        val language = layoutLanguage.layoutCode
        warmSwipe(language)
    }
    private data class Draft(val text: String, val language: String, val literal: Boolean)
    private var draft: Draft? = null
    private fun updateDraft() = view?.showDraft(draft?.text?.takeIf { !engine.secure })
    internal fun insertDraft() {
        val available = draft ?: return
        if (currentInputConnection == null || engine.secure) return
        if (engine.insertDictation(available.text, available.language, available.literal)) discardDraft()
    }
    internal fun discardDraft() { draft = null; updateDraft() }
    private lateinit var capture: MicCapture
    internal val preferences by lazy { SpeechPreferences(this) }
    internal var nextEnglish: Boolean? = null
    /** The options the dictation in flight started with: its language decides mid-sentence case. */
    internal val feedback by lazy { TypingPreferences(this) }
    internal val layoutLanguage get() = feedback.layout ?: preferences.spoken.layout ?: Spoken.English
    // Android's personal dictionary is read off the main thread, and again whenever it changes.
    private val words = Executors.newSingleThreadExecutor { Thread(it, "Hex personal words") }
    private val glideWorker = Executors.newSingleThreadExecutor { Thread(it, "Swipe typing") }
    private class SwipeLanguage(val words: Map<String, Int>, val pairs: Map<String, Map<String, Int>>) {
        val geometric by lazy { GlideDecoder(words, pairs) }
        val neural = HashMap<String, FutoSwipeDecoder>()
    }
    private val neuralLanguages = setOf("en", "ru")
    private val swipeLanguages = HashMap<String, SwipeLanguage>()
    private val futoModel = lazy { runCatching { FutoSwipeModel(applicationContext) }.getOrNull() }
    private val futo by futoModel

    private fun warmSwipe(language: String) {
        if (language !in GlideDecoder.languages) return
        glideWorker.execute {
            runCatching {
                val options = swipeLanguage(language)
                if (language in neuralLanguages && futo != null) {
                    val letters = KeyboardLetters.rows(language).flatMap(String::toList).filter(Char::isLetter)
                        .distinct().sorted().joinToString("")
                    options.neural.getOrPut(letters) { FutoSwipeDecoder(letters, options.words, options.pairs) }
                }
            }
        }
    }
    internal fun glide(path: List<KeyPoint>, grid: KeyGrid, language: String) {
        val revision = engine.swipeRevision()
        val anchor = engine.swipeAnchor()
        val previous = if (anchor.lastOrNull()?.isWhitespace() == true) TypingWord.currentToken(anchor.trimEnd()) else null
        val learned = feedback.learned(language, LearningMode.Swipe)
        val typed = if (feedback.learnSwipe) feedback.learned(language, LearningMode.Tap) else LearnedWords.Empty
        val context = feedback.learnContext
        glideWorker.execute {
            val candidates = runCatching {
                val options = swipeLanguage(language)
                val neural = if (language in neuralLanguages) futo else null
                val neuralWords = if (neural == null) emptyList() else runCatching {
                    val layout = FutoLayout(grid)
                    val decoder = options.neural.getOrPut(layout.letters) {
                        FutoSwipeDecoder(layout.letters, options.words, options.pairs)
                    }
                    decoder.suggestions(neural.emissions(path, layout), previous, learned, typed, context)
                }.getOrDefault(emptyList())
                neuralWords.ifEmpty { options.geometric.suggestions(path, grid, previous, learned, typed, context) }
            }.getOrDefault(emptyList())
            mainHandler.post {
                if (view?.isShown == true && layoutLanguage.layoutCode == language)
                    engine.insertSwipe(candidates, revision, anchor, language)
            }
        }
    }
    private fun swipeLanguage(language: String) = swipeLanguages.getOrPut(language) {
        val json = assets.open("glide/$language.json").bufferedReader().use { JSONObject(it.readText()) }
        val pairs = assets.open("glide/${language}_bigrams.txt").bufferedReader().use {
            GlideDecoder.readPairs(it.lineSequence())
        }
        SwipeLanguage(json.keys().asSequence().associateWith { json.getInt(it) }, pairs)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dictionaryChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = engine.personalWordsChanged()
    }
    internal val engine by lazy {
        KeyboardEngine(InputConnectionTarget(this), feedback, { layoutLanguage }, { SpatialDecoder(TypingModel.asset(assets, it)) },
            readPersonal = { PersonalWords.read(contentResolver, it) }, background = words)
    }
    private val typingChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TypingPreferences.SUGGEST_SWEAR_WORDS) engine.swearWordsChanged()
    }
    internal val previewAllowed get() = !engine.secure

    override fun onCreate() {
        setTheme(R.style.HexIme)
        super.onCreate()
        profile = ScreenProfile.from(resources.configuration)
        getSharedPreferences("typing", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(typingChanged)
        contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, dictionaryChanged)
        capture = MicCapture(LocalSpeech(applicationContext),
            update = { phase, note -> view?.status(phase, speechNote(note) ?: idleHint()) },
            meter = { view?.setLevel(it) },
            showPreview = { preview -> view?.showLiveSpeech(preview.takeUnless { engine.secure }) },
            insert = ::commitSpeech)
        stopObservingTheme = appearance.observe {
            mainHandler.post {
                if (view != null) {
                    view?.cancelGestures()
                    setInputView(onCreateInputView())
                    engine.configureTyping(keepWord = true)
                    view?.status(capture.phase, idleHint())
                    view?.showLiveSpeech(capture.preview.takeUnless { engine.secure })
                    updateDraft()
                }
            }
        }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View {
        val themed = ContextThemeWrapper(appearance.context(this), R.style.HexIme)
        val keyboard = KeyboardView(themed, this)
        keyboard.showLiveSpeech(capture.preview.takeUnless { engine.secure })
        view = keyboard
        engine.surface = keyboard
        val surface = FoldKeyboardSurface(themed, this, keyboard)
        foldSurface = surface
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (foldSurface === surface) updateScreenProfile(surface)
        }
        configurePanelWindow()
        return surface
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        val next = ScreenProfile.from(newConfig)
        if (profile != next) capture.detachFromEditor()
        if (foldSurface?.display == null) profile = next
        super.onConfigurationChanged(newConfig)
        foldSurface?.let(::updateScreenProfile)
        updateDraft()
    }
    private fun updateScreenProfile(surface: FoldKeyboardSurface) {
        val next = ScreenProfile.from(surface)
        if (profile == next) return
        capture.detachFromEditor()
        profile = next
        surface.apply(panelSettings)
        configurePanelWindow()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        foldSurface?.post { foldSurface?.let(::updateScreenProfile) }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        if (!isCandidatesOnly) configurePanelWindow(win)
    }
    private fun configurePanelWindow(win: Window? = window?.window) {
        val height = if (foldSurface?.floating == true) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT
        win?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height)
        val themed = foldSurface?.context ?: return
        val light = themed.resources.getBoolean(R.bool.light_bars)
        win?.navigationBarColor = themed.voiceColor(R.color.keyboard)
        val flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        win?.insetsController?.setSystemBarsAppearance(if (light) flag else 0, flag)
    }
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val surface = foldSurface?.takeIf { it.floating && it.isShown } ?: return
        val bounds = Rect()
        surface.touchBounds(bounds)
        val bottom = window?.window?.decorView?.height ?: surface.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(bounds)
    }

    /** The field's type as Hex last started in it: an app can change it in place (restarting the same box). */
    private var fieldType = 0 to 0
    private var typeChanged = false
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        typeChanged = restarting && fieldType != attribute.inputType to attribute.imeOptions
        fieldType = attribute.inputType to attribute.imeOptions
        nextEnglish = null
        capture.detachFromEditor()
        engine.startInput(attribute)
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        foldSurface?.let(::updateScreenProfile)
        view?.cancelGestures()
        // Another type in the same box: back to its keys, as a new field opens (configureTyping draws them).
        if (typeChanged) view?.closeMenu(configure = false)
        engine.configureTyping()
        val language = layoutLanguage.layoutCode
        warmSwipe(language)
        view?.status(capture.phase, idleHint())
        updateDraft()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        engine.updateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    internal fun nextIme() {
        capture.cancel()
        if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }
    private fun idleHint() = getString(when {
        engine.secure -> R.string.keyboard_hint_password
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> R.string.keyboard_hint_microphone
        else -> R.string.keyboard_hint_hold
    })
    internal fun beginSpeech(): Boolean {
        if (engine.secure || currentInputConnection == null || !capture.canStart ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            view?.status(capture.phase, idleHint())
            return false
        }
        engine.clearForDictation()
        capture.start(preferences.options(nextEnglish))
        nextEnglish = null
        return true
    }
    internal fun toggleSpeech() = when (capture.phase) {
        // A dictation may start while the one before still transcribes; texts go in in order.
        SpeechPhase.Idle, SpeechPhase.Transcribing -> { beginSpeech(); Unit }
        SpeechPhase.Opening, SpeechPhase.Listening -> finishSpeech()
    }
    internal fun finishSpeech() {
        if (capture.phase == SpeechPhase.Listening || capture.phase == SpeechPhase.Opening) capture.finish()
    }
    internal fun cancelSpeech() = capture.cancelLatest()
    private fun commitSpeech(text: String, options: SpeechOptions, detached: Boolean) {
        val language = options.spoken.code
        val literal = options.style == Style.Verbatim
        if (!detached && isInputViewShown && engine.insertDictation(text, language, literal)) return
        val previous = draft
        draft = Draft(if (previous == null) text else previous.text + " " + text, language, literal)
        updateDraft()
    }
    private fun leaveEditor() { view?.reset(); view?.cancelGestures(); engine.leave(); nextEnglish = null; capture.detachFromEditor() }
    override fun onFinishInputView(finishingInput: Boolean) { leaveEditor(); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { leaveEditor(); super.onFinishInput() }
    override fun onWindowHidden() { leaveEditor(); super.onWindowHidden() }
    override fun onDestroy() {
        stopObservingTheme?.invoke()
        getSharedPreferences("typing", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(typingChanged)
        contentResolver.unregisterContentObserver(dictionaryChanged); words.shutdownNow()
        glideWorker.execute { if (futoModel.isInitialized()) futo?.close() }
        glideWorker.shutdown()
        view?.cancelGestures(); view = null; engine.surface = null; capture.close(); super.onDestroy()
    }
}
