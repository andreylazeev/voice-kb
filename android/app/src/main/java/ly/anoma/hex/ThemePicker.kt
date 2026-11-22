package ly.anoma.hex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The same miniature and controls appear in the keyboard and in its settings. */
internal fun themePicker(context: Context, appearance: Appearance, pick: (VoiceTheme) -> Unit): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(6))
        for (themes in VoiceTheme.entries.chunked(2)) {
            addView(LinearLayout(context).apply {
                for (theme in themes) {
                    val sample = theme.context(context.applicationContext, appearance.custom)
                    val selected = theme == appearance.theme
                    val name = context.getString(theme.label)
                    val tile = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(5), dp(5), dp(5), dp(3))
                        contentDescription = name
                        isFocusable = true
                        isSelected = selected
                        background = rounded(context.voiceColor(R.color.sheet), dp(12).toFloat()).apply {
                            if (selected) setStroke(dp(2), context.voiceColor(R.color.floating_brand))
                        }
                        addView(ThemePreview(context, sample), LinearLayout.LayoutParams(-1, -2))
                        addView(TextView(context).apply {
                            text = name
                            textSize = 13f
                            gravity = Gravity.CENTER
                            setTextColor(context.voiceColor(R.color.ink))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(-1, dp(23)))
                        setOnClickListener { pick(theme) }
                    }
                    addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

internal fun customThemeEditor(context: Context, initial: ThemeColors, save: (ThemeColors) -> Unit): View {
    var colors = initial
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(10))
    }
    val preview = ThemePreview(context, VoiceTheme.Custom.context(context, colors), maxHeightDp = 126)
    root.addView(preview, LinearLayout.LayoutParams(-1, -2))
    root.addView(TextView(context).apply {
        text = context.getString(R.string.theme_apply)
        setTextColor(context.voiceColor(R.color.floating_brand))
        gravity = Gravity.CENTER
        textSize = 15f
        isFocusable = true
        setOnClickListener { save(colors) }
    }, LinearLayout.LayoutParams(-1, root.dp(42)))

    fun swatches(title: Int, choices: List<Swatch>, isBackground: Boolean) {
        root.addView(TextView(context).apply {
            text = context.getString(title); textSize = 14f
            setTextColor(context.voiceColor(R.color.ink))
        }, LinearLayout.LayoutParams(-1, root.dp(24)))
        val row = LinearLayout(context)
        val buttons = ArrayList<View>()
        fun updateSelection() {
            val current = if (isBackground) colors.background else colors.accent
            choices.forEachIndexed { index, option ->
                val button = buttons[index]
                button.isSelected = option.color == current
                button.background = rounded(option.color, root.dp(10).toFloat()).apply {
                    if (button.isSelected) setStroke(root.dp(3), context.voiceColor(R.color.floating_brand))
                }
            }
        }
        for (option in choices) {
            val button = View(context).apply {
                contentDescription = context.getString(option.label)
                isFocusable = true
                setOnClickListener {
                    colors = if (isBackground) colors.copy(background = option.color) else colors.copy(accent = option.color)
                    preview.render(VoiceTheme.Custom.context(context, colors))
                    updateSelection()
                }
            }
            buttons.add(button)
            row.addView(button, LinearLayout.LayoutParams(0, root.dp(40), 1f).apply {
                setMargins(root.dp(3), root.dp(2), root.dp(3), root.dp(8))
            })
        }
        updateSelection()
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    swatches(R.string.theme_background, backgrounds, true)
    swatches(R.string.theme_accent, accents, false)
    return root
}

private data class Swatch(val color: Int, val label: Int)
private val backgrounds = listOf(
    Swatch(0xff212d2d.toInt(), R.string.theme_swatch_graphite),
    Swatch(0xff18273a.toInt(), R.string.theme_ocean),
    Swatch(0xff302438.toInt(), R.string.theme_plum),
    Swatch(0xff35241d.toInt(), R.string.theme_swatch_coffee),
    Swatch(0xfffff3e3.toInt(), R.string.theme_sand),
    Swatch(0xffeef4ef.toInt(), R.string.theme_light),
)
private val accents = listOf(
    Swatch(0xffa3f5d1.toInt(), R.string.theme_swatch_mint),
    Swatch(0xff82d5ff.toInt(), R.string.theme_swatch_blue),
    Swatch(0xffc7bcff.toInt(), R.string.theme_swatch_lavender),
    Swatch(0xfff2adda.toInt(), R.string.theme_swatch_rose),
    Swatch(0xffeeac73.toInt(), R.string.theme_swatch_peach),
    Swatch(0xffe2d774.toInt(), R.string.theme_swatch_gold),
)

private class ThemePreview(context: Context, private var sample: Context, private val maxHeightDp: Int = 112) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun render(value: Context) { sample = value; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = minOf(dp(maxHeightDp), (w * 58f / 120f).roundToInt())
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.save()
        val scale = minOf(width / 120f, height / 58f)
        canvas.translate((width - 120f * scale) / 2, (height - 58f * scale) / 2)
        canvas.scale(scale, scale)
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
        }
        val key = sample.voiceColor(R.color.floating_key)
        val accent = sample.voiceColor(R.color.floating_accent)
        val modifier = sample.voiceColor(R.color.floating_modifier)
        rect(0f, 0f, 120f, 58f, 8f, sample.voiceColor(R.color.floating_shell))
        rect(8f, 6f, 24f, 9f, 2f, sample.voiceColor(R.color.floating_brand))
        rect(106f, 4f, 114f, 12f, 4f, accent)
        for (row in 0..2) {
            val y = 15f + 10 * row
            val x = 7f + row * 4
            for (col in 0 until 10 - row) {
                val l = x + col * 11
                rect(l, y, l + 9, y + 8, 2f, key)
            }
        }
        rect(8f, 45f, 27f, 53f, 2f, modifier)
        rect(30f, 45f, 91f, 53f, 2f, key)
        rect(94f, 45f, 112f, 53f, 2f, accent)
        canvas.restore()
    }
}

private fun View.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
}
