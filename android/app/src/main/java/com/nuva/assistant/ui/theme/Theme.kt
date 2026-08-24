package com.nuva.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val NuvaAccent = Color(0xFF7C5CFF)
private val NuvaGreen = Color(0xFF3DDC97)
private val NuvaAmber = Color(0xFFFFB454)
private val NuvaRed = Color(0xFFFF5C72)
private val DarkBackground = Color(0xFF0A0B0F)
private val DarkSurface = Color(0xFF14161D)

private val DarkColors = darkColorScheme(
    primary = NuvaAccent,
    secondary = NuvaGreen,
    tertiary = NuvaAmber,
    error = NuvaRed,
    background = DarkBackground,
    surface = DarkSurface,
)

private val LightColors = lightColorScheme(
    primary = NuvaAccent,
    secondary = NuvaGreen,
    tertiary = NuvaAmber,
    error = NuvaRed,
)

private val NuvaTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
)

@Composable
fun NuvaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color on 12+ is deliberately OFF: NUVA owns its brand voice.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NuvaTypography,
        content = content,
    )
}
