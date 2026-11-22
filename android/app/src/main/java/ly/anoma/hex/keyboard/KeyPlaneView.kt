package ly.anoma.hex.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.util.TypedValue
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.emoji2.emojipicker.EmojiPickerView
import ly.anoma.hex.voiceColor
import ly.anoma.hex.R
import ly.anoma.hex.typing.GlideDecoder
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import ly.anoma.hex.typing.TypingWord
import ly.anoma.hex.useInter
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The keys: three rows from the page's layout, then ?123, comma, language, space, period and enter. Every
 * point of the area types a key (KeyRowLayout); the bottom row stays mounted across page changes.
 * Keys that type as they lift do so in the order the fingers went down ([Rollover]). */
internal class KeyPlaneView(context: Context, private val ime: HexKeyboard, private val host: KeyboardView,
                            private val feedback: LetterFeedback) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun px(value: Float) = (value * density).roundToInt()
    val minimumFloatingWidth: Float by lazy {
        val label = TextView(context).apply { useInter(400) }
        label.paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            KeyboardMetrics.FLOATING_LABEL_MAX_SP.toFloat(), resources.displayMetrics)
        val widest = "ШЩЖМФЫЮW".maxOf { label.paint.measureText(it.toString()) / density }
        // Twelve equal letter cells, the shell and row insets, and two dp per cell for rounding.
        maxOf(255f, 16f + 12 * (widest + KeyboardMetrics.FLOATING_KEY_GAP + 2f))
    }
    private val caps = KeyCaps(context) { ime.feedback.haptics }
    private val ink = caps.ink
    private val modifier = caps.modifier
    private val quiet = context.voiceColor(R.color.quiet)
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var floating = false
    private var floatingRowHeight = 41f
    private val rowHeight get() = px(when { floating -> floatingRowHeight; landscape -> KeyboardMetrics.ROW_LANDSCAPE; else -> KeyboardMetrics.ROW })
    private val keyHeight get() = px(when { floating -> floatingRowHeight - 6f; landscape -> KeyboardMetrics.KEY_HEIGHT_LANDSCAPE; else -> KeyboardMetrics.KEY_HEIGHT })
    private val insetTop get() = (rowHeight - keyHeight) / 2
    private val insetBottom get() = rowHeight - keyHeight - insetTop
    private val bottomExtra get() = px(when { floating -> 0f; landscape -> KeyboardMetrics.BOTTOM_LANDSCAPE; else -> KeyboardMetrics.BOTTOM })
    private val reach get() = if (landscape || floating) 0 else px(KeyboardMetrics.ROW1_REACH)
    private val side = KeyboardMetrics.SIDE * density
    private val gap get() = (if (floating) KeyboardMetrics.FLOATING_KEY_GAP else KeyboardMetrics.GAP) * density
    val menuHeight get() = rowHeight * KeyboardMetrics.MENU_ROWS + bottomExtra + reach
    private val letters = LinkedHashMap<Char, KeyCap>()
    private var splitMode = false
    private var grid: KeyGrid? = null
    private var trail: MutableList<KeyPoint>? = null
    private var gliding = false
    private var trailFade: ValueAnimator? = null
    private var fadingTrail: List<KeyPoint>? = null
    private var trailOpacity = 1f
    private val tracePath = Path()
    private val traceSegment = Path()
    private val traceMeasure = PathMeasure()
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.voiceColor(R.color.floating_brand)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var pendingHold: Runnable? = null
    private val rows = LinearLayout(context)
    private val bottom = KeyRowLayout(context, 10f, side, gap, insetTop, insetBottom + bottomExtra)
    private val modeKey: KeyCap
    private val enterKey: KeyCap
    private val enterIcon = ImageView(context)
    private val spaceKey: KeyCap
    private var deleteKey: KeyCap? = null
    private val comma: KeyCap
    private val emojiHint = ImageView(context)
    private val period: KeyCap
    // The emoji panel: AndroidX's picker where the strip and the letter rows are, then ABC, space and delete.
    private var picker: EmojiPickerView? = null
    private val emojiBottom = KeyRowLayout(context, 10f, side, gap, insetTop, insetBottom + bottomExtra)
    var emojiOpen = false
        private set
    private var commaText = ","
    private val roll = Rollover()
    // A slide from ?123 or shift types the key it lifts on: the keys it can type, and the one under it.
    private val slides = HashMap<KeyCap, (KeyPoint) -> Unit>()
    private val bottomSlides = HashMap<KeyCap, (KeyPoint) -> Unit>()
    private var slidOnto: KeyCap? = null
    private val onScreen = IntArray(2)
    private val hostAt = IntArray(2)
    private var deleting = false
    private var repeated = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val spaceGesture = SpaceGesture(KeyboardMetrics.CURSOR_START * density, KeyboardMetrics.CURSOR_STEP * density, slop.toFloat())
    private var cursorSlide: CursorSlide? = null
    private var spacePoint: KeyPoint? = null
    // The page the keys show; a change of shift alone relabels them in place, so a held shift key
    // stays mounted and its own finger-up ends a chord.
    private var current: KeyboardPage? = null
    private val caseKeys = ArrayList<Pair<Char, KeyCap>>()
    private var shiftIcon: ImageView? = null
    private val switchLanguage = Runnable {
        if (spaceKey.isPressed && spaceGesture.still) { spaceKey.isPressed = false; spaceKey.performLongClick() }
    }
    private val deleteGesture = DeleteGesture(KeyboardMetrics.DELETE_START * density, KeyboardMetrics.DELETE_STEP * density,
        KeyboardMetrics.DELETE_CANCEL * density, slop.toFloat())
    private var deleteSlide: DeleteSlide? = null
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (deleting) { repeated = true; ime.engine.delete(); postDelayed(this, KeyboardMetrics.DELETE_REPEAT_EVERY) }
        }
    }

    init {
        orientation = VERTICAL
        rows.orientation = VERTICAL
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight + bottomExtra))
        modeKey = caps.cap(context.getString(R.string.keyboard_numbers_label), context.getString(R.string.keyboard_numbers),
            modifier, 17f, ime.engine::mode)
        modeKey.textEntry = false
        // ?123 switches pages as it goes down, as Gboard's; a slide onto a key types it and comes back.
        modeKey.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { roll.down(v); v.isPressed = true; endSlide(); ime.engine.mode() }
                MotionEvent.ACTION_MOVE -> slideOver(v, e)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    roll.up(v); v.isPressed = false
                    val onto = slidOnto; endSlide()
                    if (onto != null && e.actionMasked == MotionEvent.ACTION_UP && typeSlid(onto, e)) ime.engine.mode()
                }
            }
            true
        }
        comma = caps.cap(",", context.getString(R.string.keyboard_comma), modifier) { ime.engine.type(commaText) }
        comma.setOnLongClickListener {
            if (current?.symbols == true) ime.engine.pad(Pad.Symbols) else host.openEmoji()
            true
        }
        typesOnLift(comma, preview = { null }, holds = { emptyList() }, activateOnHold = true, type = { comma.performClick() })
        bottomSlides[comma] = { ime.engine.type(commaText) }
        emojiHint.setImageResource(R.drawable.ic_emoji)
        emojiHint.imageTintList = ColorStateList.valueOf(quiet)
        comma.face.addView(emojiHint, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(2)
            marginEnd = dp(4)
        })
        spaceKey = caps.cap("", context.getString(R.string.keyboard_space), caps.key, 14f) {
            ime.engine.space(spacePoint, if (current?.symbols == false) geometry() else null); spacePoint = null
        }
        spaceKey.sound = AudioManager.FX_KEYPRESS_SPACEBAR
        spaceKey.label?.setTextColor(quiet)
        spaceKey.setOnLongClickListener { host.openLanguageMenu(); true }
        // A press that leaves the key still types its space on lifting: a quick tap that drifts must
        // never lose it. Only a slide that moved the cursor, or the long press's menu, types none.
        spaceKey.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Another key going down while space is held types the space first, unless it moved the cursor.
                    roll.down(v) {
                        removeCallbacks(switchLanguage); val typing = v.isPressed && cursorSlide == null
                        v.isPressed = false; cursorSlide = null
                        if (typing) v.performClick()
                    }
                    v.isPressed = true; cursorSlide = null; spaceGesture.down(e.x, e.y, e.eventTime)
                    spacePoint = KeyPoint((v.left + e.x).toDouble(), (bottom.top - rows.top + e.y).toDouble())
                    postDelayed(switchLanguage, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (v.isPressed) {
                    val characters = spaceGesture.move(e.x, e.y, e.eventTime)
                    if (!spaceGesture.still) removeCallbacks(switchLanguage)
                    if (characters != null) (cursorSlide ?: ime.engine.cursorSlide().also { cursorSlide = it }).moveTo(characters)
                }
                MotionEvent.ACTION_UP -> if (roll.up(v)) {
                    removeCallbacks(switchLanguage); val pressed = v.isPressed; v.isPressed = false
                    if (pressed && spaceGesture.up()) v.performClick()
                    if (pressed) spaceKey.typedOnLift()
                    cursorSlide = null
                }
                MotionEvent.ACTION_CANCEL -> { roll.up(v); removeCallbacks(switchLanguage); v.isPressed = false; cursorSlide = null }
            }
            true
        }
        period = caps.cap(".", context.getString(R.string.keyboard_period), modifier) { ime.engine.type(".") }
        // Held on the letters page, period offers Gboard's punctuation; on the symbol pages an ellipsis.
        period.setOnLongClickListener {
            host.openAccents(if (current?.symbols == false) LetterFeedback.periodHolds.flatten() else LetterFeedback.symbolHolds('.'), it); true
        }
        period.longClickLabel = context.getString(R.string.keyboard_more_characters)
        typesOnLift(period, preview = { null },
            holds = { if (current?.symbols == false) LetterFeedback.periodHolds else listOf(LetterFeedback.symbolHolds('.')) },
            default = { if (current?.symbols == false) LetterFeedback.PERIOD_DEFAULT else 0 },
            type = { option -> if (option != null) ime.engine.type(option) else period.performClick() })
        bottomSlides[period] = { ime.engine.type(".") }
        enterKey = caps.cap("", context.getString(R.string.keyboard_return), modifier, 17f, ime.engine::enter)
        enterKey.highlighted = true
        enterKey.sound = AudioManager.FX_KEYPRESS_RETURN
        enterKey.textEntry = false
        typesOnLift(enterKey, preview = { null }, holds = { emptyList() }, type = { enterKey.performClick() })
        enterIcon.setImageResource(R.drawable.ic_enter)
        enterIcon.imageTintList = ColorStateList.valueOf(ink)
        enterKey.face.addView(enterIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        val languageKey = caps.icon(R.drawable.ic_language, context.getString(R.string.keyboard_switch_layout), modifier, ime::toggleLayout)
        typesOnLift(languageKey, preview = { null }, holds = { emptyList() }, type = { languageKey.performClick() })
        var at = 0f
        for ((key, width) in listOf(modeKey to KeyboardMetrics.MODE, comma to KeyboardMetrics.COMMA,
                languageKey to 1f, spaceKey to (KeyboardMetrics.SPACE - 1f), period to KeyboardMetrics.PERIOD, enterKey to KeyboardMetrics.ENTER)) { bottom.add(key, at, width); at += width }
        // Gboard's emoji panel ends with ABC and delete; Hex puts a space bar between them.
        val letters = caps.cap(context.getString(R.string.keyboard_letters_label), context.getString(R.string.keyboard_letters),
            modifier, 17f) { host.closeEmoji(); ime.engine.letters() }
        letters.textEntry = false
        typesOnLift(letters, preview = { null }, holds = { emptyList() }, type = { letters.performClick() })
        val emojiSpace = caps.cap("", context.getString(R.string.keyboard_space), caps.key, 14f) { ime.engine.space() }
        emojiSpace.sound = AudioManager.FX_KEYPRESS_SPACEBAR
        typesOnLift(emojiSpace, preview = { null }, holds = { emptyList() }, type = { emojiSpace.performClick() })
        emojiBottom.add(letters, 0f, KeyboardMetrics.MODE)
        emojiBottom.add(emojiSpace, KeyboardMetrics.MODE, 10f - 2 * KeyboardMetrics.MODE)
        emojiBottom.add(newDeleteKey(), 10f - KeyboardMetrics.MODE, KeyboardMetrics.MODE)
        emojiBottom.visibility = GONE
        addView(emojiBottom, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight + bottomExtra))
    }

    fun setFloating(enabled: Boolean) {
        if (floating == enabled) return
        floating = enabled
        caps.floating = enabled
        val shown = current
        if (emojiOpen) host.closeEmoji()
        for (row in listOf(bottom, emojiBottom)) {
            row.spacing(gap, insetTop, insetBottom + bottomExtra)
            row.layoutParams = row.layoutParams.apply { height = rowHeight + bottomExtra }
            row.children.filterIsInstance<KeyCap>().forEach { it.applyPalette(enabled) }
        }
        current = null
        if (shown != null) showPage(shown)
    }

    fun setFloatingRowHeight(value: Float): Boolean {
        if (floatingRowHeight == value) return false
        val previous = rowHeight
        floatingRowHeight = value
        if (!floating || previous == rowHeight) return false
        for (row in listOf(bottom, emojiBottom)) {
            row.spacing(gap, insetTop, insetBottom + bottomExtra)
            row.layoutParams = row.layoutParams.apply { height = rowHeight + bottomExtra }
        }
        val shown = current
        if (shown?.pad != null) {
            current = null
            showPage(shown)
        } else {
            rows.children.filterIsInstance<KeyRowLayout>().forEachIndexed { index, row ->
                val top = if (index == 0) reach else 0
                row.spacing(gap, insetTop + top, insetBottom)
                row.layoutParams = row.layoutParams.apply { height = rowHeight + top }
            }
        }
        if (emojiOpen) picker?.let { p ->
            p.layoutParams = p.layoutParams.apply { height += 3 * (rowHeight - previous) }
        }
        grid = null
        return true
    }

    fun split(enabled: Boolean) {
        if (splitMode == enabled) return
        splitMode = enabled
        rows.children.filterIsInstance<KeyRowLayout>().forEach { it.split(enabled) }
        bottom.split(enabled)
        grid = null
    }
    private fun cased(c: Char) = if (current?.let { !it.symbols && it.shifted } == true) c.uppercaseChar().toString() else c.toString()

    fun showPage(page: KeyboardPage) {
        val shown = current
        // Shift only recases the letters and changes nothing on the symbol pages: neither draws the keys again,
        // which would drop a key a second finger is already on (typing the first of a sentence turns shift off).
        if (shown != null && page.copy(shifted = shown.shifted, locked = shown.locked) == shown) {
            current = page; if (!page.symbols) relabel(page); return
        }
        // Another kind of field in the same box (the app changed its type): the keys, not the emoji panel over them.
        if (emojiOpen) host.closeEmoji()
        current = page
        clearCallout(); endSlide(); grid = null; letters.clear(); caseKeys.clear(); slides.clear(); shiftIcon = null
        rows.removeAllViews()
        page.pad?.let { showPad(page, it); return }
        bottom.visibility = VISIBLE
        val language = page.language
        val symbols = page.symbols
        val lines = page.lines
        val columns = KeyboardMetrics.columns(lines).toFloat()
        val side = KeyboardMetrics.sideWidth(page, lines)
        for ((index, line) in lines.withIndex()) {
            val top = if (index == 0) reach else 0
            val row = KeyRowLayout(context, columns, this.side, gap, insetTop + top, insetBottom).apply { split(splitMode) }
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight + top))
            // Rows 1 and 2 centre their letters; row 3's share what shift and delete leave.
            var at = if (index < 2) (columns - line.length) / 2f else 0f
            val width = if (index < 2) 1f else (columns - 2 * side) / line.length
            if (index == 2) {
                val switch = if (symbols) caps.cap(context.getString(if (page.more) R.string.keyboard_page_numbers else R.string.keyboard_page_symbols),
                    context.getString(R.string.keyboard_more_symbols), modifier, 15f, ime.engine::shiftUp).also { it.textEntry = false }
                else caps.icon(shiftIconFor(page), shiftDescription(page), modifier, { ime.engine.shiftDown(); ime.engine.shiftUp() }).also { key ->
                    shiftIcon = key.face.getChildAt(0) as ImageView
                    // Shift acts as it goes down and again as it lifts: a chord types capitals, and a slide
                    // onto a letter types its capital (Gboard), then shift goes off.
                    key.setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { roll.down(v); v.isPressed = true; endSlide(); ime.engine.shiftDown() }
                            MotionEvent.ACTION_MOVE -> if (v.isPressed) slideOver(v, e)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                roll.up(v)
                                val onto = slidOnto; endSlide()
                                if (onto != null && e.actionMasked == MotionEvent.ACTION_UP) typeSlid(onto, e)
                                if (v.isPressed) { v.isPressed = false; ime.engine.shiftUp() }
                            }
                        }
                        true
                    }
                }
                row.add(switch, 0f, side); at = side
            }
            for ((position, c) in line.withIndex()) {
                var touch: KeyPoint? = null
                // The letter as it was when the finger went down, so shift lifting mid-press can't change it.
                var downText: String? = null
                val k = caps.cap(cased(c), cased(c)) {
                    val g = if (symbols) null else geometry()
                    val p = touch ?: g?.centres?.get(c)
                    val text = (downText ?: cased(c)).let { if (symbols || touch == null) it else likelier(c, it, touch!!) }
                    if (g != null && p != null) ime.engine.letter(text, p.x, p.y, g) else ime.engine.type(text)
                }
                row.add(k, at, width); at += width
                if (!symbols) { letters[c] = k; caseKeys.add(c to k) }
                val digit = if (symbols) null else KeyboardLetters.digit(index, position)
                k.hint(digit?.toString())
                fun holds() = if (symbols) LetterFeedback.symbolHolds(c) else LetterFeedback.holds(cased(c).single(), language.layoutCode, digit)
                if (holds().isNotEmpty()) {
                    k.setOnLongClickListener { host.openAccents(holds(), it); true }
                    k.longClickLabel = context.getString(R.string.keyboard_more_characters)
                }
                slides[k] = { p ->
                    val g = if (symbols) null else geometry()
                    if (g != null) ime.engine.letter(cased(c), p.x, p.y, g) else ime.engine.type(cased(c))
                }
                typesOnLift(k, preview = { cased(c) }, holds = { listOf(holds()) },
                    down = { e ->
                        downText = cased(c)
                        touch = KeyPoint((k.left + e.getX(e.actionIndex)).toDouble(), (row.top + e.getY(e.actionIndex)).toDouble())
                    },
                    type = { option ->
                        val text = downText ?: cased(c)
                        if (option != null && option != text) {
                            val g = geometry(); val p = touch
                            if (g != null && p != null) ime.engine.letter(option, p.x, p.y, g, explicit = true) else ime.engine.type(option)
                        } else k.performClick()
                    },
                    done = { touch = null; downText = null })
            }
            if (index == 2) deleteKey = newDeleteKey().also { row.add(it, columns - side, side) }
        }
        emojiHint.visibility = if (symbols) GONE else VISIBLE
        comma.hint(if (symbols) context.getString(R.string.keyboard_pad_label) else null)
        comma.longClickLabel = context.getString(if (symbols) R.string.keyboard_pad else R.string.keyboard_emoji)
        modeKey.label?.text = context.getString(if (symbols) R.string.keyboard_letters_label else R.string.keyboard_numbers_label)
        modeKey.contentDescription = context.getString(if (symbols) R.string.keyboard_letters else R.string.keyboard_numbers)
        commaText = if (symbols) "," else page.kind.mark
        comma.label?.text = commaText
        comma.label?.textSize = if (commaText == "@") 21f else 26f
        comma.contentDescription = context.getString(when (commaText) { "@" -> R.string.keyboard_at; "/" -> R.string.keyboard_slash; else -> R.string.keyboard_comma })
        // An app's own label, else Gboard's symbol for the editor's action.
        val label = page.enter.label
        enterKey.label?.text = label.orEmpty()
        enterIcon.setImageResource(actionIcon(page.enter.action))
        enterIcon.visibility = if (label == null) View.VISIBLE else View.GONE
        enterKey.contentDescription = label ?: actionLabel(page.enter.action) ?: context.getString(R.string.keyboard_return)
    }

    /** A pad in place of the letter rows and the bottom row (Gboard's for number, phone and date fields,
     * and the number pad of its symbol page): its keys 53.3 dp tall on the letters' rows. */
    private fun showPad(page: KeyboardPage, pad: Pad) {
        bottom.visibility = GONE
        val cells = KeyPads.cells(pad)
        val padKey = maxOf(keyHeight, rowHeight - px(KeyboardMetrics.PAD_ROW_GAP))
        val top = (rowHeight - padKey) / 2; val under = rowHeight - padKey - top
        val lines = KeyPads.rows(pad)
        // The symbol pad's operators stand in one column beside its first three rows.
        val block = if (pad == Pad.Symbols) PadBlock(cells, reach + top, under).also {
            rows.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 3 * rowHeight + reach))
        } else null
        for ((index, line) in lines.withIndex()) {
            val first = index == 0; val last = index == lines.lastIndex
            // The symbol pad ends on the letters' bottom row, as Gboard's does.
            val t = if (last && pad == Pad.Symbols) insetTop else top
            val b = if (last && pad == Pad.Symbols) insetBottom else under
            val row = KeyRowLayout(context, cells, side, gap, t + (if (first) reach else 0), b + (if (last) bottomExtra else 0))
            val height = rowHeight + (if (first) reach else 0) + (if (last) bottomExtra else 0)
            (if (block != null && !last) block.lines else rows).addView(row, LayoutParams(LayoutParams.MATCH_PARENT, height))
            for (key in line) row.add(padKeyView(key, page), key.start, key.width)
        }
    }

    private fun padKeyView(key: PadKey, page: KeyboardPage): KeyCap {
        val fill = if (key.modifier) modifier else caps.key
        val label = if (key.word != 0) context.getString(key.word) else key.label
        val k = when (key.action) {
            PadAction.Delete -> return newDeleteKey()
            PadAction.Space -> caps.icon(R.drawable.ic_space, context.getString(R.string.keyboard_space), fill, { ime.engine.space() })
                .also { it.sound = AudioManager.FX_KEYPRESS_SPACEBAR }
            PadAction.Enter -> (page.enter.label?.let { caps.cap(it, it, fill, 17f, ime.engine::enter) }
                ?: caps.icon(actionIcon(page.enter.action), actionLabel(page.enter.action) ?: context.getString(R.string.keyboard_return),
                    fill, ime.engine::enter)).also { it.sound = AudioManager.FX_KEYPRESS_RETURN }
            else -> caps.cap(label, padDescription(key, label), fill, when { key.word != 0 -> 17f; label.length > 1 -> 21f; else -> 26f }) { act(key.action) }
        }
        // A phone digit's letters beside it, small and quiet, as Gboard's.
        key.letters?.let { letters ->
            k.label?.text = SpannableString("$label  $letters").apply {
                setSpan(RelativeSizeSpan(0.46f), label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(quiet), label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(Centred, label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        k.textEntry = key.action is PadAction.Text || key.action == PadAction.Space
        key.hold?.let { held ->
            k.setOnLongClickListener { ime.engine.type(held); true }
            k.longClickLabel = context.getString(R.string.keyboard_type_held, held)
        }
        typesOnLift(k, preview = { null }, holds = { key.hold?.let { listOf(listOf(it)) } ?: emptyList() },
            type = { option -> if (option != null) ime.engine.type(option) else k.performClick() })
        return k
    }
    // What TalkBack calls a pad's switches; other keys are their own label.
    private fun padDescription(key: PadKey, label: String) = when (val action = key.action) {
        is PadAction.Go -> context.getString(if (action.pad == Pad.PhoneSymbols) R.string.keyboard_phone_symbols else R.string.keyboard_phone_keypad)
        PadAction.Letters -> context.getString(R.string.keyboard_letters)
        PadAction.SymbolsPage -> context.getString(R.string.keyboard_symbols)
        else -> label
    }
    /** Small letters beside a big digit sit at its middle, not on its baseline. */
    private object Centred : MetricAffectingSpan() {
        private fun raise(paint: TextPaint) { paint.baselineShift -= (paint.textSize * 0.62f).roundToInt() }
        override fun updateDrawState(paint: TextPaint) = raise(paint)
        override fun updateMeasureState(paint: TextPaint) = raise(paint)
    }
    private fun act(action: PadAction) = when (action) {
        is PadAction.Text -> ime.engine.type(action.text)
        is PadAction.Go -> ime.engine.pad(action.pad)
        PadAction.Letters -> ime.engine.letters()
        PadAction.SymbolsPage -> ime.engine.symbolsPage()
        PadAction.Space -> ime.engine.space()
        PadAction.Enter -> ime.engine.enter()
        PadAction.Delete -> ime.engine.delete()
    }

    /** The symbol pad's first three rows with its operator column laid over their first cells: one
     * pill of the modifier colour that scrolls, four operators showing, as Gboard's. */
    private inner class PadBlock(private val cells: Float, private val top: Int, private val under: Int) : FrameLayout(context) {
        val lines = LinearLayout(context).apply { orientation = VERTICAL }
        private val column = ScrollView(context).apply {
            background = GradientDrawable().apply { cornerRadius = KeyboardMetrics.CORNER * density; setColor(modifier) }
            clipToOutline = true; isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        }
        private val items = LinearLayout(context).apply { orientation = VERTICAL }
        init {
            addView(lines, LayoutParams(-1, -1)); addView(column); column.addView(items)
            for (op in KeyPads.operators) {
                val k = caps.cap(op, op, Color.TRANSPARENT, 21f) { ime.engine.type(op) }
                typesOnLift(k, preview = { null }, holds = { emptyList() }, type = { k.performClick() })
                items.addView(k)
            }
        }
        private fun cell() = (measuredWidth - 2 * side) / cells
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val w = ((side + 1.5f * cell() - gap / 2) - (side + gap / 2)).roundToInt()
            val h = measuredHeight - top - under
            for (i in 0 until items.childCount) items.getChildAt(i).layoutParams = LinearLayout.LayoutParams(w, h / 4)
            column.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            lines.layout(0, 0, r - l, b - t)
            val left = (side + gap / 2).roundToInt()
            column.layout(left, top, left + column.measuredWidth, top + column.measuredHeight)
        }
    }

    /** Delete: a lifted press deletes once; held still it repeats, slid left it selects words to delete. */
    private fun newDeleteKey(): KeyCap = caps.icon(R.drawable.ic_delete, context.getString(R.string.keyboard_delete), modifier, ime.engine::delete).also { k ->
        k.sound = AudioManager.FX_KEYPRESS_DELETE
        fun lift(typing: Boolean) {
            removeCallbacks(repeatDelete); val pressed = k.isPressed; k.isPressed = false
            val slide = deleteSlide
            when {
                slide != null -> if (slide.words > 0) ime.engine.deleteSelection()
                typing && deleting && !repeated && deleteGesture.tap -> k.performClick()
            }
            if (typing && pressed) k.typedOnLift()
            deleting = false; deleteSlide = null
        }
        k.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    roll.down(v) { lift(typing = true) }
                    repeated = false; deleting = true; v.isPressed = true; deleteSlide = null
                    deleteGesture.down(e.x)
                    postDelayed(repeatDelete, KeyboardMetrics.DELETE_REPEAT_DELAY)
                }
                MotionEvent.ACTION_MOVE -> if (deleting) {
                    val words = deleteGesture.move(e.x)
                    if (words > 0 && deleteSlide == null && !repeated) {
                        removeCallbacks(repeatDelete)
                        deleteSlide = ime.engine.deleteSlide()
                    }
                    if (!deleteGesture.still) removeCallbacks(repeatDelete)
                    deleteSlide?.select(words)
                }
                MotionEvent.ACTION_UP -> if (roll.up(v)) lift(typing = true)
                MotionEvent.ACTION_CANCEL -> {
                    roll.up(v); removeCallbacks(repeatDelete); deleting = false; v.isPressed = false
                    deleteSlide?.select(0); deleteSlide = null
                }
            }
            true
        }
    }

    /** The emoji panel opens in place of the letter rows and the strip above them ([extra]), and grows as
     * Gboard's does (124 dp above the keys), as long as the keyboard stays within 60% of the screen (two
     * thirds turned). One picker for the keys' life, hidden while closed: a new one per opening was never
     * collected (its emoji are drawn into bitmaps, about 5 MB a panel). Each opening hands it the recents
     * again, which redraws its Recently used row, and starts it at the top, as a new picker did. */
    fun openEmoji(extra: Int) {
        cancelGestures()
        val keyboard = height + extra // the keys' and the strip's height as they are
        // Turned, the screen is short: the panel may take up to two thirds of it, with smaller cells.
        val share = if (landscape) 0.67f else 0.6f
        val grow = minOf(px(KeyboardMetrics.EMOJI_GROW), (resources.displayMetrics.heightPixels * share).toInt() - keyboard).coerceAtLeast(0)
        val pickerHeight = 3 * rowHeight + reach + extra + grow
        val p = picker?.also { it.setRecentEmojiProvider(recents); it.post { it.toTop() } } ?: EmojiPickerView(ContextThemeWrapper(context, R.style.HexEmoji)).also { p ->
            p.setRecentEmojiProvider(recents)
            p.setOnEmojiPickedListener { item ->
                // Gboard's tap as an emoji is chosen; the picker's own view plays the click.
                if (vibrates(context, ime.feedback.haptics)) p.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                ime.engine.type(item.emoji)
            }
            picker = p; addView(p, 0, LayoutParams(LayoutParams.MATCH_PARENT, pickerHeight))
        }
        sizeEmojiGrid(p)
        if (p.layoutParams.height != pickerHeight) p.layoutParams = p.layoutParams.apply { height = pickerHeight }
        rows.visibility = GONE; bottom.visibility = GONE
        p.visibility = VISIBLE; emojiBottom.visibility = VISIBLE
        emojiOpen = true
    }
    fun closeEmoji() {
        if (!emojiOpen) return
        cancelGestures()
        picker?.visibility = GONE; emojiBottom.visibility = GONE
        rows.visibility = VISIBLE; bottom.visibility = VISIBLE
        emojiOpen = false
    }
    private val recents by lazy { RecentEmoji(context) }
    private var pickerWidth = 0
    private fun sizeEmojiGrid(picker: EmojiPickerView) {
        if (width <= 0 || width == pickerWidth) return
        pickerWidth = width
        // AndroidX measures square cells when rows are unset and scales a fixed 30 sp bitmap.
        // Limit the cell pitch to keep glyphs close to their rendered size on wide screens.
        picker.emojiGridColumns = ceil(width / (KeyboardMetrics.EMOJI_CELL * density)).toInt()
    }
    /** The picker back at Recently used: its tab for them (the icon's clickable item), tapped. */
    private fun EmojiPickerView.toTop() {
        val name = context.getString(R.string.emoji_category_recent)
        fun tab(v: View): View? = if (v.contentDescription == name) v else (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::tab)
        var item = tab(this)
        while (item != null && !item.isClickable) item = item.parent as? View
        item?.performClick()
    }

    /** A held key either opens its long-click action or shows options to choose from. Otherwise it
     * types on lifting or when another key goes down first. */
    private fun typesOnLift(k: KeyCap, preview: () -> String?, holds: () -> List<List<String>>, default: () -> Int = { 0 },
                            down: (MotionEvent) -> Unit = {}, activateOnHold: Boolean = false,
                            type: (String?) -> Unit, done: () -> Unit = {}) {
        fun finish(): Boolean {
            val option = if (feedback.choosing) feedback.selected() else null
            val pressed = k.isPressed; k.isPressed = false; clearCallout()
            if (pressed) type(option)
            done()
            return pressed && option == null
        }
        k.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                // A second finger on the same key is a second press: the first types now.
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && roll.up(k)) finish()
                    roll.down(k) { finish() }
                    clearCallout(); v.isPressed = true
                    down(e)
                    val shown = preview()
                    if (shown != null && ime.feedback.popups && ime.previewAllowed) feedback.present(k.face, listOf(shown))
                    val options = holds()
                    if (activateOnHold || !options.firstOrNull().isNullOrEmpty()) {
                        val hold = Runnable {
                            if (v.isPressed && activateOnHold) {
                                v.isPressed = false
                                v.performLongClick()
                            } else if (v.isPressed) feedback.hold(k.face, options, default())
                        }
                        pendingHold = hold
                        postDelayed(hold, KeyboardMetrics.HOLD_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (feedback.choosing && v.isPressed) { val (x, y) = inHost(v, e); feedback.move(x, y) }
                    else if (e.x < -slop || e.x > v.width + slop || e.y < -slop || e.y > v.height + slop) {
                        if (v.isPressed) clearCallout()
                        v.isPressed = false
                    }
                }
                // Gboard's lighter second tick when a key types as it lifts, but not for a held option.
                MotionEvent.ACTION_UP -> if (roll.up(k) && finish()) k.typedOnLift()
                MotionEvent.ACTION_CANCEL -> if (roll.up(k)) { v.isPressed = false; clearCallout(); done() }
            }
            true
        }
    }

    /** A slide from ?123 or shift moved: the key under the finger shows pressed, with its pop-up. */
    private fun slideOver(from: View, e: MotionEvent) {
        val over = keyAt(e.rawX, e.rawY)?.takeIf { it !== from }
        if (over === slidOnto) return
        slidOnto?.isPressed = false; feedback.clear()
        slidOnto = over
        if (over == null) return
        over.isPressed = true
        val text = over.label?.text?.toString()
        if (!text.isNullOrEmpty() && ime.feedback.popups && ime.previewAllowed) feedback.present(over.face, listOf(text))
    }
    private fun endSlide() { slidOnto?.isPressed = false; slidOnto = null; feedback.clear() }
    /** The slide lifted on [key]: it types, with the lift point in the rows' coordinates for the spatial model. */
    private fun typeSlid(key: KeyCap, e: MotionEvent): Boolean {
        val type = slides[key] ?: bottomSlides[key] ?: return false
        rows.getLocationOnScreen(onScreen)
        type(KeyPoint((e.rawX - onScreen[0]).toDouble(), (e.rawY - onScreen[1]).toDouble()))
        return true
    }
    private fun keyAt(x: Float, y: Float): KeyCap? {
        for (key in slides.keys + bottomSlides.keys) {
            key.getLocationOnScreen(onScreen)
            if (x >= onScreen[0] && x < onScreen[0] + key.width && y >= onScreen[1] && y < onScreen[1] + key.height) return key
        }
        return null
    }
    /** A touch in the host's coordinates, where the pop-ups are drawn. */
    private fun inHost(v: View, e: MotionEvent): Pair<Float, Float> {
        host.getLocationInWindow(hostAt); v.getLocationInWindow(onScreen)
        return (onScreen[0] - hostAt[0] + e.x) to (onScreen[1] - hostAt[1] + e.y)
    }

    private fun shiftIconFor(page: KeyboardPage) = when { page.locked -> R.drawable.ic_caps_lock; page.shifted -> R.drawable.ic_shift_on; else -> R.drawable.ic_shift }
    // As Gboard's: "Shift", "Shift enabled", "Caps lock enabled".
    private fun shiftDescription(page: KeyboardPage) = context.getString(when {
        page.locked -> R.string.keyboard_caps_lock_on; page.shifted -> R.string.keyboard_shift_on; else -> R.string.keyboard_shift
    })
    /** Shift changed and nothing else: new labels on the same keys. */
    private fun relabel(page: KeyboardPage) {
        for ((c, key) in caseKeys) { val text = cased(c); key.label?.text = text; key.contentDescription = text }
        shiftIcon?.setImageResource(shiftIconFor(page))
        (shiftIcon?.parent?.parent as? View)?.contentDescription = shiftDescription(page)
    }

    private fun actionLabel(action: Int) = when (action) {
        EditorInfo.IME_ACTION_DONE -> R.string.keyboard_action_done
        EditorInfo.IME_ACTION_GO -> R.string.keyboard_action_go
        EditorInfo.IME_ACTION_NEXT -> R.string.keyboard_action_next
        EditorInfo.IME_ACTION_PREVIOUS -> R.string.keyboard_action_previous
        EditorInfo.IME_ACTION_SEARCH -> R.string.keyboard_action_search
        EditorInfo.IME_ACTION_SEND -> R.string.keyboard_action_send
        else -> null
    }?.let(context::getString)
    // Gboard's symbols (Pixel 8 emulator): ✓ done, → go, →| next, |← previous, a magnifier, send, else return.
    private fun actionIcon(action: Int) = when (action) {
        EditorInfo.IME_ACTION_DONE -> R.drawable.ic_action_done
        EditorInfo.IME_ACTION_GO -> R.drawable.ic_action_go
        EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_action_next
        EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_action_previous
        EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_action_search
        EditorInfo.IME_ACTION_SEND -> R.drawable.ic_action_send
        else -> R.drawable.ic_enter
    }

    /** The letter a touch types: its own key's, or a neighbour's the word list makes likelier when the
     * touch is within TypingWord.REACH dp of that neighbour's touch area (never from a key's centre). */
    private fun likelier(c: Char, text: String, touch: KeyPoint): String {
        val near = HashMap<Char, Double>()
        for ((other, cap) in letters) {
            if (other == c) continue
            val row = cap.parent as View
            val dx = maxOf(cap.left - touch.x, 0.0, touch.x - cap.right)
            val dy = maxOf(row.top - touch.y, 0.0, touch.y - row.bottom)
            val dp = Math.hypot(dx, dy) / density
            if (dp < TypingWord.REACH) near[if (text.single().isUpperCase()) other.uppercaseChar() else other] = dp
        }
        if (near.isEmpty()) return text
        return ime.engine.likelierLetter(text.single(), near, letters.keys).toString()
    }

    /** Each letter's drawn keycap centre, in the rows' coordinates, which the touches use too. */
    private fun capCentre(cap: KeyCap) = KeyPoint(cap.left + cap.paddingLeft + (cap.width - cap.paddingLeft - cap.paddingRight) / 2.0,
        (cap.parent as View).top + cap.paddingTop + (cap.height - cap.paddingTop - cap.paddingBottom) / 2.0)
    private fun spaceRect(): KeyRect? {
        if (spaceKey.width == 0) return null
        val top = bottom.top - rows.top + spaceKey.paddingTop.toDouble()
        return KeyRect(spaceKey.left + spaceKey.paddingLeft.toDouble(), top, spaceKey.right - spaceKey.paddingRight.toDouble(),
            top + spaceKey.height - spaceKey.paddingTop - spaceKey.paddingBottom)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val point = KeyPoint(event.x.toDouble(), (event.y - rows.top).toDouble(), event.eventTime)
        val language = current?.language?.layoutCode
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearTrail()
                if (language in GlideDecoder.languages && ime.engine.canSwipe &&
                    letters.values.any { cap ->
                        val row = cap.parent as View
                        point.x >= cap.left && point.x < cap.right && point.y >= row.top && point.y < row.bottom
                    }) trail = mutableListOf(point)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!gliding) clearTrail()
            MotionEvent.ACTION_MOVE -> {
                val trace = trail
                val keyboardGrid = if (trace != null) geometry() else null
                if (trace != null && keyboardGrid != null) {
                    val first = trace.first()
                    val dx = (point.x - first.x) / kotlin.math.abs(keyboardGrid.pitchX).coerceAtLeast(1.0)
                    val dy = (point.y - first.y) / kotlin.math.abs(keyboardGrid.pitchY).coerceAtLeast(1.0)
                    if (!gliding && !feedback.choosing &&
                        Math.hypot(dx, dy) >= 0.65) {
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        gliding = true
                    }
                    if (trace.size < 1000) {
                        for (i in 0 until event.historySize)
                            trace.add(KeyPoint(event.getHistoricalX(i).toDouble(), (event.getHistoricalY(i) - rows.top).toDouble(), event.getHistoricalEventTime(i)))
                        trace.add(point)
                    }
                    if (gliding) { invalidate(); return true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gliding) {
                    val trace = trail?.toList().orEmpty() + point
                    val keyboardGrid = geometry()
                    clearTrail()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        fadeTrail(trace)
                        if (keyboardGrid != null && language != null) ime.glide(trace, keyboardGrid, language)
                    }
                    return true
                }
                clearTrail()
            }
        }
        if (gliding) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val points = if (gliding) trail else fadingTrail
        if (points == null || points.size < 2) return
        val first = points.first()
        val last = points.last()
        tracePath.rewind()
        tracePath.moveTo(first.x.toFloat(), first.y.toFloat() + rows.top)
        for (i in 1 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            tracePath.quadTo(a.x.toFloat(), a.y.toFloat() + rows.top,
                ((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat() + rows.top)
        }
        tracePath.lineTo(last.x.toFloat(), last.y.toFloat() + rows.top)
        traceMeasure.setPath(tracePath, false)
        val length = traceMeasure.length
        if (length <= 0) return
        val visibleLength = minOf(length, density * 215f)
        val start = length - visibleLength
        tracePaint.style = Paint.Style.STROKE
        for (i in 0 until 6) {
            traceSegment.rewind()
            traceMeasure.getSegment(start + visibleLength * i / 6, start + visibleLength * (i + 1) / 6,
                traceSegment, true)
            tracePaint.strokeWidth = density * (5f + i * 1.15f)
            tracePaint.alpha = ((16 + i * 5) * trailOpacity).roundToInt()
            canvas.drawPath(traceSegment, tracePaint)
            tracePaint.strokeWidth = density * (1.65f + i * .77f)
            tracePaint.alpha = ((58 + i * 35) * trailOpacity).roundToInt()
            canvas.drawPath(traceSegment, tracePaint)
        }
        tracePaint.style = Paint.Style.FILL
        tracePaint.alpha = (240 * trailOpacity).roundToInt()
        canvas.drawCircle(last.x.toFloat(), last.y.toFloat() + rows.top, density * 3.2f, tracePaint)
    }

    private fun fadeTrail(trace: List<KeyPoint>) {
        fadingTrail = trace
        trailFade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150
            addUpdateListener {
                trailOpacity = it.animatedValue as Float
                if (trailOpacity == 0f) fadingTrail = null
                invalidate()
            }
            start()
        }
    }

    private fun clearTrail() {
        gliding = false
        trail = null
        trailFade?.cancel()
        trailFade = null
        fadingTrail = null
        trailOpacity = 1f
        invalidate()
    }

    private fun geometry(): KeyGrid? {
        grid?.let { return it }
        // The pitch from the layout's own first two keys and the row under them: on AZERTY, q, w and a sit on
        // other rows (160 x -155 px instead of 107 x 155). The apostrophe is no letter for the touch model.
        val rows = KeyboardLetters.rows((current ?: return null).language.layoutCode)
        val first = letters[rows[0][0]] ?: return null; val next = letters[rows[0][1]] ?: return null; val below = letters[rows[1][0]] ?: return null
        if (first.width == 0 || next.left == first.left) return null
        val centres = letters.filterKeys { it.isLetter() }.mapValues { (_, cap) -> capCentre(cap) }
        return KeyGrid(centres, capCentre(next).x - capCentre(first).x, capCentre(below).y - capCentre(first).y, spaceRect()).also { grid = it }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        grid = null
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (emojiOpen && w != oldw) post { if (emojiOpen) picker?.let(::sizeEmojiGrid) }
    }
    fun clearCallout() {
        pendingHold?.let(::removeCallbacks); pendingHold = null; feedback.clear()
    }
    fun refreshSpace() {
        val next = ime.preferences.options(ime.nextEnglish)
        spaceKey.contentDescription = context.getString(R.string.keyboard_space)
        spaceKey.longClickLabel = context.getString(R.string.keyboard_space_language_action,
            if (next.translate) context.getString(R.string.keyboard_space_translate, next.spoken.label) else next.spoken.label)
        spaceKey.label?.text = ime.layoutLanguage.label
        spaceKey.label?.textSize = if (next.translate) 12f else 14f
    }
    fun cancelGestures() {
        clearTrail(); clearCallout(); endSlide(); roll.clear()
        removeCallbacks(repeatDelete); removeCallbacks(switchLanguage)
        comma.isPressed = false
        spaceKey.isPressed = false
        deleting = false
        deleteKey?.isPressed = false
    }
    override fun onDetachedFromWindow() { cancelGestures(); super.onDetachedFromWindow() }
}
