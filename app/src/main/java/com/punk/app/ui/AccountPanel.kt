package com.punk.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.punk.bridge.PunkAccount
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgePrefs
import com.punk.bridge.BridgeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
fun AccountPanel(onConnect:(String,Int,String)->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var signedEmail by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var computers by remember { mutableStateOf(JsonArray(emptyList())) }
    val bridgeState by BridgeService.state.collectAsState()
    val connection by BridgePrefs.config.collectAsState()
    suspend fun connectPc(id:String) {
        busy=true;message="Localizando seu PC pela Internet…"
        try { val c=PunkAccount.connect(ctx,id);onConnect(c.host,c.port,c.token);message="Conexão autorizada. Aguardando confirmação do PC…" }
        catch(e:Exception) { message=e.message ?: "Não foi possível conectar" }
        finally { busy=false }
    }
    suspend fun refresh(autoConnect:Boolean=false) {
        computers=PunkAccount.computers(ctx)
        if(autoConnect && computers.size==1 && BridgeService.state.value !is BridgeState.Connected)
            connectPc(computers[0].jsonObject.getValue("id").jsonPrimitive.content)
    }
    LaunchedEffect(Unit) {
        signedEmail=withContext(Dispatchers.IO) { PunkAccount.email(ctx) }
        if(signedEmail.isNotEmpty())runCatching { refresh(true) }.onFailure { message="Não foi possível atualizar os computadores. Tente novamente." }
    }
    PunkPanel {
        Text("Conta Punk",style=MaterialTheme.typography.titleLarge)
        Text("Entre na mesma conta do Desktop. O Punk encontra seu PC e atualiza a rota externa sem copiar tokens.",color=PunkTextSecondary)
        if(signedEmail.isEmpty()) {
            OutlinedTextField(email,{email=it},label={Text("E-mail")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email))
            OutlinedTextField(password,{password=it},label={Text("Senha")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                for(signup in listOf(false,true)) {
                    OutlinedButton(enabled=!busy,onClick={
                        val secret=password;password="";busy=true;message=""
                        scope.launch {
                            try { PunkAccount.login(ctx,email,secret,signup);signedEmail=email.trim();refresh(true) }
                            catch(e:Exception) { message=e.message ?: "Não foi possível entrar" }
                            finally { busy=false }
                        }
                    }) { Text(if(signup)"Criar conta" else "Entrar") }
                }
            }
        } else {
            Text(signedEmail,style=MaterialTheme.typography.titleSmall)
            Text(when(bridgeState) {
                is BridgeState.Connected -> "PC conectado · "+if(connection.trustMode=="cloudflare")"Internet" else "Rede local/VPN"
                is BridgeState.Connecting -> "Conta conectada · conectando ao PC…"
                else -> "Conta conectada · PC desconectado"
            },style=MaterialTheme.typography.bodySmall,color=PunkNeonCyan)
            if(computers.isEmpty())Text("Nenhum PC cadastrado. Entre nesta conta no Punk Desktop.",color=PunkTextSecondary)
            for(item in computers) {
                val pc=item.jsonObject
                OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={
                    scope.launch { connectPc(pc.getValue("id").jsonPrimitive.content) }
                }) { Text("Conectar · "+pc.getValue("name").jsonPrimitive.content) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!busy,onClick={scope.launch { busy=true;try { refresh(true) }catch(_:Exception){message="Não foi possível atualizar"}finally{busy=false} }}) { Text("Atualizar e conectar") }
                TextButton(enabled=!busy,onClick={scope.launch {
                    busy=true
                    ctx.startService(Intent(ctx,BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
                    try { PunkAccount.logout(ctx) } catch(_:Exception) { message="Sessão removida deste celular" }
                    finally { signedEmail="";computers=JsonArray(emptyList());busy=false }
                }}) { Text("Sair") }
            }
        }
        if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(message.isNotEmpty())Text(message,style=MaterialTheme.typography.bodySmall,color=PunkNeonCyan)
        Text("Acesso externo gratuito para testes. O PC precisa estar ligado e com o Punk Desktop conectado.",style=MaterialTheme.typography.bodySmall,color=PunkTextSecondary)
    }
}
