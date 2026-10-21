package com.punk.app.ui

import com.punk.app.R

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.punk.app.LiveVoiceService
import com.punk.chat.ChatViewModel
import com.punk.voice.AndroidVoiceEngine
import com.punk.voice.VoiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(engine:AndroidVoiceEngine, vm:ChatViewModel, onDismiss:()->Unit) {
    val context=LocalContext.current
    val status by engine.status.collectAsState()
    val amplitude by engine.amplitudeFlow.collectAsState()
    val live by LiveVoiceService.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val messages by vm.messages.collectAsState()
    val enteredAt=remember { System.currentTimeMillis() }
    var active by remember { mutableStateOf(true) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) }
    val listening=live.active && live.phase in setOf("listening","armed","waiting_wake")
    val preparing=live.active && live.phase in setOf("preparing","processing")
    val speaking=live.active && live.phase=="speaking"
    val transcript=live.transcript
    val latest=messages.lastOrNull { it.role=="assistant" && it.ts>=enteredAt }
    val latestUser=messages.lastOrNull { it.role=="user" && it.ts>=enteredAt }
    val displayError=live.error ?: (status as? VoiceStatus.Error)?.message
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission=granted
        if(granted)LiveVoiceService.start(context)
    }
    DisposableEffect(engine,context) {
        val lifecycle=(context as? LifecycleOwner)?.lifecycle
        active=true
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) {
                active=true
                permission=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            }
            if(event==Lifecycle.Event.ON_STOP) {
                active=false
                if(!LiveVoiceService.state.value.active) {
                    engine.cancelListening()
                    engine.stopSpeaking()
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            active=false
            lifecycle?.removeObserver(observer)
            if(!LiveVoiceService.state.value.active) {
                engine.cancelListening()
                engine.stopSpeaking()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=PunkAmoledBg,
        dragHandle={BottomSheetDefaults.DragHandle(color=PunkTextMuted)}
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max=740.dp).padding(horizontal=24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.punk_voice_title),style=MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.punk_voice_subtitle),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                }
                IconButton(onClick=onDismiss) { PunkIcon(PunkGlyph.Close,stringResource(R.string.punk_voice_close)) }
            }
            FilterChip(
                selected=live.active,
                onClick={
                    if(live.active) LiveVoiceService.stop(context)
                    else if(permission) LiveVoiceService.start(context)
                    else request.launch(Manifest.permission.RECORD_AUDIO)
                },
                label={Text(if(live.active)"LIVE ATIVO · continua em segundo plano" else "LIVE PAUSADO")},
                leadingIcon={PunkIcon(if(live.active)PunkGlyph.Mic else PunkGlyph.Stop,modifier=Modifier.size(18.dp))}
            )
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Ativação por “Punk”",style=MaterialTheme.typography.titleSmall)
                    Text(
                        if(live.onDeviceRecognition)"Reconhecimento local disponível" else "Usa o serviço de voz configurado no Android",
                        style=MaterialTheme.typography.bodySmall,
                        color=PunkTextSecondary
                    )
                }
                Switch(
                    checked=live.wakePhraseEnabled,
                    enabled=live.active,
                    onCheckedChange={LiveVoiceService.setWakePhrase(context,it)}
                )
            }
            val power by animateFloatAsState(if(listening)amplitude.coerceIn(0f,1f) else if(speaking).35f else .05f,label="voice amplitude")
            Box(Modifier.size(180.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center=Offset(size.width/2,size.height/2)
                    val radius=size.minDimension*(.25f+power*.11f)
                    drawCircle(Brush.radialGradient(listOf(PunkNeonCyan.copy(alpha=.18f),Color.Transparent)),radius*1.9f,center)
                    drawCircle(PunkNeonCyan.copy(alpha=.18f),radius*1.3f,center,style=Stroke(1.5f))
                    drawCircle(PunkNeonCyan.copy(alpha=.65f),radius,center,style=Stroke(2f))
                }
                if(busy)CircularProgressIndicator(Modifier.size(58.dp),strokeWidth=2.dp)
                else PunkIcon(PunkGlyph.Mic,tint=PunkNeonCyan,modifier=Modifier.size(38.dp))
            }
            Text(
                when { displayError!=null -> stringResource(R.string.punk_voice_retry); !permission -> stringResource(R.string.punk_voice_enable); live.phase=="waiting_wake" -> "Diga “Punk” para ativar"; live.phase=="armed" -> "Punk ativado · diga seu pedido"; listening -> stringResource(R.string.punk_voice_listening); preparing -> if(live.phase=="processing")stringResource(R.string.punk_voice_working) else "Preparando reconhecimento…"; speaking -> "Punk está falando · fale ou toque para interromper"; busy -> stringResource(R.string.punk_voice_working); else -> stringResource(R.string.punk_voice_ready) },
                style=MaterialTheme.typography.headlineMedium,
                modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }
            )
            if(!permission)Text(stringResource(R.string.punk_voice_permission),style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary)
            displayError?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=PunkNeonMagenta) }
            if(displayError!=null)TextButton(onClick={
                runCatching {
                    context.startActivity(
                        if(!permission)Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))
                        else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    )
                }
            },modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.punk_voice_settings)) }
            if(transcript.isNotBlank() || latest!=null)PunkPanel {
                if(transcript.isNotBlank()) {
                    Text("VOCÊ",style=MaterialTheme.typography.labelSmall,color=PunkTextSecondary)
                    Text(transcript,style=MaterialTheme.typography.bodyLarge)
                }
                latest?.let {
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Text("PUNK",style=MaterialTheme.typography.labelSmall,color=PunkNeonCyan)
                    Text(it.text,style=MaterialTheme.typography.bodyLarge)
                }
                latestUser?.takeIf { it.status in listOf("failed","interrupted") }?.let { failed ->
                    Text(stringResource(R.string.punk_voice_send_failed),style=MaterialTheme.typography.bodySmall,color=PunkNeonMagenta)
                    TextButton(onClick={vm.retry(failed.messageId)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.punk_retry)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={
                    if(transcript.isNotBlank())vm.onInput(transcript)
                    onDismiss()
                },modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text(stringResource(R.string.punk_keyboard)) }
                Button(onClick={
                    when {
                        !permission -> request.launch(Manifest.permission.RECORD_AUDIO)
                        speaking -> LiveVoiceService.interrupt(context)
                        busy -> { LiveVoiceService.stop(context); vm.stop() }
                        live.active -> LiveVoiceService.stop(context)
                        else -> LiveVoiceService.start(context)
                    }
                },modifier=Modifier.weight(1f).heightIn(min=52.dp)) {
                    PunkIcon(if(busy || speaking || listening || preparing)PunkGlyph.Stop else PunkGlyph.Mic)
                    Spacer(Modifier.width(8.dp))
                    Text(when { !permission -> stringResource(R.string.punk_activate); speaking -> "Interromper"; busy -> stringResource(R.string.punk_stop); live.active -> stringResource(R.string.punk_pause); else -> stringResource(R.string.punk_speak) })
                }
            }
        }
    }
}
