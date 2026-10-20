package com.punk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.punk.app.ui.*
import com.punk.bridge.BridgeService
import com.punk.chat.ChatScreen
import com.punk.chat.ChatViewModel
import com.punk.voice.VoiceManager

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_VOICE = "com.punk.app.OPEN_VOICE" }
    private val voiceRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0xFF080B10.toInt()), SystemBarStyle.dark(0xFF080B10.toInt()))
        voiceRequest.value = savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_VOICE, false)
        setContent {
            PunkTheme {
                val vm: ChatViewModel = viewModel()
                val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        vm.attach(uri)
                    }
                }
                var destination by rememberSaveable { mutableIntStateOf(0) }
                var settings by rememberSaveable { mutableStateOf(false) }
                var voice by rememberSaveable { mutableStateOf(false) }
                val approvals by vm.pendingApprovals.collectAsState()
                val engine = remember { VoiceManager.getOrCreateAndroidEngine(applicationContext) }
                val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                LaunchedEffect(voiceRequest.value) {
                    if (voiceRequest.value) {
                        destination = 0
                        settings = false
                        voice = true
                        voiceRequest.value = false
                    }
                }
                if (settings) {
                    ConfigScreen(
                        onConnect = { _, _, _ ->
                            startForegroundService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_START })
                        },
                        onBack = { settings = false }
                    )
                } else {
                    Scaffold(
                        containerColor = PunkAmoledBg,
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        bottomBar = {
                            if (!keyboardVisible) {
                            NavigationBar(containerColor = PunkAmoledBg, tonalElevation = 0.dp) {
                                val labels = listOf(stringResource(R.string.punk_nav_chat), stringResource(R.string.punk_nav_devices), stringResource(R.string.punk_nav_activity))
                                val glyphs = listOf(PunkGlyph.Chat, PunkGlyph.Devices, PunkGlyph.Activity)
                                labels.forEachIndexed { index, label ->
                                    NavigationBarItem(
                                        selected = destination == index,
                                        onClick = { destination = index },
                                        icon = { PunkIcon(glyphs[index]) },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = PunkNeonCyan,
                                            selectedTextColor = PunkTextPrimary,
                                            indicatorColor = PunkSurfaceVar,
                                            unselectedIconColor = PunkTextMuted,
                                            unselectedTextColor = PunkTextMuted
                                        )
                                    )
                                }
                            }
                            }
                        }
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            when (destination) {
                                0 -> ChatScreen(vm = vm, onVoice = { voice = true }, onSettings = { settings = true }, onPickAttachment = { attachmentPicker.launch(arrayOf("*/*")) })
                                1 -> DevicesScreen(vm, onSettings = { settings = true })
                                else -> ActivityScreen(vm, onSettings = { settings = true })
                            }
                        }
                    }
                }
                if (voice) {
                    VoiceSheet(engine, vm, onDismiss = { voice = false })
                }
                approvals.firstOrNull()?.let { request ->
                    AlertDialog(
                        onDismissRequest = { vm.respondApproval(request.id, false) },
                        icon = { PunkIcon(PunkGlyph.Shield, tint = PunkNeonCyan) },
                        title = { Text("Autorizar ação") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(request.description)
                                Text("${request.device} · ${request.tool}", style = MaterialTheme.typography.bodySmall, color = PunkTextSecondary)
                            }
                        },
                        confirmButton = { Button(onClick = { vm.respondApproval(request.id, true) }) { Text("Autorizar") } },
                        dismissButton = { TextButton(onClick = { vm.respondApproval(request.id, false) }) { Text("Recusar") } }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE, false)) voiceRequest.value = true
    }
}
