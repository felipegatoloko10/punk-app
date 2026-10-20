package com.punk.chat

import android.content.Context
import androidx.room.Room
import com.punk.bridge.*
import com.punk.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Exactly one event consumer owns persistence, response assembly and speech for the application. */
object ChatRepository {
    private lateinit var context:Context
    private lateinit var db:ChatDb
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val events=Channel<BridgeMessage>(Channel.UNLIMITED)
    private val mutation=Mutex()
    private val _selected=MutableStateFlow("default")
    val selected:StateFlow<String> = _selected
    private val _ready=MutableStateFlow(false)
    private suspend fun awaitReady() { _ready.first { it } }
    private val _conversations=MutableStateFlow<List<Conversation>>(emptyList())
    val conversations:StateFlow<List<Conversation>> = _conversations
    private val _activity=MutableStateFlow<List<ActionEvent>>(emptyList())
    val activity:StateFlow<List<ActionEvent>> = _activity
    private val _messages=MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages:StateFlow<List<ChatMessage>> = _messages
    @Volatile private var started=false
    @Synchronized fun start(ctx:Context) {
        if(started) return
        started=true; context=ctx.applicationContext
        _selected.value=context.getSharedPreferences("punk_chat_ui",0).getString("conversation","default") ?: "default"
        db=Room.databaseBuilder(context,ChatDb::class.java,"punk_chat_v3.db")
            .addMigrations(ChatDb.MIGRATION_1_2)
            .build()
        BridgeService.messageConsumer=::accept
        scope.launch {
            db.dao().interruptStale()
            db.dao().interruptStaleActivity(
                System.currentTimeMillis(),
                "O app foi reiniciado ou perdeu a sessão antes de receber a confirmação final. A execução não está mais sendo acompanhada."
            )
            db.dao().insertConv(Conversation(_selected.value,"Nova conversa"))
            _ready.value=true
            for(event in events) {
                try { mutation.withLock { handle(event) } } catch(e:CancellationException) { throw e } catch(_:Exception) {
                    if(event.type!="task_event")recordAction(ActionEvent("event-error:${event.id}","Atualização interrompida","O evento será reconciliado ao reconectar.","Punk","failed"))
                }
            }
        }
        scope.launch { awaitReady(); db.dao().conversations().collect { _conversations.value=it } }
        scope.launch { awaitReady(); db.dao().activity().collect { _activity.value=it } }
        scope.launch { awaitReady(); selected.collectLatest { cid -> db.dao().messages(cid).collect { _messages.value=it } } }
        scope.launch { awaitReady(); BridgeService.state.collect { if(it is BridgeState.Connected) reconcile() } }
    }
    fun accept(message:BridgeMessage) { events.trySend(message) }
    fun recordAction(event:ActionEvent) { accept(BridgeMessage(event.id,"task_event",buildJsonObject {
        put("id",event.id); put("title",event.title); put("detail",event.detail); put("device",event.device); put("status",event.status); put("ts",event.ts)
    })) }
    fun select(id:String) { _selected.value=id; context.getSharedPreferences("punk_chat_ui",0).edit().putString("conversation",id).apply() }
    fun newConversation() {
        val id=java.util.UUID.randomUUID().toString()
        scope.launch { awaitReady(); db.dao().insertConv(Conversation(id,"Nova conversa")); select(id) }
    }
    private suspend fun put(message:ChatMessage) {
        val old=db.dao().message(message.messageId)
        db.dao().insert(message.copy(id=old?.id ?: message.id))
    }
    suspend fun send(text:String,spoken:Boolean,attachments:JsonArray=JsonArray(emptyList()),retryId:String?=null) {
        awaitReady()
        val cid=selected.value
        val message=ChatMessage(conversationId=cid,role="user",text=text,messageId=retryId ?: java.util.UUID.randomUUID().toString(),status="sending",spoken=spoken,attachmentsJson=attachments.toString())
        mutation.withLock { put(message) }
        if(db.dao().allConversations().firstOrNull { it.id==cid }?.title=="Nova conversa") db.dao().rename(cid,text.take(48).ifBlank { "Arquivos" })
        val sent=BridgeService.sendMessage(BridgeTypes.CHAT,buildJsonObject {
            put("conversationId",cid); put("messageId",message.messageId); put("text",text); put("spoken",spoken); put("attachments",attachments)
        })
        if(!sent) mutation.withLock { put(message.copy(status="failed")) }
    }
    suspend fun retry(id:String) {
        awaitReady()
        val message=db.dao().message(id) ?: return
        if(message.role!="user") return
        select(message.conversationId)
        send(message.text,message.spoken,runCatching { Json.parseToJsonElement(message.attachmentsJson).jsonArray }.getOrDefault(JsonArray(emptyList())),message.messageId)
    }
    private suspend fun handle(event:BridgeMessage) {
        val p=event.payload as? JsonObject ?: return
        val seq=p["seq"]?.jsonPrimitive?.longOrNull ?: 0
        when(event.type) {
            BridgeTypes.CHAT_CHUNK -> {
                val cid=p["conversationId"]?.jsonPrimitive?.content ?: return
                val mid=p["messageId"]?.jsonPrimitive?.content ?: return
                val requestId=p["requestId"]?.jsonPrimitive?.content ?: ""
                val previous=db.dao().message(mid)
                if(seq>0 && previous!=null && seq<=previous.lastSeq) return
                val user=db.dao().message(requestId)
                val done=p["done"]?.jsonPrimitive?.booleanOrNull ?: false
                val status=if(done) p["status"]?.jsonPrimitive?.content ?: "completed" else "running"
                val delta=p["text"]?.jsonPrimitive?.content ?: ""
                val error=p["error"]?.jsonPrimitive?.content
                val reply=ResponseAssembler.apply(previous,cid,mid,delta,seq,done,status,error) ?: return
                val text=reply.text
                db.dao().insertConv(Conversation(cid,"Conversa recuperada")); put(reply)
                if(user!=null) put(user.copy(status=if(done && status!="completed") status else "completed"))
                if(done && status=="completed" && user?.spoken==true && previous?.status!="completed" && text.isNotBlank()) {
                    withContext(Dispatchers.Main) { VoiceManager.getOrCreateAndroidEngine(context).speakText(text) }
                }
            }
            "task_event" -> {
                val ts=p["ts"]?.jsonPrimitive?.doubleOrNull ?: (System.currentTimeMillis()/1000.0)
                val updatedAt=if(ts<1e12) (ts*1000).toLong() else ts.toLong()
                val id=p["id"]?.jsonPrimitive?.content ?: event.id
                val previous=db.dao().event(id)
                val incomingDetail=p["detail"]?.jsonPrimitive?.content.orEmpty()
                val detail=when {
                    incomingDetail.isBlank() -> previous?.detail.orEmpty()
                    previous?.detail.isNullOrBlank() || previous?.detail==incomingDetail -> incomingDetail
                    else -> previous!!.detail+"\n\n"+incomingDetail
                }
                db.dao().insertEvent(ActionEvent(id,p["title"]?.jsonPrimitive?.content ?: "Tarefa",
                    detail,p["device"]?.jsonPrimitive?.content ?: "PC",
                    p["status"]?.jsonPrimitive?.content ?: "running",previous?.ts ?: updatedAt,updatedAt))
            }
            "file_received" -> {
                val cid=p["conversationId"]?.jsonPrimitive?.content ?: selected.value
                val fileId=p["uploadId"]?.jsonPrimitive?.content ?: return
                put(ChatMessage(conversationId=cid,role="assistant",text="Arquivo recebido do PC",messageId="file:$fileId",attachmentsJson=JsonArray(listOf(p)).toString(),lastSeq=seq))
            }
            BridgeTypes.ERROR -> db.dao().insertEvent(ActionEvent(event.id,"Conexão ou execução",p["message"]?.jsonPrimitive?.content ?: p["error"]?.jsonPrimitive?.content ?: "Não foi possível concluir.","Punk","failed"))
        }
        if(seq>0) BridgePrefs.setLastSeq(context,seq)
    }
    private suspend fun reconcile() {
        val config=BridgePrefs.load(context)
        if(config.deviceId.isBlank()) return
        runCatching { BridgeHttp.client(config).useClient { http ->
            for(conversation in db.dao().allConversations()) {
                http.newCall(BridgeHttp.request(config,"/api/conversations/${conversation.id}/messages").build()).execute().use { response ->
                    if(!response.isSuccessful) return@use
                    val body=Json.parseToJsonElement(response.body!!.string()).jsonObject
                    mutation.withLock {
                    for(item in body["messages"]?.jsonArray ?: JsonArray(emptyList())) {
                        val p=item.jsonObject
                        val mid=p["id"]?.jsonPrimitive?.content ?: p["messageId"]?.jsonPrimitive?.content ?: continue
                        val old=db.dao().message(mid)
                        val ts=p["createdAt"]?.jsonPrimitive?.doubleOrNull ?: p["ts"]?.jsonPrimitive?.doubleOrNull ?: (System.currentTimeMillis()/1000.0)
                        val snapshotSeq=body["lastSeq"]?.jsonPrimitive?.longOrNull ?: 0
                        if(old != null && old.lastSeq > snapshotSeq) continue
                        put(ChatMessage(conversationId=conversation.id,role=p["role"]?.jsonPrimitive?.content ?: "assistant",text=p["text"]?.jsonPrimitive?.content ?: "",
                            messageId=mid,status=p["status"]?.jsonPrimitive?.content ?: "completed",spoken=old?.spoken ?: false,ts=old?.ts ?: if(ts<1e12)(ts*1000).toLong() else ts.toLong(),lastSeq=maxOf(old?.lastSeq ?: 0,snapshotSeq),attachmentsJson=old?.attachmentsJson ?: "[]"))
                    }
                    }
                }
            }
        } }
    }
}
