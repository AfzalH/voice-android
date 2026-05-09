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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.srizonvoice.android.R
import com.srizonvoice.android.data.RecordingMode
import com.srizonvoice.android.recording.RecordingState
import kotlin.math.max
import kotlin.random.Random

/**
 * Custom view-based bubble.
 *
 *  - **Idle / Error.** Rounded-square white tile with the SrizonVoice waveform
 *    logo (matches the launcher icon).
 *  - **Recording / Transcribing in handsfree.** Three-element layout: a light
 *    lavender circle on the left (✕ to cancel), a light lavender pill in the
 *    middle (live waveform / spinner), and a solid purple circle on the right
 *    (✓ to stop & transcribe). Explicit affordances so the user doesn't have
 *    to guess that "tapping the bar stops it".
 *  - **Recording / Transcribing in push-to-talk.** Single gradient pill with a
 *    full white waveform / spinner. PTT users release-to-stop, so the explicit
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

    private val handler = Handler(Looper.getMainLooper())
    private var state: RecordingState = RecordingState.Idle
    private var mode: RecordingMode = RecordingMode.HANDSFREE
    private var cancelHover: Boolean = false
    private var errorUntilNanos: Long = 0L
    private val barHeights = FloatArray(MAX_BAR_COUNT) { MIN_BAR }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadowLayer requires software rendering
        layoutParams = ViewGroup_LayoutParams(WRAP, WRAP)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (state is RecordingState.Recording || state is RecordingState.Transcribing) WIDE_PX else PILL_PX
        setMeasuredDimension(width, PILL_PX)
    }

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

    /** True if the bubble is rendering inline Cancel / Done buttons (handsfree wide). */
    fun hasInlineButtons(): Boolean =
        mode == RecordingMode.HANDSFREE &&
            (state is RecordingState.Recording || state is RecordingState.Transcribing)

    /** Width (px) of the left/right tap zones when [hasInlineButtons] is true. */
    fun inlineButtonZoneWidth(): Float =
        HANDSFREE_BUTTON_PX + HANDSFREE_GAP_PX + 2 * SHADOW_PAD

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

    // ── Handsfree wide: cancel ○ │ ▭ wave ▭ │ ○ done ───────────────────────

    private fun drawHandsfreeWideUI(canvas: Canvas, w: Float, h: Float, flashing: Boolean) {
        val tinted = cancelHover || flashing
        val buttonR = HANDSFREE_BUTTON_PX / 2f
        val cy = h / 2f
        val cancelCx = SHADOW_PAD + buttonR
        val doneCx = w - SHADOW_PAD - buttonR
        val middleLeft = cancelCx + buttonR + HANDSFREE_GAP_PX
        val middleRight = doneCx - buttonR - HANDSFREE_GAP_PX

        paint.shader = null
        paint.style = Paint.Style.FILL

        // Cancel circle (left, light lavender).
        paint.color = if (tinted) cancelTint else LIGHT_BG
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawCircle(cancelCx, cy, buttonR, paint)
        paint.clearShadowLayer()

        // Middle pill (light lavender).
        paint.color = if (tinted) cancelTint else LIGHT_BG
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawRoundRect(middleLeft, cy - buttonR, middleRight, cy + buttonR, buttonR, buttonR, paint)
        paint.clearShadowLayer()

        // Done circle (right, brand purple).
        paint.color = if (tinted) cancelTint else DONE_PURPLE
        paint.setShadowLayer(SHADOW_RADIUS, 0f, 2f, shadowColor)
        canvas.drawCircle(doneCx, cy, buttonR, paint)
        paint.clearShadowLayer()

        // Foreground glyphs.
        val glyphColor = if (tinted) Color.WHITE else DARK_GLYPH
        drawCancelGlyph(canvas, cancelCx, cy, buttonR * GLYPH_SIZE_FRACTION, glyphColor)
        drawDoneGlyph(canvas, doneCx, cy, buttonR * GLYPH_SIZE_FRACTION, Color.WHITE)

        // Middle pill content.
        when (state) {
            is RecordingState.Recording -> drawHandsfreeBars(canvas, middleLeft, middleRight, cy, buttonR, glyphColor)
            is RecordingState.Transcribing -> {
                drawHandsfreeSpinner(canvas, (middleLeft + middleRight) / 2f, cy, buttonR * 0.55f, glyphColor)
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
        val maxBarHeight = buttonR * 1.4f
        var x = left + BAR_INNER_PADDING + barWidth / 2f
        for (i in 0 until HANDSFREE_BAR_COUNT) {
            val level = max(MIN_BAR, barHeights[i])
            val barHeight = max(barWidth, maxBarHeight * level)
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

    // ── Glyphs ─────────────────────────────────────────────────────────────

    private fun drawCancelGlyph(canvas: Canvas, cx: Float, cy: Float, halfSize: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = halfSize * 0.18f
        canvas.drawLine(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize, paint)
        canvas.drawLine(cx + halfSize, cy - halfSize, cx - halfSize, cy + halfSize, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDoneGlyph(canvas: Canvas, cx: Float, cy: Float, halfSize: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = halfSize * 0.20f
        canvas.drawLine(cx - halfSize, cy + halfSize * 0.05f, cx - halfSize / 4f, cy + halfSize / 1.8f, paint)
        canvas.drawLine(cx - halfSize / 4f, cy + halfSize / 1.8f, cx + halfSize, cy - halfSize / 1.8f, paint)
        paint.style = Paint.Style.FILL
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

    companion object {
        // 20% smaller than the original 168 → 134.
        const val PILL_PX = 134
        // Wider than the original 360 to give the three-element handsfree layout room
        // for a generous middle pill (~170px after subtracting both buttons + gaps).
        const val WIDE_PX = 400
        const val WIDTH_DELTA = WIDE_PX - PILL_PX

        // Three-element layout — handsfree only.
        private const val HANDSFREE_BUTTON_PX = 100f
        private const val HANDSFREE_GAP_PX = 12f
        private const val HANDSFREE_BAR_COUNT = 12
        private const val HANDSFREE_BAR_SPACING_PX = 6f
        private const val BAR_INNER_PADDING = 14f

        // Single-pill PTT layout.
        private const val CLASSIC_BAR_COUNT = 30
        private const val CLASSIC_BAR_SPACING_PX = 4f

        // Shared waveform animation — buffer holds enough for either bar count.
        private const val MAX_BAR_COUNT = 30
        private const val MIN_BAR = 0.08f

        private const val CORNER_RADIUS_FRACTION = 0.32f
        private const val GLYPH_SIZE_FRACTION = 0.42f
        private const val ERROR_FLASH_NS = 1_500_000_000L
        private const val SHADOW_RADIUS = 6f
        private const val SHADOW_PAD = 4f

        private const val LIGHT_BG = 0xFFEFE5F4.toInt()      // light lavender
        private const val DARK_GLYPH = 0xFF555960.toInt()    // dark gray
        private const val DONE_PURPLE = 0xFFA26BFA.toInt()   // brand purple

        private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
