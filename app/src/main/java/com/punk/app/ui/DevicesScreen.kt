package com.punk.app.ui

import com.punk.app.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.punk.accessibility.PunkAccessibilityService
import com.punk.bridge.BridgeState
import com.punk.bridge.BridgeService
import com.punk.chat.ChatViewModel
import com.punk.tools.PunkNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Composable
fun DevicesScreen(vm:ChatViewModel,onSettings:()->Unit) {
    val context=LocalContext.current
    val bridge by vm.bridgeState.collectAsState()
    val health by BridgeService.health.collectAsState()
    val supervised by vm.supervised.collectAsState()
    val busy by vm.busy.collectAsState()
    var capabilities by remember { mutableStateOf<Map<String,Boolean>>(emptyMap()) }
    var battery by remember { mutableIntStateOf(-1) }
    var additional by remember { mutableStateOf(false) }
    val snack=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val requestPermissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if(granted.values.any{!it})scope.launch {
            val result=snack.showSnackbar("Algumas permissões continuam desativadas.",actionLabel="Ajustes",withDismissAction=true)
            if(result==SnackbarResult.ActionPerformed)runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
            }
        }
    }
    fun open(intent:Intent) {
        try { context.startActivity(intent) } catch(_:Exception) { scope.launch { snack.showSnackbar("Não foi possível abrir este ajuste neste aparelho.") } }
    }
    LaunchedEffect(Unit) {
        while(true) {
            fun allowed(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
            capabilities=mapOf(
                "accessibility" to PunkAccessibilityService.isEnabled(),
                "notifications" to PunkNotificationListener.isEnabled(),
                "postNotifications" to (Build.VERSION.SDK_INT<33 || allowed(Manifest.permission.POST_NOTIFICATIONS)),
                "shizuku" to runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
                "adbDebugging" to runCatching { Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED,0)==1 }.getOrDefault(false),
                "microphone" to allowed(Manifest.permission.RECORD_AUDIO),
                "camera" to allowed(Manifest.permission.CAMERA),
                "location" to (allowed(Manifest.permission.ACCESS_FINE_LOCATION)||allowed(Manifest.permission.ACCESS_COARSE_LOCATION)),
                "contacts" to (allowed(Manifest.permission.READ_CONTACTS)&&allowed(Manifest.permission.WRITE_CONTACTS)),
                "calendar" to (allowed(Manifest.permission.READ_CALENDAR)&&allowed(Manifest.permission.WRITE_CALENDAR)),
                "dnd" to runCatching { context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false),
                "calls" to allowed(Manifest.permission.CALL_PHONE),
                "sms" to (allowed(Manifest.permission.SEND_SMS)&&allowed(Manifest.permission.READ_SMS)),
                "files" to if(Build.VERSION.SDK_INT>=30)Environment.isExternalStorageManager() else allowed(Manifest.permission.READ_EXTERNAL_STORAGE)
            )
            battery=context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            delay(1500)
        }
    }
    val connected=bridge is BridgeState.Connected
    Scaffold(containerColor=PunkAmoledBg,snackbarHost={SnackbarHost(snack)},contentWindowInsets=WindowInsets(0,0,0,0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item { SectionHeading(stringResource(R.string.punk_devices_eyebrow),stringResource(R.string.punk_nav_devices),stringResource(R.string.punk_devices_subtitle)) { IconButton(onClick=onSettings) { PunkIcon(PunkGlyph.Settings,stringResource(R.string.punk_settings)) } } }
            item {
                PunkPanel {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(50.dp).background(PunkSurfaceVar,RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center) { PunkIcon(PunkGlyph.Pc,tint=PunkNeonCyan) }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.punk_pc_title),style=MaterialTheme.typography.titleMedium)
                            Text(when(bridge) { is BridgeState.Connected -> "Punk está conectado";is BridgeState.Connecting -> "Tentando conectar…";is BridgeState.Failed -> "Conexão precisa de atenção";else -> "Aguardando pareamento" },style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                        }
                    }
                    StatePill(if(connected)"Conectado" else "Desconectado",connected)
                    if(connected && health.isNotEmpty()) {
                        HealthRow("Agente no computador",(health["hermes"] as? JsonPrimitive)?.booleanOrNull)
                        HealthRow("Controle integrado",(health["policy"] as? JsonPrimitive)?.booleanOrNull)
                        val identity=health["identity"] as? JsonObject
                        HealthRow("Identidade sincronizada",(identity?.get("ok") as? JsonPrimitive)?.booleanOrNull)
                        DetailRow("Perfil",(identity?.get("profile") as? JsonPrimitive)?.contentOrNull ?: "punk-mobile")
                        DetailRow("Modelo",(identity?.get("model") as? JsonPrimitive)?.contentOrNull ?: "Verificando")
                        DetailRow("Provedor",(identity?.get("provider") as? JsonPrimitive)?.contentOrNull ?: "Verificando")
                        HealthRow("Mesmos recursos do Telegram",(identity?.get("toolsetParity") as? JsonPrimitive)?.booleanOrNull)
                        HealthRow("Voz Live",(health["voiceLive"] as? JsonPrimitive)?.booleanOrNull)
                    }
                    AdbHealthRow(
                        state=if(connected)(health["adbState"] as? JsonPrimitive)?.content else null,
                        count=(health["adbDevices"] as? JsonPrimitive)?.content?.toIntOrNull()
                    )
                    Text(if(connected)"Envie pedidos pela conversa para usar os recursos disponíveis no seu PC." else "Conecte o bridge do PC para usar seu agente a partir do celular.",style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary)
                    OutlinedButton(onClick=onSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { PunkIcon(PunkGlyph.Link);Spacer(Modifier.width(8.dp));Text(if(connected)stringResource(R.string.punk_manage_connection) else stringResource(R.string.punk_connect_pc)) }
                }
            }
            item {
                PunkPanel {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(50.dp).background(PunkSurfaceVar,RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center) { PunkIcon(PunkGlyph.Phone,tint=PunkNeonCyan) }
                        Column(Modifier.weight(1f)) {
                            Text(if(Build.MODEL.startsWith("SM-S918"))"Galaxy S23 Ultra" else Build.MODEL,style=MaterialTheme.typography.titleMedium)
                            Text("Este celular · Android ${Build.VERSION.RELEASE}"+(if(battery>=0)" · $battery%" else ""),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                        }
                    }
                    Text(stringResource(R.string.punk_phone_resources),style=MaterialTheme.typography.titleSmall)
                    CapabilityRow(PunkGlyph.Phone,"Controlar a tela","Toques, gestos e leitura da interface",capabilities["accessibility"]==true) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    CapabilityRow(PunkGlyph.Shield,"Shizuku · ADB local",if(capabilities["shizuku"]==true)"Serviço shell ativo e autorizado neste celular" else "Inicie e autorize o Shizuku para controles privilegiados",capabilities["shizuku"]==true) {
                        try {
                            if(Shizuku.pingBinder())Shizuku.requestPermission(1002)
                            else {
                                val intent=context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                if(intent!=null)open(intent) else scope.launch { snack.showSnackbar("Instale e inicie o Shizuku para ativar controles do sistema.") }
                            }
                        } catch(_:Throwable) { scope.launch { snack.showSnackbar("Abra o Shizuku e inicie o serviço antes de autorizar o Punk.") } }
                    }
                    CapabilityRow(PunkGlyph.Link,"Depuração do Android","Ativa no celular; a conexão ADB do computador é mostrada separadamente acima",capabilities["adbDebugging"]==true) { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    CapabilityRow(PunkGlyph.Notification,"Notificações","Consultar notificações dos apps",capabilities["notifications"]==true) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    CapabilityRow(PunkGlyph.Mic,"Microfone","Pedidos e conversa por voz",capabilities["microphone"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                    CapabilityRow(PunkGlyph.Camera,"Câmera","Capturas solicitadas na conversa",capabilities["camera"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.CAMERA)) }
                    CapabilityRow(PunkGlyph.Location,"Localização","Consultar a posição do aparelho",capabilities["location"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    TextButton(onClick={additional=!additional},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(additional)stringResource(R.string.punk_show_less) else stringResource(R.string.punk_other_resources));Spacer(Modifier.width(6.dp));PunkIcon(if(additional)PunkGlyph.Close else PunkGlyph.Plus) }
                    if(additional) {
                        CapabilityRow(PunkGlyph.Notification,stringResource(R.string.punk_alerts_title),stringResource(R.string.punk_alerts_description),capabilities["postNotifications"]==true) {
                            if(Build.VERSION.SDK_INT>=33 && capabilities["postNotifications"]!=true)requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                        }
                        CapabilityRow(PunkGlyph.Phone,"Contatos","Consultar e criar contatos",capabilities["contacts"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS,Manifest.permission.WRITE_CONTACTS)) }
                        CapabilityRow(PunkGlyph.Activity,"Agenda","Consultar e criar eventos",capabilities["calendar"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) }
                        CapabilityRow(PunkGlyph.Shield,"Não Perturbe","Controle especial do modo de interrupção",capabilities["dnd"]==true) { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        CapabilityRow(PunkGlyph.Phone,"Ligações","Iniciar chamadas solicitadas",capabilities["calls"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.CALL_PHONE)) }
                        CapabilityRow(PunkGlyph.Chat,"SMS","Consultar e enviar mensagens",capabilities["sms"]==true) { requestPermissions.launch(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.SEND_SMS)) }
                        CapabilityRow(PunkGlyph.Link,"Arquivos","Acesso aos arquivos compartilhados",capabilities["files"]==true) {
                            if(Build.VERSION.SDK_INT>=30)open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))
                            else requestPermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                        }
                    }
                }
            }
            item {
                PunkPanel {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PunkIcon(PunkGlyph.Shield,tint=PunkNeonCyan)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.punk_supervised_title),style=MaterialTheme.typography.titleMedium)
                            Text(if(supervised)stringResource(R.string.punk_supervised_on) else stringResource(R.string.punk_supervised_off),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                        }
                        Switch(checked=supervised,onCheckedChange=vm::setSupervised)
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Text(stringResource(R.string.punk_control_title),style=MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.punk_control_description),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                    Button(onClick=vm::stop,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.onErrorContainer)) {
                        PunkIcon(PunkGlyph.Stop);Spacer(Modifier.width(8.dp));Text(if(busy)stringResource(R.string.punk_stop_all) else stringResource(R.string.punk_stop_tasks))
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthRow(title:String,ready:Boolean?) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary,modifier=Modifier.weight(1f))
        Text(when(ready) { true -> "Pronto";false -> "Indisponível";null -> "Verificando" },style=MaterialTheme.typography.labelMedium,color=when(ready) { true -> PunkNeonGreen;false -> PunkNeonMagenta;null -> PunkTextSecondary })
    }
}

