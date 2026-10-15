package com.punk.app.ui

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punk.bridge.BridgePrefs
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState
import com.punk.tools.PunkNotificationListener
import rikka.shizuku.Shizuku

@Composable
fun ConfigScreen(onConnect: (String, Int, String) -> Unit) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf(BridgePrefs.host(ctx)) }
    var port by remember { mutableStateOf(BridgePrefs.port(ctx).toString()) }
    var token by remember { mutableStateOf(BridgePrefs.token(ctx)) }
    val state by BridgeService.state.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }

    // Telemetria local
    val batteryPct = remember {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    val isShizukuReady = remember {
        try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
    }

    val isNotifListenerReady = remember {
        PunkNotificationListener.isEnabled()
    }

    // Animação de pulso neon no status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PunkAmoledBg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header Cyberpunk
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PUNK // COMPANION",
                    style = MaterialTheme.typography.displayLarge.copy(
                        color = PunkNeonCyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                )
                Text(
                    text = "J.A.R.V.I.S. NEURAL LINK v2.1",
                    style = MaterialTheme.typography.bodySmall.copy(color = PunkTextMuted)
                )
            }

            // Pulse indicator dot
            val isConnected = state is BridgeState.Connected
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) PunkNeonGreen.copy(alpha = pulseAlpha)
                        else PunkNeonMagenta.copy(alpha = pulseAlpha)
                    )
            )
        }

        // Live Telemetry HUD Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PunkSurface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PunkBorderCyan, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TELEMETRIA DE SISTEMA",
                    style = MaterialTheme.typography.labelSmall.copy(color = PunkNeonCyan)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusBadge(
                        title = "LINK HERMES",
                        value = when (state) {
                            is BridgeState.Connected -> "ONLINE"
                            is BridgeState.Connecting -> "LINKING..."
                            is BridgeState.Failed -> "OFFLINE"
                            is BridgeState.Disconnected -> "STANDBY"
                        },
                        color = when (state) {
                            is BridgeState.Connected -> PunkNeonGreen
                            is BridgeState.Connecting -> PunkNeonCyan
                            else -> PunkNeonMagenta
                        }
                    )

                    StatusBadge(
                        title = "SHIZUKU (UID 2000)",
                        value = if (isShizukuReady) "PRIVILEGED" else "RESTRICTED",
                        color = if (isShizukuReady) PunkNeonGreen else PunkNeonMagenta
                    )

                    StatusBadge(
                        title = "BATERIA",
                        value = if (batteryPct >= 0) "$batteryPct%" else "N/A",
                        color = if (batteryPct > 20) PunkNeonCyan else PunkNeonMagenta
                    )
                }

                Divider(color = PunkBorderCyan.copy(alpha = 0.2f), thickness = 0.5.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NOTIFICAÇÕES ATIVAS:",
                        style = MaterialTheme.typography.bodySmall.copy(color = PunkTextSecondary)
                    )
                    Text(
                        text = if (isNotifListenerReady) "MONITORANDO" else "PENDENTE ATIVAÇÃO",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isNotifListenerReady) PunkNeonGreen else PunkNeonMagenta
                        )
                    )
                }
            }
        }

        // Quick Actions Cyberpunk Row
        Text(
            text = "DISPATCH RÁPIDO",
            style = MaterialTheme.typography.labelSmall.copy(color = PunkNeonCyan)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionButton(
                label = "TELA",
                modifier = Modifier.weight(1f),
                onClick = {
                    Toast.makeText(ctx, "Buffer de vídeo/captura pronto", Toast.LENGTH_SHORT).show()
                }
            )
            QuickActionButton(
                label = "LOCAL",
                modifier = Modifier.weight(1f),
                onClick = {
                    Toast.makeText(ctx, "GPS & Fused Network integrados", Toast.LENGTH_SHORT).show()
                }
            )
            QuickActionButton(
                label = "TILE",
                modifier = Modifier.weight(1f),
                onClick = {
                    Toast.makeText(ctx, "Pressione o atalho na barra superior do Android", Toast.LENGTH_LONG).show()
                }
            )
        }

        // Bridge Config Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PunkSurface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PunkBorderMagenta, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PARÂMETROS DE CONEXÃO",
                    style = MaterialTheme.typography.labelSmall.copy(color = PunkNeonMagenta)
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host (Tailscale IP / LAN)", color = PunkTextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PunkTextPrimary,
                        unfocusedTextColor = PunkTextPrimary,
                        focusedBorderColor = PunkNeonCyan,
                        unfocusedBorderColor = PunkTextMuted,
                        focusedLabelColor = PunkNeonCyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Porta", color = PunkTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PunkTextPrimary,
                            unfocusedTextColor = PunkTextPrimary,
                            focusedBorderColor = PunkNeonCyan,
                            unfocusedBorderColor = PunkTextMuted,
                            focusedLabelColor = PunkNeonCyan
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token", color = PunkTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PunkTextPrimary,
                            unfocusedTextColor = PunkTextPrimary,
                            focusedBorderColor = PunkNeonCyan,
                            unfocusedBorderColor = PunkTextMuted,
                            focusedLabelColor = PunkNeonCyan
                        ),
                        modifier = Modifier.weight(2f)
                    )
                }

                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 8765
                        onConnect(host, p, token)
                        Toast.makeText(ctx, "Engatando ponte: ws://${host}:${p}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PunkNeonCyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "ESTABELECER CONEXÃO",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }

        // Checklist de Onboarding / Permissões
        Card(
            colors = CardDefaults.cardColors(containerColor = PunkSurfaceVar),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CHECKLIST DE INTEGRAÇÃO",
                    style = MaterialTheme.typography.labelSmall.copy(color = PunkNeonGreen)
                )

                ChecklistRow(
                    label = "Acessibilidade (PunkAccessibility)",
                    active = true,
                    onClick = {
                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        ctx.startActivity(intent)
                    }
                )

                ChecklistRow(
                    label = "Acesso a Notificações (Listener)",
                    active = isNotifListenerReady,
                    onClick = {
                        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        ctx.startActivity(intent)
                    }
                )

                ChecklistRow(
                    label = "Shizuku Shell (UID 2000)",
                    active = isShizukuReady,
                    onClick = {
                        try {
                            val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (intent != null) ctx.startActivity(intent)
                            else Toast.makeText(ctx, "Shizuku app não encontrado", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StatusBadge(title: String, value: String, color: Color) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelSmall.copy(color = PunkTextMuted, fontSize = 9.sp))
        Text(text = value, style = MaterialTheme.typography.bodySmall.copy(color = color, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun QuickActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = PunkSurfaceVar, contentColor = PunkNeonCyan),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PunkBorderCyan),
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun ChecklistRow(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall.copy(color = PunkTextPrimary))
        Text(
            text = if (active) "[ OK ]" else "[ CONFIGURAR ]",
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (active) PunkNeonGreen else PunkNeonMagenta,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
