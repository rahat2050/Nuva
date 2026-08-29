package com.nuva.assistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** NUVA 3D visual language: violet intelligence, cyan voice and warm action light. */
val NuvaViolet = Color(0xFF8B7CFF)
val NuvaCyan = Color(0xFF39D6D0)
val NuvaAmber = Color(0xFFFFB86B)
val NuvaRose = Color(0xFFFF6F91)
val NuvaInk = Color(0xFF070A13)
val NuvaDeepSurface = Color(0xFF101525)
val NuvaRaisedSurface = Color(0xFF192138)

private val DarkColors = darkColorScheme(
    primary = NuvaViolet,
    onPrimary = Color(0xFF100B35),
    primaryContainer = Color(0xFF2A225F),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = NuvaCyan,
    onSecondary = Color(0xFF002F2E),
    secondaryContainer = Color(0xFF103E43),
    onSecondaryContainer = Color(0xFFB6F4F0),
    tertiary = NuvaAmber,
    onTertiary = Color(0xFF3F2500),
    tertiaryContainer = Color(0xFF553817),
    onTertiaryContainer = Color(0xFFFFDDB7),
    error = NuvaRose,
    errorContainer = Color(0xFF5C1B31),
    background = NuvaInk,
    onBackground = Color(0xFFF2F1FF),
    surface = NuvaDeepSurface,
    onSurface = Color(0xFFF2F1FF),
    surfaceVariant = NuvaRaisedSurface,
    onSurfaceVariant = Color(0xFFBFC5D9),
    outline = Color(0xFF66708E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6353D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF21185D),
    secondary = Color(0xFF007C78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F0EC),
    onSecondaryContainer = Color(0xFF003735),
    tertiary = Color(0xFF9B5D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB1),
    onTertiaryContainer = Color(0xFF321B00),
    error = Color(0xFFBA1A4B),
    background = Color(0xFFF4F6FF),
    onBackground = Color(0xFF171A26),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF171A26),
    surfaceVariant = Color(0xFFE6E8F3),
    onSurfaceVariant = Color(0xFF484C5D),
    outline = Color(0xFF777B8D),
)

private val NuvaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val NuvaTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 23.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun NuvaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // Dynamic color stays off so depth, contrast and action colors remain predictable.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NuvaTypography,
        shapes = NuvaShapes,
        content = content,
    )
}