@Composable
private fun DetailRow(title:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary,modifier=Modifier.weight(1f))
        Text(value.take(34),style=MaterialTheme.typography.labelMedium,color=PunkTextPrimary)
    }
}

@Composable
private fun AdbHealthRow(state:String?,count:Int?) {
    val (label,active)=when(state) {
        "connected" -> (if((count ?: 0)>1)"Conectado · $count aparelhos" else "Conectado e autorizado") to true
        "unauthorized" -> "Aguardando autorização no celular" to false
        "offline" -> "Detectado, mas offline" to false
        "disconnected" -> "Nenhum aparelho no PC" to false
        "unavailable" -> "ADB não encontrado no PC" to false
        "error" -> "Falha ao consultar o ADB" to false
        else -> "Conecte o bridge para verificar" to null
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text("ADB com o PC",style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary,modifier=Modifier.weight(1f))
        Text(label,style=MaterialTheme.typography.labelMedium,color=when(active) { true -> PunkNeonGreen;false -> PunkNeonMagenta;null -> PunkTextSecondary })
    }
}

@Composable
private fun CapabilityRow(glyph:PunkGlyph,title:String,description:String,active:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=66.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        PunkIcon(glyph,tint=PunkTextSecondary)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text(description,style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
            Text(if(active)"Ativo" else "Toque para ativar",style=MaterialTheme.typography.labelSmall,color=if(active)PunkNeonGreen else PunkNeonCyan)
        }
        PunkIcon(if(active)PunkGlyph.Check else PunkGlyph.Chevron,tint=if(active)PunkNeonGreen else PunkTextMuted)
    }
}
