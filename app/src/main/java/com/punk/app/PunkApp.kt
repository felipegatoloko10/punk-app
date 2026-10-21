package com.punk.app

import android.app.Application
import com.punk.accessibility.PunkAccessibilityService
import com.punk.bridge.*
import com.punk.chat.ActionEvent
import com.punk.chat.ChatRepository
import com.punk.tools.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class PunkApp:Application() {
    private val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val toolMutex=Mutex()
    override fun onCreate() {
        super.onCreate()
        ChatRepository.start(this)
        appScope.launch { BridgePrefs.initialize(this@PunkApp); ControlPolicy.applyMode(BridgePrefs.supervised(this@PunkApp)) }
        BridgeService.toolCatalog={ ToolRegistry.catalog() }
        BridgeService.deviceCapabilities={ ToolRegistry.capabilities(this@PunkApp) }
        BridgeService.awaitLocalToolsIdle={
            withTimeoutOrNull(4_000) {
                while(com.punk.accessibility.PunkAccessibilityService.hasPendingGestures()) delay(25)
                true
            } ?: false
        }
        BridgeService.stopLocalTools={ ToolRegistry.cancelAll(this@PunkApp); com.punk.voice.VoiceManager.cancelAll() }
        PunkAccessibilityService.eventConsumer={ event ->
            BridgeService.sendMessage(BridgeTypes.ACCESSIBILITY_EVENT,buildJsonObject {
                event.forEach { (key,value) -> put(key,value) }
                put("conversationId",ChatRepository.selected.value)
            })
        }
        BridgeService.toolExecutor={ tool,params,callId ->
            try {
                val definition=ToolRegistry.tools.firstOrNull { it.name==tool }
                val readOnly=definition?.risk=="reading" && tool !in setOf("audio_record","camera_capture")
                val approvalParams=JsonObject(params.mapValues { (key,value) -> if(key.contains("pin",true) || key.contains("password",true) || key.contains("token",true)) JsonPrimitive("[protegido]") else value })
                if(!ControlPolicy.authorize(tool,callId,readOnly,(definition?.description ?: tool)+"\n"+approvalParams.toString().take(2000))) ToolResult(callId,false,error="Execução não autorizada ou confirmação expirada")
                else {
                    ChatRepository.recordAction(ActionEvent(callId,definition?.description ?: tool,"","Celular","running"))
                    val result=toolMutex.withLock { withContext(Dispatchers.IO) { ToolRegistry.invoke(this@PunkApp,tool,params) } }
                    val ok=ToolRegistry.isSuccessful(result)
                    val error=if(ok)null else result["error"]?.jsonPrimitive?.contentOrNull ?: "A ferramenta não confirmou a execução"
                    val unverified=result["verified"]?.jsonPrimitive?.booleanOrNull==false || result["status"]?.jsonPrimitive?.contentOrNull?.contains("unverified")==true
                    ChatRepository.recordAction(ActionEvent(callId,definition?.description ?: tool,error ?: if(unverified)"Comando aceito; resultado final sem confirmação." else "Execução confirmada","Celular",if(!ok)"failed" else if(unverified)"unverified" else "completed"))
                    ToolResult(callId,ok,result,error)
                }
            } catch(e:CancellationException) {
                ChatRepository.recordAction(ActionEvent(callId,tool,"Execução interrompida","Celular","cancelled")); throw e
            } catch(e:Exception) {
                ChatRepository.recordAction(ActionEvent(callId,tool,e.message ?: "Falha ao executar","Celular","failed"))
                ToolResult(callId,false,error=e.message ?: "Falha ao executar")
            }
        }
    }
}
