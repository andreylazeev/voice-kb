package ly.anoma.hex.keyboard

import android.view.KeyEvent
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.sign

internal enum class SpaceSwipeBehavior { LanguageAndCursor, LanguageOnly, CursorOnly, Off }

/** Classifies a press before acting on it. In mixed mode the first deliberate movement
 * decides whether this is an immediate swipe or a drag after a stationary hold. */
internal class SpaceGesture(private val start: Float, private val step: Float, private val stillSlop: Float) {
    enum class Release { Space, SwitchLanguage, LanguageMenu, None }
    private enum class Route { Language, Cursor }

    companion object {
        private const val QUICK_MS = 180L
        internal const val CURSOR_HOLD_MS = 350L
    }

    private var originX = 0f
    private var originY = 0f
    private var downAt = 0L
    private var moved = 0f
    private var languageSwipe = false
    private var cursorSlide = false
    private var dx = 0f
    private var dy = 0f
    private var route: Route? = null
    private var behavior = SpaceSwipeBehavior.LanguageAndCursor

    fun down(x: Float, y: Float, time: Long, behavior: SpaceSwipeBehavior) {
        originX = x; originY = y; downAt = time; moved = 0f
        dx = 0f; dy = 0f; languageSwipe = false; cursorSlide = false
        this.behavior = behavior
        route = when (behavior) {
            SpaceSwipeBehavior.LanguageAndCursor -> null
            SpaceSwipeBehavior.LanguageOnly -> Route.Language
            SpaceSwipeBehavior.CursorOnly, SpaceSwipeBehavior.Off -> Route.Cursor
        }
    }

    /** The finger moved: how many characters the cursor should be from where it was, or null until it slides. */
    fun move(x: Float, y: Float, time: Long): Int? {
        dx = x - originX
        dy = y - originY
        val elapsed = time - downAt
        val wasStill = still
        moved = maxOf(moved, abs(dx), abs(dy))
        if (route == null && wasStill && !still)
            route = if (elapsed >= CURSOR_HOLD_MS) Route.Cursor else Route.Language
        if (route == Route.Language && abs(dx) >= maxOf(start, 2 * stillSlop) && abs(dx) > abs(dy))
            languageSwipe = true
        if (route == Route.Cursor && abs(dx) >= start && elapsed >= QUICK_MS) cursorSlide = true
        return if (cursorSlide && behavior != SpaceSwipeBehavior.Off) dx.sign.toInt() * characters(abs(dx)) else null
    }

    fun characters(distance: Float) = if (distance < start) 0 else ((distance - start) / step).toInt() + 1

    /** Still enough for a long press to mean the menu. */
    val still get() = moved < stillSlop

    val typesOnRollover get() = !languageSwipe && !cursorSlide

    fun up(time: Long, longPress: Long): Release = when {
        languageSwipe -> if (abs(dx) >= maxOf(start, 2 * stillSlop) && abs(dx) > abs(dy)) Release.SwitchLanguage else Release.None
        cursorSlide -> Release.None
        still && time - downAt >= longPress -> Release.LanguageMenu
        else -> Release.Space
    }
}

/** Moves the cursor whole characters (grapheme clusters, so 👍🏽 is one) from where a slide began: left
 * from the selection's start, right from its end. Without a known position, or in a password field
 * whose text it shouldn't read, it steps with arrow keys instead. */
internal class CursorSlide(private val target: KeyboardTextTarget, private val start: Int, private val end: Int, readable: Boolean) {
    private val window = 2000
    private val left: IntArray
    private val right: IntArray
    private val arrows = !readable || start < 0 || end < 0
    private var at = 0

    init {
        left = if (arrows) IntArray(0) else boundaries(target.before(window).orEmpty(), backwards = true)
        right = if (arrows) IntArray(0) else boundaries(target.after(window).orEmpty(), backwards = false)
    }

    fun moveTo(characters: Int) {
        if (arrows) {
            while (at > characters) { target.sendKey(KeyEvent.KEYCODE_DPAD_LEFT); at-- }
            while (at < characters) { target.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT); at++ }
            return
        }
        val next = characters.coerceIn(-left.size, right.size)
        if (next == at) return
        at = next
        val position = when {
            next < 0 -> start - left[-next - 1]
            next > 0 -> end + right[next - 1]
            else -> if (start == end) start else return
        }
        target.setSelection(position, position)
    }

    private companion object {
        /** UTF-16 distances from the cursor to each character boundary, nearest first. */
        fun boundaries(text: String, backwards: Boolean): IntArray {
            if (text.isEmpty()) return IntArray(0)
            val it = BreakIterator.getCharacterInstance().apply { setText(text) }
            val out = ArrayList<Int>()
            if (backwards) {
                var p = it.last()
                while (true) { p = it.previous(); if (p == BreakIterator.DONE) break; out.add(text.length - p) }
            } else {
                var p = it.first()
                while (true) { p = it.next(); if (p == BreakIterator.DONE) break; out.add(p) }
            }
            return out.toIntArray()
        }
    }
}
