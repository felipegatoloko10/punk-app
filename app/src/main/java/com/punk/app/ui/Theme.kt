package com.punk.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PunkDark = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFFFF2E97),
    tertiary = Color(0xFF9C27FF),
    background = Color(0xFF0A0E14),
    surface = Color(0xFF121821),
    surfaceVariant = Color(0xFF1E2A3A),
    onBackground = Color(0xFFE6F1FF),
    onSurface = Color(0xFFE6F1FF)
)

@Composable
fun PunkTheme(content: @Composable ()->Unit){
    MaterialTheme(colorScheme=PunkDark, typography=Typography(), content=content)
}
