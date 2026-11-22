package ly.anoma.hex.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import ly.anoma.hex.voiceColor
import ly.anoma.hex.R
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.speech.VoiceWaveView
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.useInter
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The strip above the keys, with the Hex mark at its trailing end where Gboard has its mic.
 * Idle it shows three suggestions (the likeliest, or the autocorrection in a small chip, in the
 * middle) or a hint and the Hex menu button. Dictating, the mark becomes ✓ and the strip becomes
 * the recording bar: the waveform's well grows out of it, with ✕ beside ✓. */
internal class StripView(context: Context, private val ime: HexKeyboard, private val host: KeyboardView) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.voiceColor(id)
    private val ink = color(R.color.ink)
    private val quiet = color(R.color.quiet)
    private val key = color(R.color.key)
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var floating = false
    val stripHeight get() = if (floating) dp(38) else px(if (landscape) KeyboardMetrics.STRIP_LANDSCAPE else KeyboardMetrics.STRIP)
    private val centre = px(if (landscape) KeyboardMetrics.STRIP_CENTER_LANDSCAPE else KeyboardMetrics.STRIP_CENTER)
    private val side = px(KeyboardMetrics.SIDE)
    private val hexWidth = px(KeyboardMetrics.HEX_KEY)
    private val pill = px(KeyboardMetrics.PILL)
    private val cancelWidth = dp(40)
    private val incognitoWidth = dp(28)
    private val wellStart = side + px(KeyboardMetrics.GAP / 2)

    private val idleRow = LinearLayout(context)
    private val incognitoIcon = icon(R.drawable.ic_incognito).apply {
        contentDescription = context.getString(R.string.keyboard_incognito)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
    }
    private val statusText = TextView(context)
    private val slotRow = LinearLayout(context)
    private val slotViews = List(3) { FrameLayout(context) }
    private val words = List(3) { TextView(context) }
    private val dividers = List(2) { View(context) }
    private var offered = arrayOfNulls<String>(3)
    private val menuKey = TextView(context)
    private val floatingMenu = FrameLayout(context)
    private val floatingMenuIcon = ImageView(context)
    // Undo dictation, in the slots' place after a dictation (iPhone Hex's chip).
    private val undoChip = TextView(context)
    private var undoing = false

    private val well = FrameLayout(context)
    private val scope = VoiceWaveView(context)
    private val cancelKey = FrameLayout(context)
    private val hexKey = FrameLayout(context)
    private val hexFace = View(context)
    private val mark = ImageView(context)
    private val check = ImageView(context)
    private val spinner = ProgressBar(context, null, 0, android.R.style.Widget_Material_ProgressBar_Small)

    private var idle = true
    private var reveal: ValueAnimator? = null
    var hint = context.getString(R.string.keyboard_hint_hold)
        private set
    /** The Hex menu replaces the keys. */
    var choosing = false
    val recording get() = !idle

    // The Hex key: a tap dictates (✓ then finishes), a still hold of 350 ms is push to talk, and
    // a slide of 10 dp opens the menu, as on iPhone. It stays mounted while it turns into ✓, so
    // a hold's own finger-up finishes the dictation it started.
    private var held = false
    private var tracking = false
    private var originX = 0f
    private var originY = 0f
    private val slide = 10 * density
    private val hold = Runnable { if (tracking) held = ime.beginSpeech() }

    init {
        // Everything centres on the line Gboard's toolbar icons sit on, a little above the middle.
        setPadding(0, 0, 0, (stripHeight - 2 * centre).coerceAtLeast(0))
        clipChildren = false

        statusText.apply {
            useInter(500); setTextColor(quiet); textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(4), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (choosing) host.backInMenu() }
        }
        floatingMenu.apply {
            visibility = GONE
            isFocusable = true
            setOnClickListener { if (choosing) host.closeMenu() else host.openMenu() }
            addView(floatingMenuIcon.apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        idleRow.addView(floatingMenu, LinearLayout.LayoutParams(dp(38), -1))
        idleRow.addView(statusText, LinearLayout.LayoutParams(0, -1, 1f))
        for (i in 0..2) {
            if (i > 0) slotRow.addView(dividers[i - 1].apply { setBackgroundColor(color(R.color.rule)) },
                LinearLayout.LayoutParams(maxOf(1, dp(1) / 2 + 1), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL })
            words[i].apply {
                useInter(450); gravity = Gravity.CENTER; setTextColor(ink); textSize = 16f
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setPadding(dp(10), 0, dp(10), 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            slotViews[i].apply {
                isFocusable = true
                setOnClickListener { offered[i]?.let(ime.engine::pickSuggestion) }
                addView(words[i], LayoutParams(LayoutParams.WRAP_CONTENT, dp(30), Gravity.CENTER))
            }
            slotRow.addView(slotViews[i], LinearLayout.LayoutParams(0, -1, 1f))
        }
        idleRow.addView(slotRow, LinearLayout.LayoutParams(0, -1, 1f))
        val undoBox = FrameLayout(context).apply {
            undoChip.apply {
                text = context.getString(R.string.keyboard_undo_dictation); useInter(500); textSize = 14f; setTextColor(ink)
                gravity = Gravity.CENTER; includeFontPadding = false; isFocusable = true
                background = shape(key, dp(15).toFloat()); setPadding(dp(12), 0, dp(14), 0)
                compoundDrawablePadding = dp(6)
                setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(R.drawable.ic_undo)?.mutate()?.apply {
                    setTint(ink); setBounds(0, 0, dp(16), dp(16))
                }, null, null, null)
                setOnClickListener { ime.engine.undoDictation() }
            }
            addView(undoChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30), Gravity.CENTER))
            visibility = GONE
        }
        idleRow.addView(undoBox, LinearLayout.LayoutParams(0, -1, 1f))
        menuKey.apply {
            useInter(550); gravity = Gravity.CENTER; textSize = 14f; setTextColor(ink)
            setOnClickListener { if (choosing) host.closeMenu() else host.openMenu() }
            setPadding(dp(8), 0, dp(8), 0); isFocusable = true; minimumWidth = dp(40)
        }
        idleRow.addView(menuKey, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, -1))
        addView(idleRow, LayoutParams(-1, -1).apply { marginStart = side; marginEnd = side + hexWidth })

        well.background = shape(color(R.color.sheet), pill / 2f)
        well.clipToOutline = true
        well.addView(scope, LayoutParams(-1, -1).apply { marginStart = dp(10); marginEnd = dp(10) })
        // TalkBack says "Recording" as the bar appears and "Transcribing" when it changes (a live region:
        // a pane title would stay behind as the keyboard window's name).
        scope.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        scope.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(well, LayoutParams(-1, pill, Gravity.CENTER_VERTICAL).apply {
            marginStart = wellStart; marginEnd = side + hexWidth + cancelWidth - (cancelWidth - pill) / 2 + dp(6)
        })
        addView(incognitoIcon, LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = side + dp(3)
        })

        cancelKey.apply {
            contentDescription = context.getString(R.string.keyboard_cancel_dictation); isFocusable = true
            setOnClickListener { ime.cancelSpeech() }
            addView(View(context).apply { isDuplicateParentStateEnabled = true; background = pressable(color(R.color.modifier)) },
                LayoutParams(pill, pill, Gravity.CENTER))
            addView(icon(R.drawable.ic_cancel), LayoutParams(dp(19), dp(19), Gravity.CENTER))
        }
        addView(cancelKey, LayoutParams(cancelWidth, -1, Gravity.END).apply { marginEnd = side + hexWidth })

        hexKey.apply {
            isFocusable = true
            hexFace.isDuplicateParentStateEnabled = true
            addView(hexFace, LayoutParams(pill, pill, Gravity.CENTER))
            mark.setImageResource(R.drawable.ic_microphone); mark.imageTintList = ColorStateList.valueOf(ink)
            addView(mark, LayoutParams(px(KeyboardMetrics.MARK), px(KeyboardMetrics.MARK), Gravity.CENTER))
            check.setImageResource(R.drawable.ic_check); check.imageTintList = ColorStateList.valueOf(ink)
            addView(check, LayoutParams(dp(19), dp(19), Gravity.CENTER))
            spinner.indeterminateTintList = ColorStateList.valueOf(ink)
            spinner.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(spinner, LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { ime.toggleSpeech() }
            setOnTouchListener { v, e -> hexTouch(v, e) }
        }
        addView(hexKey, LayoutParams(hexWidth, -1, Gravity.END).apply { marginEnd = side })
        showIdle(true)
    }

    fun setIncognito(enabled: Boolean) {
        if ((incognitoIcon.visibility == VISIBLE) == enabled) return
        incognitoIcon.visibility = if (enabled) VISIBLE else GONE
        val offset = if (enabled) incognitoWidth else 0
        idleRow.layoutParams = (idleRow.layoutParams as LayoutParams).apply { marginStart = side + offset }
        well.layoutParams = (well.layoutParams as LayoutParams).apply { marginStart = wellStart + offset }
    }

    fun setFloating(enabled: Boolean) {
        if (floating == enabled) return
        floating = enabled
        setPadding(0, 0, 0, if (enabled) 0 else (stripHeight - 2 * centre).coerceAtLeast(0))
        for (word in words) {
            word.textSize = if (enabled) 13.5f else 16f
            val padding = dp(if (enabled) 5 else 10)
            word.setPadding(padding, 0, padding, 0)
        }
        for (divider in dividers) {
            divider.layoutParams = divider.layoutParams.apply { height = dp(if (enabled) 13 else 20) }
        }
        statusText.textSize = if (enabled) 13f else 14f
        styleMic(idle)
        update()
    }

    private fun hexTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false; tracking = true; originX = e.x; originY = e.y; v.isPressed = true
                postDelayed(hold, 350)
            }
            MotionEvent.ACTION_MOVE -> if (!held && tracking && hypot(originX - e.x, originY - e.y) > slide) {
                removeCallbacks(hold); host.openMenu()
                tracking = false; v.isPressed = false
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold); v.isPressed = false
                if (held) ime.finishSpeech() else if (tracking) v.performClick()
                held = false; tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold); if (held) ime.cancelSpeech()
                held = false; tracking = false; v.isPressed = false
            }
        }
        return true
    }

    /** The typing model's slots in its order. With what was typed among them they show as given: typed
     * in quotes on the left, then the fix (in a chip) or the likeliest word in the middle. Without it,
     * the likeliest word takes the middle, the next the left, as Gboard and iPhone place them. */
    fun suggestions(slots: List<Suggestion>) {
        val shown = arrayOfNulls<Suggestion>(3)
        if (slots.any { it.kind == Suggestion.Kind.Typed }) slots.take(3).forEachIndexed { i, slot -> shown[i] = slot }
        else { shown[1] = slots.getOrNull(0); shown[0] = slots.getOrNull(1); shown[2] = slots.getOrNull(2) }
        offered = Array(3) { shown[it]?.text }
        var chip = -1
        for (i in 0..2) {
            val slot = shown[i]
            val s = slot?.text.orEmpty()
            val typed = slot?.kind == Suggestion.Kind.Typed
            val fix = slot?.kind == Suggestion.Kind.Fix
            if (fix) chip = i
            this.words[i].text = if (typed) context.getString(R.string.keyboard_literal, s) else s
            this.words[i].background = if (fix) shape(key, dp(15).toFloat()) else null
            slotViews[i].contentDescription = when {
                s.isEmpty() -> null
                typed -> context.getString(R.string.keyboard_keep, s)
                fix -> context.getString(R.string.keyboard_correction, s)
                else -> context.getString(R.string.keyboard_suggest, s)
            }
            slotViews[i].isEnabled = s.isNotEmpty()
            slotViews[i].visibility = if (s.isEmpty()) GONE else VISIBLE
        }
        for (i in dividers.indices) {
            dividers[i].visibility = when {
                offered[i] == null || offered[i + 1] == null -> GONE
                chip == i || chip == i + 1 -> INVISIBLE
                else -> VISIBLE
            }
        }
        update()
    }
    /** Undo dictation shows in the slots' place, or goes. */
    fun undo(shown: Boolean) { undoing = shown; update() }
    fun update() {
        val undo = idle && !choosing && undoing
        val show = idle && !choosing && !undo && offered.any { it != null }
        (undoChip.parent as View).visibility = if (undo) VISIBLE else GONE
        slotRow.visibility = if (show) VISIBLE else GONE
        val idleHint = floating && !choosing && hint == context.getString(R.string.keyboard_hint_hold)
        // Keep the weighted space reserved so the menu stays against the left edge.
        statusText.visibility = if (idle && !show && !undo) {
            if (idleHint) INVISIBLE else VISIBLE
        } else GONE
        menuKey.visibility = if (floating) GONE else VISIBLE
        floatingMenu.visibility = if (floating) VISIBLE else GONE
        if (floating) {
            floatingMenuIcon.setImageResource(if (choosing) R.drawable.ic_cancel else R.drawable.voice_mark)
            floatingMenuIcon.imageTintList = ColorStateList.valueOf(color(if (choosing) R.color.quiet else R.color.floating_brand))
            floatingMenu.contentDescription = context.getString(if (choosing) R.string.keyboard_menu_done else R.string.keyboard_menu)
        }
        menuKey.text = context.getString(when { choosing -> R.string.keyboard_menu_done; show -> R.string.keyboard_menu_more; else -> R.string.keyboard_menu_open })
        menuKey.contentDescription = context.getString(if (choosing) R.string.keyboard_menu_done else R.string.keyboard_menu)
    }
    /** The menu opened: the hint line shows its page's title and goes back a page when tapped. */
    fun menuOpened() { statusText.isClickable = true }
    fun menuClosed() { statusText.isClickable = false; menuKey.text = context.getString(R.string.keyboard_menu_open); statusText.text = hint }
    fun title(text: String) { statusText.text = text }

    /** Shows [phase]'s strip. The caller has already closed the menu if dictation is running. */
    fun status(phase: SpeechPhase, hint: String, menuVisible: Boolean) {
        this.hint = hint
        val wasIdle = idleRow.visibility == VISIBLE
        idle = phase == SpeechPhase.Idle
        val processing = phase == SpeechPhase.Transcribing
        if (!menuVisible) { statusText.isClickable = false; menuKey.text = context.getString(R.string.keyboard_menu_open); statusText.text = hint }
        if (idle != wasIdle) showIdle(idle)
        spinner.visibility = if (processing) VISIBLE else GONE
        check.visibility = if (!idle && !processing) VISIBLE else GONE
        hexKey.contentDescription = context.getString(when {
            idle -> R.string.keyboard_hex
            processing -> R.string.keyboard_transcribing
            else -> R.string.keyboard_finish_dictation
        })
        // Set just after the bar shows, so the live region announces it.
        val state = context.getString(if (processing) R.string.keyboard_transcribing else R.string.keyboard_recording)
        if (idle) scope.contentDescription = null else scope.postDelayed({ if (!this.idle) scope.contentDescription = state }, 200)
        scope.showPhase(phase)
    }
    /** Before [status]: this phase's hint and whether it is idle, so the caller can close the menu first. */
    fun prepare(phase: SpeechPhase, hint: String) { this.hint = hint; idle = phase == SpeechPhase.Idle }
    fun setLevel(value: Float) = scope.push(value)

    /** Idle: suggestions and the Hex mark. Dictating: the mark is ✓ and the well grows leftwards out of it. */
    private fun showIdle(idle: Boolean) {
        reveal?.cancel(); reveal = null
        idleRow.visibility = if (idle) VISIBLE else GONE
        well.visibility = if (idle) GONE else VISIBLE
        cancelKey.visibility = if (idle) GONE else VISIBLE
        mark.visibility = if (idle) VISIBLE else GONE
        styleMic(idle)
        well.clipBounds = null
        if (idle || !ValueAnimator.areAnimatorsEnabled()) return
        cancelKey.alpha = 0f
        reveal = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val w = well.width
                if (w > 0) well.clipBounds = Rect((w * (1 - f)).roundToInt(), 0, w, well.height)
                cancelKey.alpha = f
            }
            start()
        }
    }

    private fun styleMic(idle: Boolean) {
        val fill = when {
            floating -> color(R.color.floating_accent)
            idle -> android.graphics.Color.TRANSPARENT
            else -> key
        }
        val foreground = ColorStateList.valueOf(if (floating) color(R.color.floating_on_accent) else ink)
        val pressed = if (floating) ColorUtils.blendARGB(fill, foreground.defaultColor, .18f) else color(R.color.pressed)
        hexFace.background = pressable(fill, pressed)
        mark.imageTintList = foreground
        check.imageTintList = foreground
        spinner.indeterminateTintList = foreground
    }

    fun cancelGestures() {
        removeCallbacks(hold)
        held = false; tracking = false
        hexKey.isPressed = false
    }

    private fun icon(res: Int) = ImageView(context).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(ink) }
    private fun shape(fill: Int, radius: Float) = GradientDrawable().apply { setColor(fill); cornerRadius = radius }
    private fun pressable(fill: Int, pressed: Int = color(R.color.pressed)) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pressed) })
        addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) })
    }
}
