package com.punk.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class PunkGlyph { Chat, Devices, Activity, Settings, Mic, Plus, Back, Close, History, Pc, Phone, Shield, Stop, Chevron, Check, Alert, Camera, Location, Notification, Link, Copy, Eye }

@Composable
fun PunkIcon(glyph: PunkGlyph, description: String? = null, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round)
            fun line(x:Float,y:Float,xx:Float,yy:Float) = drawLine(tint, Offset(x,y),Offset(xx,yy),1.65f,StrokeCap.Round)
            fun round(x:Float,y:Float,w:Float,h:Float,r:Float=2f) = drawRoundRect(tint,Offset(x,y),Size(w,h),CornerRadius(r,r),style=stroke)
            fun path(vararg points:Float) { val p=Path();p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1]);drawPath(p,tint,style=stroke) }
            when(glyph) {
                PunkGlyph.Chat -> { round(3f,3f,18f,15f,4f);path(8f,18f,5f,21f,5f,17f);line(7f,8f,17f,8f);line(7f,12f,13f,12f) }
                PunkGlyph.Devices -> { round(2f,3f,15f,12f);line(5f,19f,12f,19f);line(9f,15f,9f,19f);round(16f,9f,6f,12f,1.5f) }
                PunkGlyph.Activity -> { path(3f,13f,7f,13f,10f,5f,14f,20f,17f,11f,21f,11f) }
                PunkGlyph.Settings -> { drawCircle(tint,8f,Offset(12f,12f),style=stroke);drawCircle(tint,3f,Offset(12f,12f),style=stroke);line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f) }
                PunkGlyph.Mic -> { round(9f,3f,6f,12f,3f);drawArc(tint,0f,180f,false,Offset(6f,7f),Size(12f,12f),style=stroke);line(12f,19f,12f,22f);line(8f,22f,16f,22f) }
                PunkGlyph.Plus -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
                PunkGlyph.Back -> { path(14f,5f,7f,12f,14f,19f) }
                PunkGlyph.Chevron -> { path(9f,6f,15f,12f,9f,18f) }
                PunkGlyph.Close -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
                PunkGlyph.History -> { drawArc(tint,-140f,285f,false,Offset(4f,4f),Size(16f,16f),style=stroke);path(3f,3f,3f,9f,8f,9f);path(12f,7f,12f,12f,15f,14f) }
                PunkGlyph.Pc -> { round(3f,3f,18f,13f);line(12f,16f,12f,21f);line(7f,21f,17f,21f) }
                PunkGlyph.Phone -> { round(6f,2f,12f,20f,3f);line(10f,5f,14f,5f);line(11f,19f,13f,19f) }
                PunkGlyph.Shield -> { path(12f,2f,20f,5f,19f,14f,16f,19f,12f,22f,8f,19f,5f,14f,4f,5f,12f,2f);path(8f,11f,11f,14f,16f,9f) }
                PunkGlyph.Stop -> { round(5f,5f,14f,14f,3f) }
                PunkGlyph.Check -> { path(5f,12f,10f,17f,19f,7f) }
                PunkGlyph.Alert -> { path(12f,3f,22f,20f,2f,20f,12f,3f);line(12f,9f,12f,13f);drawCircle(tint,0.8f,Offset(12f,17f)) }
                PunkGlyph.Camera -> { round(3f,6f,18f,15f);path(7f,6f,9f,3f,15f,3f,17f,6f);drawCircle(tint,4f,Offset(12f,13f),style=stroke) }
                PunkGlyph.Location -> { drawCircle(tint,3f,Offset(12f,9f),style=stroke);path(12f,22f,5f,13f,4f,8f,6f,4f,10f,2f,14f,2f,18f,4f,20f,8f,19f,13f,12f,22f) }
                PunkGlyph.Notification -> { path(4f,17f,6f,14f,6f,8f,8f,4f,12f,3f,16f,4f,18f,8f,18f,14f,20f,17f,4f,17f);drawArc(tint,0f,180f,false,Offset(9f,18f),Size(6f,4f),style=stroke) }
                PunkGlyph.Link -> { round(3f,5f,11f,7f,3.5f);round(10f,12f,11f,7f,3.5f);line(9f,9f,15f,15f) }
                PunkGlyph.Copy -> { round(8f,7f,12f,14f);path(16f,7f,16f,3f,4f,3f,4f,17f,8f,17f) }
                PunkGlyph.Eye -> { drawOval(tint,Offset(2f,6f),Size(20f,12f),style=stroke);drawCircle(tint,3f,Offset(12f,12f),style=stroke) }
            }
        }
    }
}

@Composable
fun PunkMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val s=size.minDimension
        val p=Path().apply { moveTo(s*.26f,s*.73f);lineTo(s*.26f,s*.26f);lineTo(s*.57f,s*.26f);lineTo(s*.76f,s*.43f);lineTo(s*.57f,s*.60f);lineTo(s*.41f,s*.60f) }
        drawPath(p,PunkNeonCyan,style=Stroke(s*.075f,cap=StrokeCap.Round))
        drawCircle(PunkNeonCyan,s*.035f,Offset(s*.74f,s*.73f))
    }
}

@Composable
fun SectionHeading(eyebrow: String, title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(eyebrow.uppercase(),style=MaterialTheme.typography.labelSmall,color=PunkNeonCyan)
            Text(title,style=MaterialTheme.typography.headlineMedium)
            if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
fun PunkPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=PunkSurface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}

@Composable
fun StatePill(text:String, active:Boolean, modifier:Modifier=Modifier) {
    Row(modifier.background(if(active)Color(0xFF173329) else PunkSurfaceVar,RoundedCornerShape(20.dp)).padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(if(active)PunkNeonGreen else PunkTextMuted,CircleShape))
        Text(text,style=MaterialTheme.typography.labelSmall,color=if(active)PunkNeonGreen else PunkTextSecondary)
    }
}
