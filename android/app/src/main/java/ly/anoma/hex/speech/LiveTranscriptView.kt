package ly.anoma.hex.speech

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.UpdateAppearance
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useInter
import ly.anoma.hex.voiceColor
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Keeps the recognized prefix still while newly heard or revised words settle into place. */
internal class LiveTranscriptView(context: Context) : TextView(context) {
    private val ink = context.voiceColor(R.color.ink)
    private val quiet = context.voiceColor(R.color.quiet)
    private val density get() = resources.displayMetrics.density
    private val backdrop = TranscriptBackdrop(context)
    private var shown: LiveSpeech? = null
    private var reveal: ValueAnimator? = null
    private var scrolling: ValueAnimator? = null
    private val follow = Runnable { followLatestLine() }

    init {
        useInter(450)
        textSize = 15f
        setTextColor(ink)
        includeFontPadding = false
        setLineSpacing(3f * density, 1f)
        setPadding((12f * density).roundToInt(), (9f * density).roundToInt(),
            (12f * density).roundToInt(), (9f * density).roundToInt())
    }

    fun showPhase(phase: SpeechPhase) {
        backdrop.showPhase(phase)
        invalidate()
    }

    fun pushLevel(rms: Float) {
        backdrop.level(rms)
        invalidate()
    }

    fun show(speech: LiveSpeech?) {
        if (shown == speech) return
        val before = shown?.text.orEmpty()
        shown = speech
        reveal?.cancel()
        removeCallbacks(follow)
        val next = speech?.text.orEmpty()
        if (speech == null || next.isEmpty()) {
            text = ""
            scrolling?.cancel()
            scrollTo(0, 0)
            return
        }

        val prefix = before.commonPrefixWith(next).length
        val start = if (prefix < before.length && prefix < next.length &&
            !before[prefix].isWhitespace() && !next[prefix].isWhitespace())
            next.lastIndexOf(' ', prefix - 1) + 1 else prefix
        val partialStart = next.length - speech.partial.length
        val animate = start < next.length && ValueAnimator.areAnimatorsEnabled()
        if (animate) backdrop.wordsArrived()
        val stableTail = WordReveal(ink, density)
        val partialTail = WordReveal(quiet, density)
        text = SpannableString(next).apply {
            val quietEnd = if (animate) maxOf(start, partialStart) else next.length
            if (partialStart < quietEnd)
                setSpan(ForegroundColorSpan(quiet), partialStart, quietEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (animate && start < partialStart)
                setSpan(stableTail, start, partialStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (animate && partialStart < next.length)
                setSpan(partialTail, maxOf(start, partialStart), next.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (animate) {
            reveal = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 175
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val value = it.animatedFraction
                    stableTail.progress = value
                    partialTail.progress = value
                    invalidate()
                }
                start()
            }
        }
        post(follow)
    }

    override fun onDraw(canvas: Canvas) {
        backdrop.draw(canvas, scrollX, scrollY, width, height)
        super.onDraw(canvas)
        if (isShown && backdrop.moving && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }

    private fun followLatestLine() {
        val available = height - paddingTop - paddingBottom
        if (available <= 0) return
        val target = ((layout?.height ?: 0) - available).coerceAtLeast(0)
        if (scrollY == target) return
        scrolling?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            scrollTo(0, target)
            return
        }
        scrolling = ValueAnimator.ofInt(scrollY, target).apply {
            duration = 155
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        reveal?.end()
        scrolling?.cancel()
        removeCallbacks(follow)
        super.onDetachedFromWindow()
    }

    private class WordReveal(private val color: Int, private val density: Float) : CharacterStyle(), UpdateAppearance {
        var progress = 0f
        override fun updateDrawState(paint: TextPaint) {
            paint.color = color
            paint.alpha = (Color.alpha(color) * (.28f + .72f * progress)).roundToInt()
            paint.baselineShift += (2f * density * (1f - progress)).roundToInt()
        }
    }
}

private class TranscriptBackdrop(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val sheet = context.voiceColor(R.color.sheet)
    private val accent = context.voiceColor(R.color.floating_brand)
    private val outline = context.voiceColor(R.color.rule)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val transform = Matrix()
    private val glow = RadialGradient(0f, 0f, 1f, intArrayOf(accent, accent and 0x00ffffff),
        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    private var phase = SpeechPhase.Idle
    private var firstFrame = 0L
    private var lastFrame = 0L
    private var lastLevelAt = 0L
    private var lastWordAt = 0L
    private var target = 0f
    private var level = 0f
    val moving get() = phase == SpeechPhase.Listening || (phase == SpeechPhase.Transcribing && level > .01f)

    fun showPhase(value: SpeechPhase) {
        if (value == phase) return
        phase = value
        if (value == SpeechPhase.Idle || value == SpeechPhase.Opening) {
            firstFrame = 0L
            lastFrame = 0L
            lastLevelAt = 0L
            lastWordAt = 0L
            target = 0f
            level = 0f
        }
    }

    fun level(rms: Float) {
        target = voiceAmplitude(rms)
        lastLevelAt = SystemClock.uptimeMillis()
    }

    fun wordsArrived() { lastWordAt = SystemClock.uptimeMillis() }

    fun draw(canvas: Canvas, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frame.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        val now = SystemClock.uptimeMillis()
        val animate = ValueAnimator.areAnimatorsEnabled()
        if (firstFrame == 0L) firstFrame = now
        val time = if (animate) (now - firstFrame) * .001f else 0f
        val delta = (now - lastFrame).coerceIn(0L, 80L)
        lastFrame = now
        val drive = if (phase == SpeechPhase.Listening && now - lastLevelAt < 220) target else 0f
        level = if (animate) level + (drive - level) * (1f - exp(-delta / 75f)) else drive
        val pulse = if (animate) (1f - (now - lastWordAt) / 340f).coerceIn(0f, 1f) else 0f
        val corner = 12f * density

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = sheet
        canvas.drawRoundRect(frame, corner, corner, paint)

        fun wash(x: Float, y: Float, width: Float, height: Float, strength: Int) {
            transform.setScale(frame.width() * width, frame.height() * height)
            transform.postTranslate(frame.left + frame.width() * x, frame.top + frame.height() * y)
            glow.setLocalMatrix(transform)
            paint.shader = glow
            paint.alpha = strength
            canvas.drawRoundRect(frame, corner, corner, paint)
        }
        wash(.22f + .16f * sin(time * 1.55f), .95f, .74f + .27f * level, 1.45f,
            (16f + 72f * level).roundToInt())
        wash(.75f + .15f * sin(time * 1.1f + 1.3f), .08f + .17f * sin(time * .9f), .65f, 1.16f,
            (12f + 48f * level + 39f * pulse).roundToInt())

        paint.shader = null
        paint.color = outline
        paint.alpha = 110
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * .7f
        frame.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(frame, corner, corner, paint)
    }

}
