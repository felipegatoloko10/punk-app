package com.punk.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun PunkOrb(state: HudState, amplitude: Float = 0f, modifier: Modifier = Modifier.size(160.dp)){
    val inf = rememberInfiniteTransition(label="orb")
    val rot by inf.animateFloat(0f,360f, infiniteRepeatable(tween(4000, easing=LinearEasing)), label="rot")
    val pulse by inf.animateFloat(0.92f,1.08f, infiniteRepeatable(tween(900, easing=FastOutSlowInEasing), RepeatMode.Reverse), label="pulse")
    val baseColor = when(state){
        HudState.IDLE -> Color(0xFF00E5FF)
        HudState.LISTENING -> Color(0xFF00FFAB)
        HudState.THINKING -> Color(0xFF9C27FF)
        HudState.SPEAKING -> Color(0xFFFF2E97)
        HudState.TOOL -> Color(0xFFFFC400)
        HudState.ALERT -> Color(0xFFFF3D00)
    }
    Canvas(modifier){
        val c = Offset(size.width/2, size.height/2)
        val r = (size.minDimension/2.6f) * pulse * (1f + amplitude*0.6f)
        // glow
        drawCircle(Brush.radialGradient(listOf(baseColor.copy(alpha=0.35f), Color.Transparent), center=c, radius=r*1.8f), radius=r*1.8f, center=c)
        // core
        drawCircle(Brush.radialGradient(listOf(Color.White, baseColor, Color(0xFF0A0A14)), center=c, radius=r), radius=r, center=c)
        // rings
        val stroke = Stroke(width=2.5f)
        drawCircle(color=baseColor.copy(alpha=0.9f), radius=r*1.22f, center=c, style=stroke)
        drawCircle(color=baseColor.copy(alpha=0.5f), radius=r*1.45f, center=c, style=stroke)
        // orbit dots
        val dots = 3
        for(i in 0 until dots){
            val ang = Math.toRadians((rot + i*120).toDouble())
            val rr = r*1.32f
            val x = c.x + (rr * kotlin.math.cos(ang)).toFloat()
            val y = c.y + (rr * kotlin.math.sin(ang)).toFloat()
            drawCircle(color=Color.White, radius=4f, center=Offset(x,y))
        }
    }
}
