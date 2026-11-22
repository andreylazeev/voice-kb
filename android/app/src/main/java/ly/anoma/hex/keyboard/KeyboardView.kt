package ly.anoma.hex.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import ly.anoma.hex.R
import ly.anoma.hex.speech.LiveSpeech
import ly.anoma.hex.speech.LiveTranscriptView
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.voiceColor

/** The keyboard's root view: the strip, then the keys or the Hex menu in their place. */
internal class KeyboardView(context: Context, private val ime: HexKeyboard) : LinearLayout(context), KeyboardSurface {
    private val feedback = LetterFeedback(this)
    private val strip = StripView(context, ime, this)
    private val keys = KeyPlaneView(context, ime, this, feedback)
    private val menu: SpeechMenu
    private var phase = SpeechPhase.Idle
    private var mode = KeyboardMode.Standard
    private val draft = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
    private val draftText = TextView(context).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val liveText = LiveTranscriptView(context).apply { visibility = GONE }
    private val dp get() = resources.displayMetrics.density
    private var systemBars = Insets.NONE
    private var navigationInset = 0
    /** Where the key held for More characters was, so TalkBack's focus can go back to it. */
    private var heldAt: IntArray? = null

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        // On edge-to-edge IME windows the system draws its back and switcher buttons and the
        // gesture handle over the bottom of our window: 48 dp, which Android 14 and later report
        // as the caption bar inside systemBars (navigationBars alone is the 24 dp handle). Keys stay
        // above it. Where the window already ends above the navigation bar (Android 12) it is 0.
        setOnApplyWindowInsetsListener { _, insets ->
            systemBars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val navBars = WindowInsets.Type.navigationBars()
            navigationInset = maxOf(insets.getInsetsIgnoringVisibility(navBars).bottom,
                context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(navBars).bottom)
            applyKeyboardPadding()
            insets
        }
        applyKeyboardPadding()
        setBackgroundColor(context.voiceColor(R.color.keyboard))
        draft.addView(draftText.apply {
            setPadding((dp * 10).toInt(), 0, 0, 0)
            setTextColor(context.voiceColor(R.color.ink))
        }, LayoutParams(0, -2, 1f))
        for ((label, click) in listOf(R.string.draft_insert to ime::insertDraft, R.string.draft_discard to ime::discardDraft)) {
            draft.addView(TextView(context).apply {
                text = context.getString(label)
                gravity = Gravity.CENTER
                setPadding((dp * 8).toInt(), 0, (dp * 8).toInt(), 0)
                setTextColor(context.voiceColor(R.color.ink))
                setOnClickListener { click() }
            }, LayoutParams(-2, -1))
        }
        addView(draft, LayoutParams(-1, (dp * 52).toInt()))
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, strip.stripHeight))
        addView(liveText, LayoutParams(-1, (dp * 68).toInt()).apply {
            val side = (dp * 7).toInt()
            setMargins(side, 0, side, (dp * 4).toInt())
        })
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        menu = SpeechMenu(context, ime, strip::title, ::closeMenu).apply { visibility = GONE }
        addView(menu, LayoutParams(-1, keys.menuHeight))
        status(SpeechPhase.Idle, context.getString(R.string.keyboard_hint_hold))
    }

    fun applyMode(value: KeyboardMode) {
        if (mode == value) return
        mode = value
        val floating = value == KeyboardMode.Floating
        setBackgroundColor(if (floating) Color.TRANSPARENT else context.voiceColor(R.color.keyboard))
        keys.setFloating(floating)
        keys.split(mode == KeyboardMode.Split)
        strip.setFloating(floating)
        strip.layoutParams = strip.layoutParams.apply { height = strip.stripHeight }
        menu.layoutParams = menu.layoutParams.apply { height = keys.menuHeight }
        applyKeyboardPadding()
        requestApplyInsets()
    }

    private fun applyKeyboardPadding() {
        val floating = mode == KeyboardMode.Floating
        val fullWidth = mode == KeyboardMode.Standard || mode == KeyboardMode.Split
        val edge = if (floating) (6f * dp).toInt() else 0
        // One UI can send zero insets while switching displays. Apply its caption reserve before
        // the first docked measure and when leaving floating mode, even without a new insets dispatch.
        val reserve = if (Build.VERSION.SDK_INT >= 35 &&
            (navigationInset > 0 || Build.MANUFACTURER.equals("samsung", ignoreCase = true))) (48f * dp).toInt() else 0
        setPadding(if (fullWidth) systemBars.left else edge, 0, if (fullWidth) systemBars.right else edge,
            if (floating) 0 else maxOf(systemBars.bottom, reserve))
    }
    fun minimumFloatingWidth() = keys.minimumFloatingWidth

    fun setFloatingRowHeight(height: Float) {
        if (keys.setFloatingRowHeight(height)) {
            menu.layoutParams = menu.layoutParams.apply { this.height = keys.menuHeight }
        }
    }
    fun showDraft(text: String?) {
        draftText.text = text.orEmpty()
        draft.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    fun showLiveSpeech(speech: LiveSpeech?) {
        liveText.visibility = if (speech?.text.isNullOrEmpty()) GONE else VISIBLE
        liveText.show(speech)
    }

    override fun showPage(page: KeyboardPage) {
        keys.showPage(page)
        // What TalkBack names the keyboard as it appears, as Gboard's "English (US) (QWERTY)".
        val title = context.getString(R.string.keyboard_pane, page.language.label)
        if (accessibilityPaneTitle != title) accessibilityPaneTitle = title
    }
    override fun suggestions(slots: List<Suggestion>) = strip.suggestions(slots)
    override fun undoDictation(shown: Boolean) = strip.undo(shown)

    /** Emoji in place of the strip and the keys, the window's height unchanged (Gboard's panel
     * replaces its toolbar too); ABC brings both back. */
    fun openEmoji() {
        if (strip.recording) return
        closeMenu(); ime.engine.clearForDictation()
        strip.visibility = GONE; keys.openEmoji(strip.stripHeight)
    }
    fun closeEmoji() {
        if (!keys.emojiOpen) return
        keys.closeEmoji(); strip.visibility = VISIBLE; strip.update()
    }
    fun openMenu() {
        if (strip.recording) return
        closeEmoji()
        keys.visibility = GONE; menu.visibility = VISIBLE
        strip.choosing = true; strip.menuOpened(); menu.home(); strip.update()
    }
    fun openLanguageMenu() { openMenu(); menu.language() }
    fun openAccents(accents: List<String>, from: View) {
        openMenu(); if (menu.visibility != VISIBLE) return
        heldAt = IntArray(2).also(from::getLocationInWindow).let { intArrayOf(it[0] + from.width / 2, it[1] + from.height / 2) }
        menu.accents(accents) { accent -> closeMenu(); ime.engine.type(accent) }
    }
    fun backInMenu() { if (menu.visibility == VISIBLE) menu.back() }
    /** [configure] false: the caller sets typing up next (a new field), so the keys are drawn once. */
    fun closeMenu(configure: Boolean = true) {
        if (menu.visibility != VISIBLE) return
        menu.visibility = GONE; keys.visibility = VISIBLE
        strip.choosing = false; strip.menuClosed(); keys.refreshSpace()
        if (configure) ime.engine.configureTyping(keepWord = true)
        strip.update()
        // TalkBack's focus goes back to the key that was held, as on Gboard, not to the first suggestion.
        // By place: typing on a symbol page draws its keys again.
        val at = heldAt ?: return
        heldAt = null
        keys.doOnPreDraw {
            fun find(v: View): View? = if (v is KeyCap && v.isShown && Rect().also { v.getGlobalVisibleRect(it) }.contains(at[0], at[1])) v
                else (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
            find(keys)?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }
    fun status(phase: SpeechPhase, hint: String) {
        cue(this.phase, phase); this.phase = phase
        val idle = phase == SpeechPhase.Idle
        strip.prepare(phase, hint)
        if (!idle) { heldAt = null; closeMenu() }
        strip.status(phase, hint, menu.visibility == VISIBLE)
        liveText.showPhase(phase)
        // Keep the snapshotted paste language visible until this dictation has finished.
        if (idle) keys.refreshSpace()
        strip.update()
    }
    /** iPhone Hex's light tap as dictation starts; as the microphone closes (done, cancelled or failed), a
     * lighter tick that iPhone Hex doesn't have, so someone using TalkBack, which is quiet while Hex records,
     * feels both ends. Android's own pair for a gesture's start and end (a click and a tick). */
    private fun cue(from: SpeechPhase, to: SpeechPhase) {
        fun recording(p: SpeechPhase) = p == SpeechPhase.Opening || p == SpeechPhase.Listening
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val effect = when {
            !recording(from) && recording(to) -> if (modern) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONFIRM
            recording(from) && !recording(to) -> if (modern) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
            else -> return
        }
        if (vibrates(context, ime.feedback.haptics)) performHapticFeedback(effect)
    }
    fun setLevel(value: Float) {
        strip.setLevel(value)
        liveText.pushLevel(value)
    }
    fun cancelGestures() { strip.cancelGestures(); keys.cancelGestures() }
    /** The editor went away: the next one starts on the letters. */
    fun reset() { heldAt = null; closeMenu(); closeEmoji() }
}
