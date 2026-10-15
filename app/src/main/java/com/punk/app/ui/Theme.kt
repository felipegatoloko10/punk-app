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

// Cyberpunk AMOLED palette - Sir Felipe spec
val PunkAmoledBg      = Color(0xFF050508) // fundo ultra-escuro AMOLED
val PunkSurface       = Color(0xFF0E0E14)
val PunkSurfaceVar    = Color(0xFF1A1A24)
val PunkSurfaceHigh   = Color(0xFF1E1E2E)
val PunkNeonCyan      = Color(0xFF00F0FF) // ciano neon
val PunkNeonMagenta   = Color(0xFFFF003C) // magenta/pink neon
val PunkNeonGreen     = Color(0xFF00FF66) // verde matrix
val PunkNeonPurple    = Color(0xFF9D00FF)
val PunkTextPrimary   = Color(0xFFE6F1FF)
val PunkTextSecondary = Color(0xFF8A9AB5)
val PunkTextMuted     = Color(0xFF5A6A85)
val PunkBorderCyan    = Color(0x3300F0FF)
val PunkBorderMagenta = Color(0x33FF003C)
val PunkBorderGreen   = Color(0x3300FF66)

private val PunkDarkScheme = darkColorScheme(
    primary = PunkNeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00373A),
    onPrimaryContainer = PunkNeonCyan,
    secondary = PunkNeonMagenta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A0014),
    onSecondaryContainer = PunkNeonMagenta,
    tertiary = PunkNeonGreen,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF003314),
    onTertiaryContainer = PunkNeonGreen,
    error = PunkNeonMagenta,
    onError = Color.White,
    background = PunkAmoledBg,
    onBackground = PunkTextPrimary,
    surface = PunkSurface,
    onSurface = PunkTextPrimary,
    surfaceVariant = PunkSurfaceVar,
    onSurfaceVariant = PunkTextSecondary,
    outline = Color(0xFF2A3548),
    outlineVariant = Color(0xFF1E2A3A),
    scrim = Color.Black,
    surfaceTint = PunkNeonCyan
)

// Monospaced for logs / terminal
val PunkMonoFamily = FontFamily.Monospace

private val PunkTypography = Typography(
    displayLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 1.2.sp, color = PunkTextPrimary),
    titleLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.8.sp, color = PunkTextPrimary),
    titleMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.5.sp, color = PunkNeonCyan),
    labelLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.2.sp, color = PunkTextPrimary),
    labelMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.0.sp, color = PunkTextSecondary),
    bodyLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = PunkTextPrimary),
    bodyMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = PunkTextSecondary),
    bodySmall     = TextStyle(fontFamily = PunkMonoFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, color = PunkTextMuted),
    labelSmall    = TextStyle(fontFamily = PunkMonoFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.0.sp, color = PunkNeonCyan)
)

private val PunkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun PunkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PunkDarkScheme,
        typography = PunkTypography,
        shapes = PunkShapes,
        content = content
    )
}
