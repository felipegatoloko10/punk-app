package com.punk.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PunkAmoledBg = Color(0xFF080B10)
val PunkSurface = Color(0xFF11161E)
val PunkSurfaceVar = Color(0xFF1A212C)
val PunkSurfaceHigh = Color(0xFF222C39)
val PunkNeonCyan = Color(0xFF82DBE8)
val PunkNeonMagenta = Color(0xFFFF999D)
val PunkNeonGreen = Color(0xFF91D4B3)
val PunkWarning = Color(0xFFE9C78E)
val PunkNeonPurple = Color(0xFFB8B1E8)
val PunkTextPrimary = Color(0xFFEAF0F6)
val PunkTextSecondary = Color(0xFFA4AFBE)
val PunkTextMuted = Color(0xFF8B98A9)
val PunkBorderCyan = Color(0xFF2A424D)
val PunkBorderMagenta = Color(0xFF57393F)
val PunkBorderGreen = Color(0xFF2D4B40)
val PunkMonoFamily = FontFamily.Monospace

private val PunkColors = darkColorScheme(
    primary = PunkNeonCyan, onPrimary = Color(0xFF09272E),
    primaryContainer = Color(0xFF1B3741), onPrimaryContainer = Color(0xFFC5F3F9),
    secondary = Color(0xFFB6C8DC), onSecondary = Color(0xFF14202E),
    secondaryContainer = Color(0xFF263342), onSecondaryContainer = PunkTextPrimary,
    tertiary = PunkNeonGreen, onTertiary = Color(0xFF102C21),
    error = PunkNeonMagenta, onError = Color(0xFF3B1118),
    errorContainer = Color(0xFF362128), onErrorContainer = Color(0xFFFFDADC),
    background = PunkAmoledBg, onBackground = PunkTextPrimary,
    surface = PunkSurface, onSurface = PunkTextPrimary,
    surfaceVariant = PunkSurfaceVar, onSurfaceVariant = PunkTextSecondary,
    outline = Color(0xFF677587), outlineVariant = Color(0xFF293341),
    surfaceTint = PunkNeonCyan, scrim = Color.Black
)

private val PunkTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun PunkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PunkColors,
        typography = PunkTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        content = content
    )
}
