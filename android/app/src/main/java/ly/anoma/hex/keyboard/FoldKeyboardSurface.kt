package ly.anoma.hex.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import ly.anoma.hex.voiceColor
import ly.anoma.hex.R
import kotlin.math.roundToInt

internal enum class KeyboardMode(val title: Int) {
    Standard(R.string.mode_standard), Left(R.string.mode_left), Right(R.string.mode_right),
    Split(R.string.mode_split), Floating(R.string.mode_floating),
}

internal data class ScreenProfile(val inner: Boolean, val landscape: Boolean) {
    val display = if (inner) "inner" else "cover"
    val key = "$display." + if (landscape) "landscape" else "portrait"
    val alternateKey get() = copy(landscape = !landscape).key
    val defaultMode get() = if (inner) KeyboardMode.Split else KeyboardMode.Standard

    companion object {
        fun from(configuration: Configuration) = ScreenProfile(
            configuration.smallestScreenWidthDp >= 600,
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )

        fun from(view: View): ScreenProfile {
            val configuration = view.resources.configuration
            val mode = view.display?.mode ?: return from(configuration)
            return fromDisplay(mode.physicalWidth, mode.physicalHeight, view.resources.displayMetrics.density,
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        }

        fun fromDisplay(width: Int, height: Int, density: Float, landscape: Boolean) =
            ScreenProfile(minOf(width, height) / density >= 600, landscape)
    }
}

internal data class PanelSettings(
    val mode: KeyboardMode,
    val x: Float = 0.5f,
    val y: Float = 0.75f,
    val width: Float = 320f,
    val opacity: Float = 1f,
    val rowHeight: Float = 41f,
) {
    fun resize(horizontalDp: Float, verticalDp: Float, maxWidth: Float, maxRowHeight: Float, minWidth: Float = 255f) = copy(
        width = (width + horizontalDp).coerceIn(minWidth, maxOf(minWidth, minOf(maxWidth, 660f))),
        rowHeight = (rowHeight + verticalDp / 4).coerceIn(33f, maxRowHeight.coerceIn(33f, 72f)),
    )
}

internal class PanelPreferences(context: Context) {
    private val store = context.getSharedPreferences("keyboard-panels", Context.MODE_PRIVATE)

    fun read(profile: ScreenProfile): PanelSettings {
        val key = if (store.contains("${profile.key}.width")) profile.key else profile.alternateKey
        val modeName = store.getString("${profile.display}.mode", null)
            ?: store.getString("${profile.key}.mode", null)
            ?: store.getString("${profile.alternateKey}.mode", null)
        val mode = KeyboardMode.entries.firstOrNull { it.name == modeName } ?: profile.defaultMode
        return PanelSettings(mode,
            store.getFloat("$key.x", .5f), store.getFloat("$key.y", .75f),
            store.getFloat("$key.width", 320f), store.getFloat("$key.opacity", 1f), store.getFloat("$key.rowHeight", 41f))
    }

    fun write(profile: ScreenProfile, settings: PanelSettings) {
        val key = profile.key
        store.edit().putString("${profile.display}.mode", settings.mode.name)
            .putFloat("$key.x", settings.x).putFloat("$key.y", settings.y)
            .putFloat("$key.width", settings.width).putFloat("$key.opacity", settings.opacity)
            .putFloat("$key.rowHeight", settings.rowHeight).apply()
    }
}

/** Layout within the input method window. Only the visible panel consumes touches when floating. */
internal class FoldKeyboardSurface(context: Context, private val ime: HexKeyboard, val keyboard: KeyboardView) :
    FrameLayout(context) {
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val controls = LinearLayout(context)
    private var safe = android.graphics.Insets.NONE
    private var dockArmed = false
    private var dockProgress = 0f
    private var dockAnimation: ValueAnimator? = null
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomInset get() = if (floating) maxOf(safe.bottom, dp(56f)) else safe.bottom
    private var settings = ime.panelSettings
    val floating get() = settings.mode == KeyboardMode.Floating
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).roundToInt()

