package com.verivid.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Shared visual tokens. All UI colors are explicit so no default purple tint leaks in.
val Navy = Color(0xFF20395C)
val NavyDark = Color(0xFF162942)
val Accent = Color(0xFF6267CD)
val NavySoft = Color(0xFFF1F1FD)
val AppBackground = Color.White
val SurfaceSoft = Color(0xFFF7F8FA)
val Line = Color(0xFFEBEDF2)
val Ink = Color(0xFF191F28)
val Muted = Color(0xFF687182)
val Quiet = Color(0xFF8993A1)
val Success = Color(0xFF137553)
val SuccessSoft = Color(0xFFF0F9F4)
val Warning = Color(0xFF8A5900)
val WarningSoft = Color(0xFFFFF9EC)
val Danger = Color(0xFFBE3944)
val DangerSoft = Color(0xFFFFF4F4)

val LocalLargeView = staticCompositionLocalOf { false }

private val colors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = NavySoft,
    onPrimaryContainer = Navy,
    secondary = Navy,
    onSecondary = Color.White,
    secondaryContainer = NavySoft,
    onSecondaryContainer = Navy,
    tertiary = Accent,
    tertiaryContainer = NavySoft,
    onTertiaryContainer = Navy,
    background = AppBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = Muted,
    surfaceTint = Color.Transparent,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    errorContainer = DangerSoft
)

private fun textStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = (-0.3).sp,
    lineBreak = LineBreak.Heading
)

fun veriVidTypography(large: Boolean = false) = Typography(
    headlineLarge = textStyle(if (large) 34 else 25, if (large) 45 else 33, if (large) FontWeight.Bold else FontWeight.SemiBold),
    headlineMedium = textStyle(if (large) 30 else 22, if (large) 40 else 30, if (large) FontWeight.Bold else FontWeight.SemiBold),
    headlineSmall = textStyle(if (large) 27 else 20, if (large) 37 else 28, FontWeight.SemiBold),
    titleLarge = textStyle(if (large) 24 else 17, if (large) 34 else 25, FontWeight.SemiBold),
    titleMedium = textStyle(if (large) 21 else 15, if (large) 30 else 22, FontWeight.SemiBold),
    titleSmall = textStyle(if (large) 19 else 14, if (large) 28 else 21, FontWeight.SemiBold),
    bodyLarge = textStyle(if (large) 20 else 15, if (large) 31 else 23),
    bodyMedium = textStyle(if (large) 19 else 14, if (large) 29 else 21),
    bodySmall = textStyle(if (large) 17 else 12, if (large) 26 else 18),
    labelLarge = textStyle(if (large) 19 else 15, if (large) 28 else 22, FontWeight.SemiBold),
    labelMedium = textStyle(13, 20, FontWeight.Medium),
    labelSmall = textStyle(12, 18, FontWeight.Medium)
)

// Keep the accessible layout unchanged; only normal mode uses a denser layout.
@Composable
internal fun uiSpace(normal: Int, large: Int) = (if (LocalLargeView.current) large else normal).dp

@Composable
internal fun screenPadding(normalTop: Int = 0, easyTop: Int = normalTop): androidx.compose.foundation.layout.PaddingValues {
    val horizontal = uiSpace(20, 24)
    return androidx.compose.foundation.layout.PaddingValues(horizontal, uiSpace(normalTop, easyTop), horizontal, uiSpace(20, 28))
}

@Composable
fun VeriVidTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = Color.White.toArgb()
                window.navigationBarColor = Color.White.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = veriVidTypography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
