package com.srizonvoice.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush as ComposeBrush

/**
 * Coral → Purple → Blue gradient ported from macOS SrizonVoice's recording island.
 * Reused for waveform bars, the bubble, and accent strokes.
 *
 * The `Brush as ComposeBrush` alias avoids shadowing inside the [Brush] property
 * getter — without it, `Brush.linearGradient(...)` would resolve to *this* property
 * rather than the Compose `Brush` companion.
 */
object GradientPalette {
    val Coral = Color(0xFFFF6F61)
    val Purple = Color(0xFFA26BFA)
    val Blue = Color(0xFF4D8BFA)

    private val gradientColors = listOf(Coral, Purple, Blue)

    val Brush: ComposeBrush = ComposeBrush.linearGradient(gradientColors)
    val VerticalBrush: ComposeBrush = ComposeBrush.verticalGradient(gradientColors)
}
