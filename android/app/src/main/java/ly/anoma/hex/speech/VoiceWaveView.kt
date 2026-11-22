package ly.anoma.hex.speech

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import ly.anoma.hex.R
import ly.anoma.hex.voiceColor
import kotlin.math.pow
import kotlin.math.sin

internal fun voiceAmplitude(rms: Float) =
    ((rms - .0035f).coerceAtLeast(0f).pow(.47f) * 3.5f).coerceAtMost(1f)

/** Recent microphone levels, drawn as a smoothly scrolling row of capsules. */
internal class VoiceWaveView(context: Context) : View(context) {
    private val history = FloatArray(64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.voiceColor(R.color.floating_brand)
    private var phase = SpeechPhase.Idle
    private var newest = 0
    private var envelope = 0f
    private var lastSampleAt = 0L
    private var sampleInterval = 75L
    private var stoppedAt = 0L

    fun showPhase(value: SpeechPhase) {
        if (phase == value) return
        phase = value
        if (value == SpeechPhase.Opening || value == SpeechPhase.Idle) {
            history.fill(0f)
            envelope = 0f
            lastSampleAt = 0L
        }
        if (value == SpeechPhase.Transcribing) stoppedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun push(rms: Float) {
        if (phase != SpeechPhase.Listening) return
        val now = SystemClock.uptimeMillis()
        if (lastSampleAt != 0L) sampleInterval = (now - lastSampleAt).coerceIn(35L, 140L)
        lastSampleAt = now

        val amplitude = voiceAmplitude(rms)
        val response = if (amplitude > envelope) .82f else .48f
        envelope += (amplitude - envelope) * response
        newest = (newest + 1) % history.size
        history[newest] = envelope
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        val animate = ValueAnimator.areAnimatorsEnabled()
        val elapsed = now - lastSampleAt
        val offset = if (animate) (elapsed.toFloat() / sampleInterval).coerceIn(0f, 1f) else 1f
        val settle = if (phase == SpeechPhase.Transcribing) {
            if (animate) (1f - (now - stoppedAt) / 170f).coerceIn(0f, 1f) else 0f
        } else 1f
        val columns = (width / (density * 5.2f)).toInt().coerceIn(1, history.lastIndex)
        val step = width.toFloat() / columns
        val radius = minOf(density * 1.25f, step * .31f)
        val middle = height / 2f

        paint.color = accent
        for (i in 0 until columns) {
            val age = columns - 1 - i
            val recent = history[(newest - age + history.size) % history.size]
            val older = history[(newest - age - 1 + history.size) % history.size]
            val level = (older + (recent - older) * offset) * settle
            val place = (i + .5f) / columns
            val texture = .76f + .24f * sin((age - offset) * 1.3f)
            val halfHeight = radius + level * height * .31f * texture
            val x = (i + .5f) * step
            paint.alpha = ((43 + 46 * place + 165 * level) * (.63f + .37f * place)).toInt()
            canvas.drawRoundRect(x - radius, middle - halfHeight, x + radius, middle + halfHeight,
                radius, radius, paint)
        }

        if (animate && isShown && ((phase == SpeechPhase.Listening && offset < 1f) ||
                (phase == SpeechPhase.Transcribing && settle > 0f))) postInvalidateOnAnimation()
    }
}
