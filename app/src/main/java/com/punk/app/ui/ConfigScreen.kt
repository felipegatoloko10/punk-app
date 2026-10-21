package com.punk.app.ui

import com.punk.app.R

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.punk.bridge.BridgeConfig
import com.punk.bridge.BridgePairing
import com.punk.bridge.BridgePrefs
import com.punk.bridge.BridgeState
import com.punk.bridge.BridgeService
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConfigScreen(onConnect:(String,Int,String)->Unit,onBack:()->Unit={}) {
    val context=LocalContext.current
    val state by BridgeService.state.collectAsState()
    val savedConfiguration by BridgePrefs.config.collectAsState()
    val scope=rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(true) }
    var fingerprint by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var localAddress by remember { mutableStateOf("") }
    var remoteAddress by remember { mutableStateOf("") }
    var autoRoutes by remember { mutableStateOf(false) }
    var pairText by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    val snackbar=remember { SnackbarHostState() }
    val scanner=remember(context) {
        GmsBarcodeScanning.getClient(context,GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    suspend fun loadSettings() {
        val config=withContext(Dispatchers.IO) {
            BridgeConfig(host=BridgePrefs.host(context),port=BridgePrefs.port(context),token=BridgePrefs.token(context),useTls=BridgePrefs.tls(context),certificateFingerprint=BridgePrefs.certificateFingerprint(context),deviceId=BridgePrefs.deviceId(context))
        }
        host=config.host;port=config.port.toString();token=config.token;tls=config.useTls;fingerprint=config.certificateFingerprint;deviceId=config.deviceId
        withContext(Dispatchers.IO) { Triple(BridgePrefs.localHost(context),BridgePrefs.tailscaleHost(context),BridgePrefs.autoFailover(context)) }.let {
            localAddress=it.first;remoteAddress=it.second;autoRoutes=it.third
        }
        loaded=true
    }
    LaunchedEffect(savedConfiguration) { loadSettings() }
    BackHandler(onBack=onBack)
    val importPairing=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer=CharArray(32769)
                        val count=reader.read(buffer)
                        require(count in 1..32768) { "O arquivo de pareamento está vazio ou é muito grande." }
                        String(buffer,0,count)
                    } ?: error("Não foi possível ler o arquivo.")
                }
            }.onSuccess { pairText=it;problem=null;success=null }.onFailure { problem=it.message ?: "Falha ao importar o pareamento." }
        }
    }
    Scaffold(
        containerColor=PunkAmoledBg,
        snackbarHost={SnackbarHost(snackbar)},
        topBar={
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start=8.dp,end=16.dp,top=4.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { PunkIcon(PunkGlyph.Back,stringResource(R.string.punk_back)) }
                Text(stringResource(R.string.punk_settings),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f).padding(start=8.dp))
                PunkMark()
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            SectionHeading(stringResource(R.string.punk_connection_eyebrow),stringResource(R.string.punk_connection_title),stringResource(R.string.punk_connection_description))
            AccountPanel(onConnect)
            PunkPanel {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    PunkIcon(PunkGlyph.Link,tint=PunkNeonCyan)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.punk_pc_connection),style=MaterialTheme.typography.titleMedium)
                        Text(when(state) { is BridgeState.Connected -> "Conectado e pronto";is BridgeState.Connecting -> "Conectando…";is BridgeState.Failed -> "Não foi possível conectar";else -> "Desconectado" },style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                    }
                    StatePill(if(state is BridgeState.Connected)"Online" else "Offline",state is BridgeState.Connected)
                }
                if(state is BridgeState.Failed)Text((state as BridgeState.Failed).reason,style=MaterialTheme.typography.bodySmall,color=PunkNeonMagenta)
            }
            PunkPanel {
                Text(stringResource(R.string.punk_pair_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.punk_pair_description),style=MaterialTheme.typography.bodyMedium,color=PunkTextSecondary)
                OutlinedButton(onClick={
                    working=true;problem=null;success=null
                    runCatching {
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                working=false
                                val raw=barcode.rawValue
                                if(raw.isNullOrBlank() || raw.length>32768)problem="O QR code não contém dados de pareamento válidos."
                                else { pairText=raw;success="QR code lido. Toque em Parear e conectar." }
                            }
                            .addOnCanceledListener { working=false }
                            .addOnFailureListener { working=false;problem="Não foi possível abrir o leitor. Você pode colar ou importar os dados de pareamento." }
                    }.onFailure { working=false;problem="Leitor indisponível. Cole ou importe os dados de pareamento abaixo." }
                },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!working) {
                    PunkIcon(PunkGlyph.Camera);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.punk_scan_qr))
                }
                OutlinedTextField(value=pairText,onValueChange={pairText=it;problem=null;success=null},label={Text(stringResource(R.string.punk_pair_data))},placeholder={Text(stringResource(R.string.punk_pair_placeholder))},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,enabled=!working)
                OutlinedButton(onClick={importPairing.launch(arrayOf("application/json","text/plain"))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!working) { PunkIcon(PunkGlyph.Link);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.punk_import_pair)) }
                Button(onClick={
                    if(pairText.isBlank()) { problem="Cole os dados de pareamento do seu computador.";return@Button }
                    scope.launch {
                        working=true;problem=null;success=null
                        val result=withContext(Dispatchers.IO) { BridgePairing.pair(context,pairText.trim()) }
                        result.onSuccess {
                            loadSettings()
                            pairText=""
                            onConnect(host,port.toIntOrNull() ?: 8765,token)
                            success="Pareamento concluído. Conectando ao computador…"
                        }.onFailure { problem=it.message ?: "Não foi possível parear. Gere um novo código no PC." }
                        working=false
                    }
                },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!working&&loaded) {
                    if(working)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)
                    else { Text(stringResource(R.string.punk_pair_connect));Spacer(Modifier.width(8.dp));PunkIcon(PunkGlyph.Chevron) }
                }
                Text(stringResource(R.string.punk_key_storage),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
            }
            problem?.let { Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp)) { Text(it,modifier=Modifier.fillMaxWidth().padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium) } }
            success?.let { Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp)) { Text(it,modifier=Modifier.fillMaxWidth().padding(16.dp),color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.bodyMedium) } }
            PunkPanel {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.punk_manual_connection),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.punk_manual_description),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                    }
                    IconButton(onClick={manual=!manual}) { PunkIcon(if(manual)PunkGlyph.Close else PunkGlyph.Plus,if(manual)"Recolher conexão manual" else "Abrir conexão manual") }
                }
                if(manual) {
                    OutlinedTextField(value=host,onValueChange={host=it},label={Text(stringResource(R.string.punk_pc_address))},supportingText={Text(stringResource(R.string.punk_pc_address_hint))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!working,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                    OutlinedTextField(value=port,onValueChange={port=it.filter(Char::isDigit).take(5)},label={Text(stringResource(R.string.punk_port))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!working,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    OutlinedTextField(value=token,onValueChange={token=it},label={Text(stringResource(R.string.punk_access_key))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!working,visualTransformation=if(showToken)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={IconButton(onClick={showToken=!showToken}) { PunkIcon(PunkGlyph.Eye,if(showToken)"Ocultar chave" else "Mostrar chave") }})
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.punk_tls),style=MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.punk_tls_description),style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                        }
                        Switch(checked=tls,onCheckedChange={tls=it;if(!it)autoRoutes=false},enabled=!working)
                    }
                    if(tls)OutlinedTextField(value=fingerprint,onValueChange={fingerprint=it},label={Text(stringResource(R.string.punk_fingerprint))},supportingText={Text(stringResource(R.string.punk_fingerprint_hint))},modifier=Modifier.fillMaxWidth(),maxLines=3,enabled=!working)
                    if(tls) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Alternar endereços automaticamente",style=MaterialTheme.typography.titleSmall)
                                Text("Tenta suas rotas local e Tailscale ao mudar de rede.",style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
                            }
                            Switch(checked=autoRoutes,onCheckedChange={autoRoutes=it},enabled=!working)
                        }
                        if(autoRoutes) {
                            OutlinedTextField(value=localAddress,onValueChange={localAddress=it},label={Text("Endereço na rede local")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!working)
                            OutlinedTextField(value=remoteAddress,onValueChange={remoteAddress=it},label={Text("Endereço Tailscale")},supportingText={Text("Mantenha sua VPN conectada no app Tailscale.")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!working)
                        }
                    }
                    Button(onClick={
                        val parsedPort=port.toIntOrNull()
                        problem=when {
                            host.isBlank() || host.any(Char::isWhitespace) || host.contains("://") -> "Informe apenas o endereço ou IP do computador, sem http://."
                            parsedPort==null || parsedPort !in 1..65535 -> "Informe uma porta entre 1 e 65535."
                            token.isBlank() -> "Informe a chave de acesso do bridge."
                            tls && fingerprint.isBlank() -> "Informe a impressão digital do certificado do PC."
                            else -> null
                        }
                        if(problem==null && parsedPort!=null)scope.launch {
                            working=true;success=null
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    BridgePrefs.saveFailoverSettings(context,localAddress.trim(),remoteAddress.trim(),autoRoutes&&tls)
                                    BridgePrefs.save(context,BridgeConfig(host=host.trim(),port=parsedPort,token=token.trim(),useTls=tls,certificateFingerprint=fingerprint.trim(),deviceId=deviceId))
                                }
                                onConnect(host.trim(),parsedPort,token.trim())
                            }.onSuccess { success="Configuração salva. Conectando ao computador…" }.onFailure { problem=it.message ?: "Não foi possível salvar a conexão." }
                            working=false
                        }
                    },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!working&&loaded) { Text(stringResource(R.string.punk_save_connect)) }
                }
            }
            Text("Punk\nSeu agente. Seus dispositivos.",style=MaterialTheme.typography.bodySmall,color=PunkTextMuted,modifier=Modifier.padding(vertical=12.dp))
        }
    }
}
