package com.punk.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState

/** Compact connection summary kept for hosts that embed the HUD feature. */
@Composable
fun HudScreen() {
    val state by BridgeService.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Punk",style=MaterialTheme.typography.headlineLarge)
        Text(when(state) {
            is BridgeState.Connected -> "Conectado ao computador"
            is BridgeState.Connecting -> "Conectando…"
            is BridgeState.Failed -> "A conexão precisa de atenção"
            else -> "Computador desconectado"
        },style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
