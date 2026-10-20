package com.punk.app.ui

import com.punk.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.punk.chat.ChatViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(vm:ChatViewModel,onSettings:()->Unit) {
    val events by vm.activity.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while(true) { delay(30_000); value=System.currentTimeMillis() }
    }
    var filter by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    fun isPhone(device:String)=device.lowercase().let { it.contains("phone") || it.contains("celular") || it.contains("android") || it.contains("mobile") }
    val visible=events.filter { filter==0 || (filter==1 && !isPhone(it.device)) || (filter==2 && isPhone(it.device)) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { SectionHeading(stringResource(R.string.punk_activity_eyebrow),stringResource(R.string.punk_nav_activity),stringResource(R.string.punk_activity_subtitle)) { IconButton(onClick=onSettings) { PunkIcon(PunkGlyph.Settings,stringResource(R.string.punk_settings)) } } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.punk_filter_all),stringResource(R.string.punk_filter_pc),stringResource(R.string.punk_filter_phone)).forEachIndexed { index,label -> FilterChip(selected=filter==index,onClick={filter=index},label={Text(label)},modifier=Modifier.heightIn(min=48.dp)) }
            }
        }
        if(visible.isEmpty())item {
            Column(Modifier.fillMaxWidth().padding(vertical=60.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(72.dp).background(PunkSurface,RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center) { PunkIcon(PunkGlyph.Activity,tint=PunkNeonCyan,modifier=Modifier.size(32.dp)) }
                Text(stringResource(R.string.punk_activity_empty_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.punk_activity_empty_description),style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary)
            }
        }
        items(visible,key={it.id}) { event ->
            val failed=event.status.lowercase() in listOf("failed","error","denied","rejected")
            val running=event.status.lowercase() in listOf("running","pending","waiting","started","executing")
            val unverified=event.status.lowercase()=="unverified"
            val completed=event.status.lowercase() in listOf("completed","success","succeeded","done")
            val duration=formatActivityDuration(((if(running)now else event.updatedAt)-event.ts).coerceAtLeast(0))
            val statusColor=when { failed -> PunkNeonMagenta;unverified -> PunkWarning;running -> PunkNeonCyan;completed -> PunkNeonGreen;else -> PunkTextSecondary }
            val statusLabel=when(event.status.lowercase()) {
                "failed","error" -> stringResource(R.string.punk_status_failed)
                "denied","rejected" -> stringResource(R.string.punk_status_denied)
                "cancelled","canceled","interrupted" -> stringResource(R.string.punk_status_interrupted)
                "running","started","executing" -> stringResource(R.string.punk_status_running)
                "pending","waiting" -> stringResource(R.string.punk_status_pending)
                "unverified" -> stringResource(R.string.punk_status_unverified)
                "completed","success","succeeded","done" -> stringResource(R.string.punk_status_completed)
                else -> stringResource(R.string.punk_status_recorded)
            }
            Surface(onClick={expanded=if(expanded==event.id)null else event.id},color=PunkSurface,shape=RoundedCornerShape(20.dp),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PunkIcon(if(failed)PunkGlyph.Alert else if(isPhone(event.device))PunkGlyph.Phone else PunkGlyph.Pc,tint=if(failed)PunkNeonMagenta else PunkNeonCyan)
                        Column(Modifier.weight(1f)) {
                            Text(event.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("dd MMM · HH:mm",Locale("pt","BR")).format(Date(event.ts))+" · "+if(isPhone(event.device))stringResource(R.string.punk_filter_phone) else stringResource(R.string.punk_filter_pc),style=MaterialTheme.typography.labelSmall,color=PunkTextSecondary)
                            Text((if(running)"Em execução" else "Duração")+" · "+duration,style=MaterialTheme.typography.labelSmall,color=statusColor)
                        }
                        if(running)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        else PunkIcon(if(failed||unverified)PunkGlyph.Alert else if(completed)PunkGlyph.Check else PunkGlyph.Activity,tint=statusColor)
                    }
                    Text(statusLabel,style=MaterialTheme.typography.labelMedium,color=statusColor)
                    if(event.detail.isNotBlank()) {
                        if(expanded==event.id)SelectionContainer { Text(event.detail,style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary) }
                        else Text(event.detail,style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    if(expanded==event.id) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        SelectionContainer {
                            Text("Origem: ${event.device}\nStatus técnico: ${event.status}\nID: ${event.id}\nÚltima atualização: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("pt","BR")).format(Date(event.updatedAt))}",style=MaterialTheme.typography.labelSmall,color=PunkTextSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun formatActivityDuration(ms:Long):String {
    val seconds=(ms/1000).coerceAtLeast(0)
    val hours=seconds/3600
    val minutes=(seconds%3600)/60
    val rest=seconds%60
    return when {
        hours>0 -> "${hours}h ${minutes}min"
        minutes>0 -> "${minutes}min ${rest}s"
        else -> "${rest}s"
    }
}
