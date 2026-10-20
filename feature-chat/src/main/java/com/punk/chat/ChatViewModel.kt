package com.punk.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.punk.bridge.*
import com.punk.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

class ChatViewModel(application:Application):AndroidViewModel(application) {
    private val ctx=application.applicationContext
    private val prefs=ctx.getSharedPreferences("punk_drafts",0)
    val messages=ChatRepository.messages
    val conversations=ChatRepository.conversations
    val conversationId=ChatRepository.selected
    val activity=ChatRepository.activity
    val bridgeState=BridgeService.state
    val supervised=ControlPolicy.supervised
    val pendingApprovals=ControlPolicy.pending
    private val _input=MutableStateFlow(prefs.getString("text","") ?: "")
    val input:StateFlow<String> = _input
    private val _attachments=MutableStateFlow<List<AttachmentDraft>>(loadDrafts())
    val attachments:StateFlow<List<AttachmentDraft>> = _attachments
    private val _uploading=MutableStateFlow(false)
    val busy:StateFlow<Boolean> = combine(messages,_uploading) { list,uploading -> uploading || list.any { it.status in setOf("sending","running","streaming") } }
        .stateIn(viewModelScope,SharingStarted.Eagerly,false)
    private var sendJob:Job?=null
    init { ChatRepository.start(application) }
    fun onInput(value:String) { _input.value=value; prefs.edit().putString("text",value).apply() }
    fun send() { sendText(_input.value,false) }
    fun sendText(text:String,spoken:Boolean=false) {
        val trimmed=text.trim()
        val command=java.text.Normalizer.normalize(trimmed.lowercase(),java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"),"").replace(",","")
        if(command in setOf("/supervisionado","punk peca confirmacao antes de agir","pedir confirmacao","modo supervisionado")) { setSupervised(true); onInput(""); return }
        if(command in setOf("/autonomo","modo autonomo","punk volte ao modo autonomo")) { setSupervised(false); onInput(""); return }
        if(command in setOf("/parar","punk para tudo","punk pare tudo")) { stop(); onInput(""); return }
        if(trimmed.isEmpty() && _attachments.value.isEmpty()) return
        if(sendJob?.isActive==true || busy.value) return
        val cid=conversationId.value
        val drafts=_attachments.value.toList()
        sendJob=viewModelScope.launch {
            try {
                _uploading.value=drafts.isNotEmpty()
                val uploaded=mutableListOf<JsonElement>()
                for(draft in drafts) {
                    val metadata=withContext(Dispatchers.IO) { BridgeFiles.upload(ctx,draft) { progress ->
                        _attachments.value=_attachments.value.map { if(it.id==draft.id) it.copy(progress=progress,error=null) else it }
                    } }
                    uploaded+=metadata
                }
                if(conversationId.value!=cid) ChatRepository.select(cid)
                withContext(Dispatchers.IO) { ChatRepository.send(trimmed,spoken,JsonArray(uploaded)) }
                onInput(""); _attachments.value=emptyList(); persistDrafts()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                _attachments.value=_attachments.value.map { it.copy(error="Envio interrompido. Toque em enviar para retomar.") }
                ChatRepository.recordAction(ActionEvent(UUID.randomUUID().toString(),"Não foi possível enviar",e.message ?: "Verifique a conexão com o PC.","Celular","failed"))
            } finally { _uploading.value=false }
        }
    }
    fun attach(uri:Uri) {
        if(_attachments.value.size>=5) return
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                var name=uri.lastPathSegment ?: "Arquivo"; var size=0L
                ctx.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) { name=cursor.getString(0) ?: name; if(!cursor.isNull(1))size=cursor.getLong(1) }
                }
                require(size<=512L*1024*1024) { "O limite por arquivo é 512 MB" }
                val draft=AttachmentDraft(UUID.randomUUID().toString(),name,ctx.contentResolver.getType(uri) ?: "application/octet-stream",uri.toString(),size)
                _attachments.value=_attachments.value+draft; persistDrafts()
            }.onFailure { ChatRepository.recordAction(ActionEvent(UUID.randomUUID().toString(),"Anexo indisponível",it.message ?: "Escolha novamente o arquivo.","Celular","failed")) }
        }
    }
    fun removeAttachment(id:String) { _attachments.value=_attachments.value.filterNot { it.id==id }; persistDrafts() }
    fun newConversation() { if(!busy.value) { onInput(""); ChatRepository.newConversation() } }
    fun openConversation(id:String) { if(!busy.value) { onInput(""); ChatRepository.select(id) } }
    fun retry(messageId:String) { if(!busy.value) viewModelScope.launch(Dispatchers.IO) { ChatRepository.retry(messageId) } }
    fun stop() {
        ChatRepository.recordAction(ActionEvent("cancel:"+conversationId.value,"Interrupção solicitada","Aguardando confirmação das tarefas remotas.","Punk","pending"))
        sendJob?.cancel(); BridgeService.stopAll(conversationId.value)
        VoiceManager.getOrCreateAndroidEngine(ctx).cancelListening()
        VoiceManager.getOrCreateAndroidEngine(ctx).stopSpeaking()

    }
    fun setSupervised(enabled:Boolean) {
        if(enabled) { stop(); BridgePrefs.setSupervised(ctx,true) }
        ControlPolicy.setSupervised(enabled)
    }
    fun respondApproval(id:String,allow:Boolean) { ControlPolicy.respond(id,allow) }
    fun openAttachment(uploadId:String,name:String,mime:String) {
        viewModelScope.launch {
            try {
                val file=withContext(Dispatchers.IO) { BridgeFiles.download(ctx,uploadId,name) }
                val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".files",file)
                val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch(e:Exception) { ChatRepository.recordAction(ActionEvent(UUID.randomUUID().toString(),"Não foi possível abrir o arquivo",e.message ?: "Instale um aplicativo compatível.","Celular","failed")) }
        }
    }
    fun exportConversation() {
        viewModelScope.launch {
            try {
                val dir=java.io.File(ctx.cacheDir,"received").apply { mkdirs() }
                val file=java.io.File(dir,"Punk-conversa.txt")
                withContext(Dispatchers.IO) { file.writeText(messages.value.joinToString("\n\n") { (if(it.role=="user")"Você" else "Punk")+":\n"+it.text }) }
                val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".files",file)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Exportar conversa").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch(e:Exception) { ChatRepository.recordAction(ActionEvent(UUID.randomUUID().toString(),"Exportação interrompida",e.message ?: "Tente novamente.","Celular","failed")) }
        }
    }
    private fun loadDrafts():List<AttachmentDraft> = runCatching { Json.parseToJsonElement(prefs.getString("attachments","[]") ?: "[]").jsonArray.map { element ->
        val p=element.jsonObject; AttachmentDraft(p.getValue("id").jsonPrimitive.content,p.getValue("name").jsonPrimitive.content,p.getValue("mimeType").jsonPrimitive.content,p.getValue("uri").jsonPrimitive.content,p["size"]?.jsonPrimitive?.longOrNull ?: 0)
    } }.getOrDefault(emptyList())
    private fun persistDrafts() { prefs.edit().putString("attachments",JsonArray(_attachments.value.map { a -> buildJsonObject { put("id",a.id); put("name",a.name); put("mimeType",a.mimeType); put("uri",a.uri); put("size",a.size) } }).toString()).apply() }
    companion object { fun appendMessage(message:ChatMessage) { ChatRepository.accept(BridgeMessage(UUID.randomUUID().toString(),"chat_message",buildJsonObject { put("conversationId",message.conversationId); put("text",message.text); put("role",message.role) })) } }
}
