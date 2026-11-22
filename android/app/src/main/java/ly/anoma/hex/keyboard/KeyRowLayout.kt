package ly.anoma.hex.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/** A row's visible caps and touch bounds, with an optional untouchable gap between thumb groups. */
internal class KeyRowLayout(context: Context, private val cells: Float, private val side: Float,
                            private var gap: Float, private var insetTop: Int, private var insetBottom: Int) : ViewGroup(context) {
    private class Span(val start: Float, val fraction: Float) : LayoutParams(MATCH_PARENT, MATCH_PARENT)
    private var split = false
    private var edges = IntArray(0)

    fun add(view: View, start: Float, width: Float) = addView(view, Span(start, width))
    fun split(enabled: Boolean) {
        if (split != enabled) { split = enabled; requestLayout() }
    }
    fun spacing(gap: Float, top: Int, bottom: Int) {
        this.gap = gap; this.insetTop = top; this.insetBottom = bottom
        requestLayout()
    }

    private fun span(i: Int) = getChildAt(i).layoutParams as Span

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        if (childCount == 0) return
        val breakAt = if (split && childCount > 2)
            (1 until childCount).minBy { abs(span(it).start - cells / 2f) } else -1
        val middle = if (breakAt > 0) (width * .15f).coerceAtMost(110 * resources.displayMetrics.density) else 0f
        val cell = ((width - 2 * side - middle) / cells).coerceAtLeast(0f)
        fun centerStart(i: Int) = side + span(i).start * cell + if (breakAt > 0 && i >= breakAt) middle else 0f
        fun centerEnd(i: Int) = centerStart(i) + span(i).fraction * cell
        edges = IntArray(childCount * 2)
        for (i in 0 until childCount) {
            val left = when (i) {
                0 -> 0
                breakAt -> centerStart(i).roundToInt()
                else -> ((centerEnd(i - 1) + centerStart(i)) / 2).roundToInt()
            }
            val right = when {
                i == childCount - 1 -> width
                breakAt > 0 && i == breakAt - 1 -> centerEnd(i).roundToInt()
                else -> ((centerEnd(i) + centerStart(i + 1)) / 2).roundToInt()
            }
            val child = getChildAt(i)
            val capLeft = (centerStart(i) + gap / 2).roundToInt()
            val capRight = (centerEnd(i) - gap / 2).roundToInt()
            edges[2 * i] = left
            edges[2 * i + 1] = right
            child.setPadding((capLeft - left).coerceAtLeast(0), insetTop, (right - capRight).coerceAtLeast(0), insetBottom)
            child.measure(MeasureSpec.makeMeasureSpec((right - left).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(edges[2 * i], 0, edges[2 * i + 1], b - t)
    }
}
