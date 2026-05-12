package com.srizonvoice.android.trigger.bubble

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.srizonvoice.android.R
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.recording.RecordingState
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Custom view-based bubble.
 *
 *  - **Idle / Error.** Rounded-square white tile with the SrizonVoice waveform
 *    logo (matches the launcher icon).
 *  - **Recording / Transcribing in handsfree.** Inline settings, cancel, live
 *    waveform/spinner, optional translate, and done actions. Glyphs are official
 *    Material Symbols rendered as vector drawables, tinted on the fly. Settings
 *    opens the Settings screen so the user can tweak opacity and output behavior
 *    without leaving the bubble.
 *  - **Recording / Transcribing in push-to-talk.** Single gradient pill with a
 *    full white waveform / spinner. PTT users release-to-stop, so explicit
 *    buttons aren't needed.
 */
class BubbleView(context: Context) : View(context) {

    val dragState = DragState()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val cancelTint = Color.argb(180, 0xE5, 0x3E, 0x3E)
    private val errorColor = 0xFFB22222.toInt()
    private val shadowColor = 0x40000000

    private val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.srizon_logo)
    private val settingsIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_settings)
    private val closeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_close)
    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_check)
    private val translateIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_translate)

    private val handler = Handler(Looper.getMainLooper())
    private var state: RecordingState = RecordingState.Idle
    private var mode: RecordingMode = RecordingMode.HANDSFREE
    private var translateButtonShown: Boolean = false
    private var cancelHover: Boolean = false
    private var errorUntilNanos: Long = 0L
    private val barHeights = FloatArray(MAX_BAR_COUNT) { MIN_BAR }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadowLayer requires software rendering
        layoutParams = ViewGroup_LayoutParams(WRAP, WRAP)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = currentWideOrIdleWidth()
        setMeasuredDimension(width, PILL_PX)
    }

    fun activeWidth(): Int = if (showTranslateInBubble()) WIDE_PX_WITH_TRANSLATE else WIDE_PX

    /** Width swing between idle and active state — used by the X-shift logic. */
    fun widthDelta(): Int = activeWidth() - PILL_PX

    private fun currentWideOrIdleWidth(): Int =
        if (state is RecordingState.Recording || state is RecordingState.Transcribing) activeWidth() else PILL_PX

    private fun showTranslateInBubble(): Boolean =
        translateButtonShown && mode == RecordingMode.HANDSFREE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    fun render(newState: RecordingState) {
        state = newState
        if (newState is RecordingState.Recording) {
            for (i in barHeights.indices) {
                val noise = Random.nextFloat() * 0.25f
                val target = (newState.visualLevel + noise).coerceIn(MIN_BAR, 1f)
                barHeights[i] = barHeights[i] * 0.8f + target * 0.2f
            }
        }
        requestLayout()
        invalidate()
    }

    fun setRecordingMode(newMode: RecordingMode) {
        if (mode != newMode) {
            mode = newMode
            invalidate()
        }
    }

    fun setTranslateButtonShown(shown: Boolean) {
        if (translateButtonShown != shown) {
            translateButtonShown = shown
            requestLayout()
            invalidate()
        }
    }

    fun flashError(@Suppress("UNUSED_PARAMETER") message: String) {
        errorUntilNanos = System.nanoTime() + ERROR_FLASH_NS
        invalidate()
    }

    fun setCancelHover(hovering: Boolean) {
        if (cancelHover != hovering) {
            cancelHover = hovering
            invalidate()
        }
    }

    /** True if the bubble is rendering inline action buttons (handsfree wide). */
    fun hasInlineButtons(): Boolean =
        mode == RecordingMode.HANDSFREE &&
            (state is RecordingState.Recording || state is RecordingState.Transcribing)

    /** Returns which on-bubble button (if any) the touch x-coordinate falls on,
     *  or [TouchZone.MIDDLE] if it's on the waveform pill or the bubble is idle. */
    fun touchZoneAt(x: Float): TouchZone {
        if (!hasInlineButtons()) return TouchZone.MIDDLE
        val w = width
        val btn = currentButtonPx()
        val gap = currentGapPx()
        val settingsRight = SHADOW_PAD + btn + gap / 2
        val cancelRight = settingsRight + btn + gap
        val doneLeft = w - SHADOW_PAD - btn - gap / 2
        if (showTranslateInBubble()) {
            val translateLeft = doneLeft - btn - gap
            return when {
                x < settingsRight -> TouchZone.SETTINGS
                x < cancelRight -> TouchZone.CANCEL
                x > doneLeft -> TouchZone.DONE
                x > translateLeft -> TouchZone.TRANSLATE
                else -> TouchZone.MIDDLE
            }
        }
        return when {
            x < settingsRight -> TouchZone.SETTINGS
            x < cancelRight -> TouchZone.CANCEL
            x > doneLeft -> TouchZone.DONE
            else -> TouchZone.MIDDLE
        }
    }

    private fun currentButtonPx(): Float =
        if (showTranslateInBubble()) HANDSFREE_BUTTON_TRANS_PX else HANDSFREE_BUTTON_PX

    private fun currentGapPx(): Float =
        if (showTranslateInBubble()) HANDSFREE_GAP_TRANS_PX else HANDSFREE_GAP_PX

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val flashing = System.nanoTime() < errorUntilNanos
        val isWide = state is RecordingState.Recording || state is RecordingState.Transcribing

        when {
            isWide && mode == RecordingMode.HANDSFREE -> drawHandsfreeWideUI(canvas, w, h, flashing)
            isWide -> drawClassicWideUI(canvas, w, h, flashing)
            else -> drawIdleUI(canvas, w, h, flashing)
        }
    }

    // ── Idle ───────────────────────────────────────────────────────────────

    private fun drawIdleUI(canvas: Canvas, w: Float, h: Float, flashing: Boolean) {
        paint.shader = null
        paint.color = when {
            cancelHover -> cancelTint
            flashing -> errorColor
            else -> Color.WHITE
        }
        val radius = h * CORNER_RADIUS_FRACTION
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawRoundRect(RectF(SHADOW_PAD, SHADOW_PAD, w - SHADOW_PAD, h - SHADOW_PAD), radius, radius, paint)
        paint.clearShadowLayer()
        drawLogo(canvas, w, h)
    }

    private fun drawLogo(canvas: Canvas, w: Float, h: Float) {
        val pad = h * 0.18f
        val left = (w - h) / 2f + pad
        val top = pad
        val size = h - 2 * pad
        val dst = RectF(left, top, left + size, top + size)
        canvas.drawBitmap(logoBitmap, null, dst, null)
    }

    // ── Handsfree wide: ⚙ ✕ │ wave │ ✓ ─────────────────────────────────────

    private fun drawHandsfreeWideUI(canvas: Canvas, w: Float, h: Float, flashing: Boolean) {
        val tinted = cancelHover || flashing
        val showTranslate = showTranslateInBubble()
        val btn = currentButtonPx()
        val gap = currentGapPx()
        val buttonR = btn / 2f
        val cy = h / 2f
        val settingsCx = SHADOW_PAD + buttonR
        val cancelCx = settingsCx + btn + gap
        val doneCx = w - SHADOW_PAD - buttonR
        val translateCx = if (showTranslate) doneCx - btn - gap else 0f
        val middleLeft = cancelCx + buttonR + gap
        val middleRight =
            if (showTranslate) translateCx - buttonR - gap else doneCx - buttonR - gap

        paint.shader = null
        paint.style = Paint.Style.FILL

        // Settings circle (light lavender).
        paint.color = if (tinted) cancelTint else LIGHT_BG
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawCircle(settingsCx, cy, buttonR, paint)
        paint.clearShadowLayer()

        // Cancel circle (light lavender).
        paint.color = if (tinted) cancelTint else LIGHT_BG
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawCircle(cancelCx, cy, buttonR, paint)
        paint.clearShadowLayer()

        // Middle pill (light lavender).
        paint.color = if (tinted) cancelTint else LIGHT_BG
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawRoundRect(middleLeft, cy - buttonR, middleRight, cy + buttonR, buttonR, buttonR, paint)
        paint.clearShadowLayer()

        // Translate circle (brand purple), only when enabled in settings.
        if (showTranslate) {
            paint.color = if (tinted) cancelTint else DONE_PURPLE
            paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
            canvas.drawCircle(translateCx, cy, buttonR, paint)
            paint.clearShadowLayer()
        }

        // Done circle (brand purple).
        paint.color = if (tinted) cancelTint else DONE_PURPLE
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawCircle(doneCx, cy, buttonR, paint)
        paint.clearShadowLayer()

        // Glyphs — Material Symbols vector drawables, tinted.
        val glyphTint = if (tinted) Color.WHITE else DARK_GLYPH
        val glyphHalfSize = buttonR * GLYPH_SIZE_FRACTION
        drawDrawable(canvas, settingsIcon, settingsCx, cy, glyphHalfSize, glyphTint)
        drawDrawable(canvas, closeIcon, cancelCx, cy, glyphHalfSize, glyphTint)
        if (showTranslate) {
            drawDrawable(canvas, translateIcon, translateCx, cy, glyphHalfSize, Color.WHITE)
        }
        drawDrawable(canvas, checkIcon, doneCx, cy, glyphHalfSize, Color.WHITE)

        // Middle pill content.
        when (state) {
            is RecordingState.Recording -> drawHandsfreeBars(canvas, middleLeft, middleRight, cy, buttonR, glyphTint)
            is RecordingState.Transcribing -> {
                drawHandsfreeSpinner(canvas, (middleLeft + middleRight) / 2f, cy, buttonR * 0.55f, glyphTint)
                handler.postDelayed({ invalidate() }, 16L)
            }
            else -> {}
        }
    }

    private fun drawHandsfreeBars(
        canvas: Canvas,
        left: Float,
        right: Float,
        cy: Float,
        buttonR: Float,
        color: Int,
    ) {
        val avail = right - left - 2 * BAR_INNER_PADDING
        if (avail <= 0f) return
        val totalSpacing = (HANDSFREE_BAR_COUNT - 1) * HANDSFREE_BAR_SPACING_PX
        val barWidth = max(2f, (avail - totalSpacing) / HANDSFREE_BAR_COUNT)
        // Bars can grow up to ~95% of the button's diameter, leaving a 5% margin
        // top and bottom inside the middle pill. Combined with the level boost
        // below, regular speech now noticeably oscillates the bars instead of
        // hovering near the minimum height.
        val maxBarHeight = buttonR * 1.9f
        var x = left + BAR_INNER_PADDING + barWidth / 2f
        for (i in 0 until HANDSFREE_BAR_COUNT) {
            val raw = max(MIN_BAR, barHeights[i])
            // The macOS-ported `clamp(rms*6, 0.02, 1)` curve maps speech to ~0.3-0.6.
            // Stretching that range to ~0.5-1.0 makes the visualization feel alive
            // without losing the silent-vs-speaking distinction.
            val boosted = (raw * HANDSFREE_BAR_AMPLIFY).coerceIn(MIN_BAR, 1f)
            val barHeight = max(barWidth, maxBarHeight * boosted)
            barPaint.strokeWidth = barWidth
            barPaint.color = color
            canvas.drawLine(x, cy - barHeight / 2f, x, cy + barHeight / 2f, barPaint)
            x += barWidth + HANDSFREE_BAR_SPACING_PX
        }
    }

    private fun drawHandsfreeSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val sweep = 240f
        val now = System.currentTimeMillis() % 1000L
        val start = (now / 1000f) * 360f
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Push-to-talk wide: gradient pill, full waveform / spinner ──────────

    private fun drawClassicWideUI(canvas: Canvas, w: Float, h: Float, flashing: Boolean) {
        paint.shader = null
        when {
            cancelHover -> paint.color = cancelTint
            flashing -> paint.color = errorColor
            else -> paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(0xFFFF6F61.toInt(), 0xFFA26BFA.toInt(), 0xFF4D8BFA.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        val radius = h * CORNER_RADIUS_FRACTION
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawRoundRect(RectF(SHADOW_PAD, SHADOW_PAD, w - SHADOW_PAD, h - SHADOW_PAD), radius, radius, paint)
        paint.shader = null
        paint.clearShadowLayer()

        when (state) {
            is RecordingState.Recording -> drawClassicBars(canvas, w, h)
            is RecordingState.Transcribing -> drawClassicSpinner(canvas, w, h)
            else -> {}
        }
    }

    private fun drawClassicBars(canvas: Canvas, w: Float, h: Float) {
        val padding = h * 0.25f
        val totalSpacing = (CLASSIC_BAR_COUNT - 1) * CLASSIC_BAR_SPACING_PX
        val barWidth = max(2f, (w - 2 * padding - totalSpacing) / CLASSIC_BAR_COUNT)
        val cy = h / 2f
        for (i in 0 until CLASSIC_BAR_COUNT) {
            val level = max(MIN_BAR, barHeights[i])
            val barHeight = (h - 2 * padding) * level
            val x = padding + i * (barWidth + CLASSIC_BAR_SPACING_PX) + barWidth / 2f
            barPaint.strokeWidth = barWidth
            barPaint.color = Color.WHITE
            canvas.drawLine(x, cy - barHeight / 2f, x, cy + barHeight / 2f, barPaint)
        }
    }

    private fun drawClassicSpinner(canvas: Canvas, w: Float, h: Float) {
        val sweep = 240f
        val now = System.currentTimeMillis() % 1000L
        val start = (now / 1000f) * 360f
        val pad = h * 0.25f
        val rect = RectF(w / 2 - h / 2 + pad, pad, w / 2 + h / 2 - pad, h - pad)
        paint.shader = null
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawArc(rect, start, sweep, false, paint)
        paint.style = Paint.Style.FILL
        if (state is RecordingState.Transcribing) {
            handler.postDelayed({ invalidate() }, 16L)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun drawDrawable(
        canvas: Canvas,
        drawable: Drawable?,
        cx: Float,
        cy: Float,
        halfSize: Float,
        tint: Int,
    ) {
        drawable ?: return
        // Compute the box size from the half-size first, then round, so all three
        // glyphs end up with the *exact same* bounding box dimensions regardless
        // of where their center happens to land. Without this, integer truncation
        // on `(cx - halfSize)` and `(cx + halfSize)` independently can produce a
        // box that's one pixel narrower or shorter than its neighbor.
        val size = (halfSize * 2f).roundToInt()
        val left = (cx - halfSize).roundToInt()
        val top = (cy - halfSize).roundToInt()
        drawable.setBounds(left, top, left + size, top + size)
        DrawableCompat.setTint(drawable, tint)
        drawable.draw(canvas)
    }

    @Suppress("FunctionName")
    private fun ViewGroup_LayoutParams(width: Int, height: Int) =
        android.view.ViewGroup.LayoutParams(width, height)

    data class DragState(
        var startX: Int = 0,
        var startY: Int = 0,
        var touchX: Float = 0f,
        var touchY: Float = 0f,
    )

    enum class TouchZone { SETTINGS, CANCEL, MIDDLE, TRANSLATE, DONE }

    companion object {
        // 20% smaller than the original 168 → 134.
        const val PILL_PX = 134
        // Wide enough for the standard 4-button handsfree layout (settings, cancel,
        // middle, done). The bubble swells further to [WIDE_PX_WITH_TRANSLATE] when
        // the translate button is also shown.
        const val WIDE_PX = 500
        const val WIDE_PX_WITH_TRANSLATE = 600

        // Four-element layout — handsfree only.
        private const val HANDSFREE_BUTTON_PX = 100f
        private const val HANDSFREE_GAP_PX = 24f
        // Five-element layout — slightly smaller buttons + tighter gaps to fit
        // settings + cancel + middle + translate + done in [WIDE_PX_WITH_TRANSLATE].
        private const val HANDSFREE_BUTTON_TRANS_PX = 84f
        private const val HANDSFREE_GAP_TRANS_PX = 18f

        private const val HANDSFREE_BAR_COUNT = 12
        private const val HANDSFREE_BAR_SPACING_PX = 6f
        private const val HANDSFREE_BAR_AMPLIFY = 1.6f
        private const val BAR_INNER_PADDING = 14f

        // Single-pill PTT layout.
        private const val CLASSIC_BAR_COUNT = 30
        private const val CLASSIC_BAR_SPACING_PX = 4f

        // Shared waveform animation — buffer holds enough for either bar count.
        private const val MAX_BAR_COUNT = 30
        private const val MIN_BAR = 0.08f

        private const val CORNER_RADIUS_FRACTION = 0.32f
        // 50% bigger by area than 0.58 → 0.58 × √1.5 ≈ 0.71. Glyph occupies ~71%
        // of the button radius on each side, i.e. the icon fills about 71% of the
        // button's diameter.
        private const val GLYPH_SIZE_FRACTION = 0.71f
        private const val ERROR_FLASH_NS = 1_500_000_000L
        private const val SHADOW_RADIUS = 6f
        private const val SHADOW_PAD = 4f

        private const val LIGHT_BG = 0xFFEFE5F4.toInt()      // light lavender
        private const val DARK_GLYPH = 0xFF555960.toInt()    // dark gray
        private const val DONE_PURPLE = 0xFFA26BFA.toInt()   // brand purple

        private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
