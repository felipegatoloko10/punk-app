package com.punk.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.punk.bridge.BridgeState

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()
    val bridgeState by vm.bridgeState.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Status indicator banner
        Surface(
            color = when (bridgeState) {
                is BridgeState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is BridgeState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                text = when (bridgeState) {
                    is BridgeState.Connected -> "● Hermes Online (Bridge Conectado)"
                    is BridgeState.Connecting -> "◌ Conectando ao Hermes..."
                    is BridgeState.Failed -> "✕ Conexão Falhou — Verifique Config"
                    else -> "○ Hermes Desconectado"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(messages){ m ->
                Card(
                    shape=RoundedCornerShape(16.dp),
                    colors=CardDefaults.cardColors(containerColor= if(m.role=="user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                ){
                    Text(m.text, modifier=Modifier.padding(12.dp), style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(value=input, onValueChange=vm::onInput, modifier=Modifier.weight(1f), placeholder={ Text("Fale com o PUNK...")}, shape=RoundedCornerShape(24.dp))
            Button(onClick=vm::send, shape=RoundedCornerShape(24.dp)){ Text("Enviar") }
        }
    }
}