    init {
        val drag = FrameLayout(context).apply {
            contentDescription = context.getString(R.string.mode_move)
            isFocusable = true
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(context.voiceColor(R.color.floating_grip))
                    cornerRadius = dp(2f).toFloat()
                }
            }, LayoutParams(dp(34f), dp(4f), Gravity.CENTER))
        }
        val resize = control(R.drawable.ic_panel_resize, R.string.mode_resize).apply {
            setOnClickListener {
                val minimum = keyboard.minimumFloatingWidth()
                val expanded = maxOf(382f, minimum + 55f)
                update(settings.copy(width = if (panel.width / density >= (expanded + minimum) / 2) minimum else expanded))
            }
        }
        val dock = control(R.drawable.ic_panel_dock, R.string.mode_dock).apply {
            setOnClickListener { ime.chooseMode(KeyboardMode.Standard) }
        }
        controls.setPadding(dp(6f), 0, dp(6f), dp(2f))
        controls.addView(dock, LinearLayout.LayoutParams(dp(44f), -1))
        controls.addView(drag, LinearLayout.LayoutParams(0, -1, 1f))
        controls.addView(resize, LinearLayout.LayoutParams(dp(44f), -1))
        panel.addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        panel.addView(controls, LinearLayout.LayoutParams(-1, dp(28f)))
        addView(panel, LayoutParams(-1, -2))
        drag.setOnTouchListener(PointerDrag(dockable = true) { dx, dy ->
            val availableX = (width - safe.left - safe.right - panel.width).coerceAtLeast(1)
            val availableY = (height - safe.top - bottomInset - drawnHeight()).coerceAtLeast(1)
            settings.copy(x = (dragStart.x + dx / availableX).coerceIn(0f, 1f),
                y = (dragStart.y + dy / availableY).coerceIn(0f, 1f))
        })
        resize.setOnTouchListener(PointerDrag(changed = ::resizedPanel))
        setOnApplyWindowInsetsListener { _, insets ->
            val mask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                WindowInsets.Type.tappableElement() or WindowInsets.Type.mandatorySystemGestures()
            val root = insets.getInsetsIgnoringVisibility(mask)
            val display = context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(mask)
            val next = android.graphics.Insets.max(root, display)
            if (next != safe) { safe = next; requestLayout() }
            insets
        }
        apply(settings)
    }

    fun apply(value: PanelSettings) {
        settings = value
        if (!floating && dockArmed) animateDockPreview(false)
        controls.visibility = if (floating) VISIBLE else GONE
        val docked = context.voiceColor(R.color.keyboard)
        setBackgroundColor(if (floating) Color.TRANSPARENT else docked)
        panel.background = GradientDrawable().apply {
            setColor(if (floating) context.voiceColor(R.color.floating_shell) else docked)
            if (floating) {
                cornerRadius = dp(20f).toFloat()
                setStroke(dp(1f), context.voiceColor(R.color.floating_outline))
            }
        }
        panel.setPadding(0, if (floating) dp(2f) else 0, 0, 0)
        panel.clipToOutline = floating
        panel.elevation = if (floating) dp(7f).toFloat() else 0f
        panel.alpha = if (floating) settings.opacity else 1f
        keyboard.setFloatingRowHeight(settings.rowHeight)
        keyboard.applyMode(settings.mode)
        requestLayout()
    }

    private fun control(icon: Int, label: Int) = FrameLayout(context).apply {
        contentDescription = context.getString(label)
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(context.voiceColor(R.color.floating_pressed)), null,
            GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(Color.WHITE) })
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(context.voiceColor(R.color.quiet))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
    }

    private var dragStart = settings
    private val dragStartBounds = Rect()

    private fun resizedPanel(dx: Float, dy: Float): PanelSettings {
        val usableWidth = (width - safe.left - safe.right - dp(12f)).coerceAtLeast(1)
        val usableHeight = (height - safe.top - bottomInset).coerceAtLeast(1)
        val fixedHeight = dragStartBounds.height() - 4 * dp(dragStart.rowHeight)
        val resized = dragStart.resize(dx / density, dy / density, usableWidth / density,
            (usableHeight - fixedHeight) / (4 * density), keyboard.minimumFloatingWidth())
        val newWidth = dp(resized.width).coerceAtMost(usableWidth)
        val newHeight = fixedHeight + 4 * dp(resized.rowHeight)
        val spaceX = (width - safe.left - safe.right - newWidth).coerceAtLeast(1)
        val spaceY = (usableHeight - newHeight).coerceAtLeast(1)
        return resized.copy(
            x = ((dragStartBounds.left - safe.left).toFloat() / spaceX).coerceIn(0f, 1f),
            y = ((dragStartBounds.top - safe.top).toFloat() / spaceY).coerceIn(0f, 1f),
        )
    }
    private inner class PointerDrag(private val dockable: Boolean = false,
                                    private val changed: (Float, Float) -> PanelSettings) : View.OnTouchListener {
        private var x = 0f
        private var y = 0f
        private var moved = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX; y = event.rawY; moved = false
                    dragStart = if (dockable) settings else settings.copy(width = panel.width / density)
                    dragStartBounds.set(panel.left, panel.top, panel.right, panel.bottom)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - x
                    val dy = event.rawY - y
                    moved = moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(4f)
                    if (moved) {
                        if (dockable) animateDockPreview(dy > dp(4f) &&
                            dragStartBounds.bottom + dy >= height - bottomInset - dp(22f))
                        apply(changed(dx, dy))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dock = dockable && dockArmed
                    if (dockable) animateDockPreview(false)
                    if (dock) ime.chooseMode(KeyboardMode.Standard)
                    else if (moved) update(changed(event.rawX - x, event.rawY - y))
                    else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dockable) animateDockPreview(false)
                    apply(dragStart)
                }
            }
            return true
        }
    }

    private fun animateDockPreview(show: Boolean) {
        if (dockArmed == show) return
        dockArmed = show
        dockAnimation?.cancel()
        dockAnimation = ValueAnimator.ofFloat(dockProgress, if (show) 1f else 0f).apply {
            duration = if (show) 170 else 110
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dockProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (floating && dockProgress > 0f) drawDockPreview(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawDockPreview(canvas: Canvas) {
        val t = dockProgress
        val left = panel.left + (safe.left - panel.left) * t + dp(4f)
        val right = panel.right + (width - safe.right - panel.right) * t - dp(4f)
        val bottom = (height - bottomInset).toFloat()
        val top = bottom - minOf(panel.height, dp(230f)) * (.85f + .15f * t)
        val radius = dp(16f).toFloat()
        previewPaint.style = Paint.Style.FILL
        previewPaint.color = context.voiceColor(R.color.floating_shell)
        previewPaint.alpha = (132 * t).roundToInt()
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, previewPaint)
        previewPaint.style = Paint.Style.STROKE
        previewPaint.strokeWidth = dp(2f).toFloat()
        previewPaint.color = context.voiceColor(R.color.floating_brand)
        previewPaint.alpha = (218 * t).roundToInt()
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, previewPaint)
    }

    override fun onDetachedFromWindow() {
        dockAnimation?.cancel()
        dockArmed = false
        dockProgress = 0f
        super.onDetachedFromWindow()
    }

    private fun update(value: PanelSettings) { ime.updatePanel(value) }
    private fun drawnHeight() = panel.measuredHeight
    private fun availableHeight() = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: availableHeight()
        val usable = (width - safe.left - safe.right).coerceAtLeast(1)
        val panelWidth = when (settings.mode) {
            KeyboardMode.Left, KeyboardMode.Right -> (usable * .76f).roundToInt().coerceAtLeast(dp(275f)).coerceAtMost(usable)
            KeyboardMode.Floating -> dp(settings.width.coerceAtLeast(keyboard.minimumFloatingWidth()))
                .coerceIn(1, (usable - dp(12f)).coerceAtLeast(1))
            else -> width
        }
        panel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        val usedHeight = if (floating) availableHeight().coerceAtMost(maxHeight) else drawnHeight()
        setMeasuredDimension(width, usedHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val freeX = (width - safe.left - safe.right - panel.measuredWidth).coerceAtLeast(0)
        val freeY = (height - safe.top - bottomInset - drawnHeight()).coerceAtLeast(0)
        val x = when (settings.mode) {
            KeyboardMode.Left -> safe.left
            KeyboardMode.Right -> safe.left + freeX
            KeyboardMode.Floating -> safe.left + (freeX * settings.x).roundToInt()
            else -> 0
        }
        val y = if (floating) safe.top + (freeY * settings.y).roundToInt() else 0
        panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
    }

    fun touchBounds(out: Rect) {
        val location = IntArray(2)
        panel.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + panel.width, location[1] + drawnHeight())
    }
}
