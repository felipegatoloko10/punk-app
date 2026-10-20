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
import com.punk.chat.ChatViewModel
import com.punk.voice.AndroidVoiceEngine
import com.punk.voice.VoiceStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(engine:AndroidVoiceEngine, vm:ChatViewModel, onDismiss:()->Unit) {
    val context=LocalContext.current
    val status by engine.status.collectAsState()
    val amplitude by engine.amplitudeFlow.collectAsState()
    val busy by vm.busy.collectAsState()
    val messages by vm.messages.collectAsState()
    val enteredAt=remember { System.currentTimeMillis() }
    var transcript by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    var liveMode by remember { mutableStateOf(true) }
    var heardReply by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) }
    val listening=status is VoiceStatus.Listening || status is VoiceStatus.Ready
    val speaking=status is VoiceStatus.Speaking
    val latest=messages.lastOrNull { it.role=="assistant" && it.ts>=enteredAt }
    val latestUser=messages.lastOrNull { it.role=="user" && it.ts>=enteredAt }
    val displayError=error ?: (status as? VoiceStatus.Error)?.message
    fun listen() {
        error=null
        transcript=""
        submitted=false
        engine.startListeningWithCallbacks(
            onPartial={if(active)transcript=it},
            onFinal={ final ->
                if(active)transcript=final
                if(active && final.isNotBlank() && !submitted) {
                    submitted=true
                    vm.sendText(final,spoken=true)
                }
            },
            onError={if(active)error=it}
        )
    }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission=granted
        if(granted)listen() else error="O microfone está desativado. Você também pode conversar por texto."
    }
    LaunchedEffect(Unit) { if(permission && !busy)listen() }
    LaunchedEffect(status,busy,permission,active,liveMode) {
        if(status is VoiceStatus.Speaking) heardReply=true
        if(heardReply && status is VoiceStatus.Idle && !busy && permission && active && liveMode) {
            heardReply=false
            delay(350)
            if(active && liveMode && !vm.busy.value) listen()
        }
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
                engine.cancelListening()
                engine.stopSpeaking()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            active=false
            lifecycle?.removeObserver(observer)
            engine.cancelListening()
            engine.stopSpeaking()
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
                selected=liveMode,
                onClick={
                    liveMode=!liveMode
                    if(!liveMode) engine.cancelListening() else if(permission && !busy && !speaking) listen()
                },
                label={Text(if(liveMode)"LIVE ATIVO · conversa contínua" else "LIVE PAUSADO")},
                leadingIcon={PunkIcon(if(liveMode)PunkGlyph.Mic else PunkGlyph.Stop,modifier=Modifier.size(18.dp))}
            )
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
                when { displayError!=null -> stringResource(R.string.punk_voice_retry); !permission -> stringResource(R.string.punk_voice_enable); listening -> stringResource(R.string.punk_voice_listening); speaking -> stringResource(R.string.punk_voice_speaking); busy -> stringResource(R.string.punk_voice_working); else -> stringResource(R.string.punk_voice_ready) },
                style=MaterialTheme.typography.headlineMedium,
                modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }
            )
            if(!permission)Text(stringResource(R.string.punk_voice_permission),style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary)
            displayError?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=PunkNeonMagenta) }
            if(!permission && error!=null)TextButton(onClick={
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }
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
                    if(!submitted && transcript.isNotBlank())vm.onInput(transcript)
                    onDismiss()
                },modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text(stringResource(R.string.punk_keyboard)) }
                Button(onClick={
                    when {
                        !permission -> request.launch(Manifest.permission.RECORD_AUDIO)
                        busy || speaking -> { liveMode=false; vm.stop() }
                        listening -> { liveMode=false; engine.cancelListening() }
                        else -> { liveMode=true; listen() }
                    }
                },modifier=Modifier.weight(1f).heightIn(min=52.dp)) {
                    PunkIcon(if(busy || speaking || listening)PunkGlyph.Stop else PunkGlyph.Mic)
                    Spacer(Modifier.width(8.dp))
                    Text(when { !permission -> stringResource(R.string.punk_activate); busy || speaking -> stringResource(R.string.punk_stop); listening -> stringResource(R.string.punk_pause); else -> stringResource(R.string.punk_speak) })
                }
            }
        }
    }
}
