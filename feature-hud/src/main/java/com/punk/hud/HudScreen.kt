package com.punk.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState

@Composable
fun HudScreen(){
    val bridgeState by BridgeService.state.collectAsState()
    val statusText = when(val s = bridgeState){
        is BridgeState.Connected -> "Hermes: conectado"
        is BridgeState.Connecting -> "Hermes: conectando..."
        is BridgeState.Disconnected -> "Hermes: desconectado"
        is BridgeState.Failed -> "Hermes: falha " + s.reason.take(40)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment=Alignment.CenterHorizontally){
        val orbState = when(bridgeState){
            is BridgeState.Connected -> HudState.IDLE
            is BridgeState.Connecting -> HudState.THINKING
            is BridgeState.Failed -> HudState.ALERT
            is BridgeState.Disconnected -> HudState.IDLE
        }
        PunkOrb(state=orbState, modifier=Modifier.size(180.dp))
        Spacer(Modifier.height(12.dp))
        Text("PUNK", style=MaterialTheme.typography.headlineMedium)
        Text(statusText, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()){
            Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Modo YOLO: autonomia total", style=MaterialTheme.typography.labelLarge)
                Text("Bridge WebSocket configur\u00e1vel (host/porta/token) via Config.", style=MaterialTheme.typography.bodySmall)
                Text("AxManager UID 2000 + Accessibility prontos.", style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
