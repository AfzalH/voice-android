package com.srizonvoice.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Coral = Color(0xFFFF6F61)
private val Purple = Color(0xFFA26BFA)
private val Blue = Color(0xFF4D8BFA)
private val Dark = Color(0xFF0E0E12)
private val Surface = Color(0xFFFFF8F4)

private val LightScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Coral,
    onSecondary = Color.White,
    tertiary = Blue,
    background = Surface,
    surface = Surface,
)

private val DarkScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Coral,
    onSecondary = Color.White,
    tertiary = Blue,
    background = Dark,
    surface = Color(0xFF1A1A22),
)

@Composable
fun SrizonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
