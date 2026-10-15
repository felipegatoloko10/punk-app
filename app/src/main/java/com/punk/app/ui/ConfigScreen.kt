package com.punk.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.punk.bridge.BridgePrefs
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState

@Composable
fun ConfigScreen(onConnect:(String,Int,String)->Unit){
    val ctx = LocalContext.current
    var host by remember { mutableStateOf(BridgePrefs.host(ctx)) }
    var port by remember { mutableStateOf(BridgePrefs.port(ctx).toString()) }
    var token by remember { mutableStateOf(BridgePrefs.token(ctx)) }
    val state by BridgeService.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Conex\u00e3o Hermes (Bridge)", style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=host, onValueChange={host=it}, label={Text("Host (Tailscale IP / LAN)")}, modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=port, onValueChange={port=it}, label={Text("Porta")}, modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=token, onValueChange={token=it}, label={Text("Token (pareamento)")}, modifier=Modifier.fillMaxWidth())
        Button(onClick={
            val p = port.toIntOrNull() ?: 8765
            onConnect(host,p,token)
            Toast.makeText(ctx, "Bridge: ws://${host}:${p}", Toast.LENGTH_SHORT).show()
        }, modifier=Modifier.fillMaxWidth()){ Text("Conectar / Reconectar") }
        val label = when(state){
            is BridgeState.Connected -> "Conectado"
            is BridgeState.Connecting -> "Conectando..."
            is BridgeState.Disconnected -> "Desconectado"
            is BridgeState.Failed -> "Falha: ${(state as BridgeState.Failed).reason}"
        }
        Text("Estado: $label", style=MaterialTheme.typography.bodyMedium)
        Divider()
        Text("AxManager: UID 2000 via /data/local/tmp/axmanager (daemon persistente). Se offline, abra o AxManager e reinicie o daemon.", style=MaterialTheme.typography.bodySmall)
        Text("Accessibility: ative em Configura\u00e7\u00f5es > Acessibilidade > PUNK.", style=MaterialTheme.typography.bodySmall)
        Text("Modo YOLO ativo: sem bloqueios desnecess\u00e1rios. Controle total para o Hermes.", style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.primary)
    }
}
