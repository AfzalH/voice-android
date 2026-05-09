package com.srizonvoice.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 * 30-bar live waveform — port of macOS `How-it-works.md:229-240`.
 *
 * - Animation: each frame, every bar drifts 80% toward a target derived from the
 *   incoming [level] plus a small random component (matches macOS dampening + noise).
 * - Refresh: driven by [withFrameNanos], so it runs at the host window's vsync rate
 *   (60 Hz on most devices, ~30 Hz on lower-end ones — matches spec §7).
 * - Colors: coral → purple → blue gradient ([GradientPalette.Brush]) applied as a
 *   single horizontal brush across all bars so the gradient reads as one shape.
 */
@Composable
fun WaveformBars(
    level: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 30,
    barSpacing: androidx.compose.ui.unit.Dp = 3.dp,
    minBarFraction: Float = 0.06f,
    brush: Brush = GradientPalette.Brush,
) {
    val heights = remember(barCount) { FloatArray(barCount) { minBarFraction } }
    val currentLevel by rememberUpdatedState(level)
    var redrawTick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                for (i in heights.indices) {
                    val noise = Random.nextFloat() * 0.25f
                    val target = (currentLevel + noise).coerceIn(minBarFraction, 1f)
                    heights[i] = heights[i] * 0.8f + target * 0.2f
                }
                redrawTick = now
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_EXPRESSION") redrawTick // read so the draw scope re-runs every frame
        val total = size.width
        val spacing = barSpacing.toPx()
        val barWidth = max(1f, (total - spacing * (barCount - 1)) / barCount)
        val centerY = size.height / 2f
        for (i in 0 until barCount) {
            val h = max(minBarFraction, heights[i]) * size.height
            val x = i * (barWidth + spacing)
            drawLine(
                brush = brush,
                start = Offset(x + barWidth / 2f, centerY - h / 2f),
                end = Offset(x + barWidth / 2f, centerY + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
