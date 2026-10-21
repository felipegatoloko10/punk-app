package com.punk.bridge

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

class BridgeClient(
    private val context: Context,
    private var config: BridgeConfig,
    private val scope: CoroutineScope,
    private val onState: (BridgeState) -> Unit,
    private val onMessage: (BridgeMessage) -> Unit,
    private val toolExecutor: (suspend (tool: String, params: JsonObject, callId: String) -> ToolResult)? = null,
    private val catalog: JsonArray = JsonArray(emptyList())
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var authenticated = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var statusJob: Job? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val results = ConcurrentHashMap<String, ToolResult>()
    private val calls = context.getSharedPreferences("punk_executed_calls_v3", Context.MODE_PRIVATE)
    private var client = BridgeHttp.client(config).newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    fun connect() {
        if (closed) return
        if (config.token.isBlank() || config.deviceId.isBlank() || !config.useTls) {
            onState(BridgeState.Failed("Faça o pareamento seguro com o PC")); return
        }
        authenticated = false
        onState(BridgeState.Connecting)
        ws = client.newWebSocket(Request.Builder().url(config.wsUrl()).header("Authorization", "Bearer ${config.token}")
            .header("X-Punk-Device", config.deviceId).build(), Listener())
    }
    fun send(type: String, payload: JsonElement? = null): Boolean {
        if (!authenticated && type != BridgeTypes.HELLO && type != BridgeTypes.PONG) return false
        return ws?.send(json.encodeToString(BridgeMessage(UUID.randomUUID().toString(), type, payload))) ?: false
    }
    fun cancelTools(callIds: Set<String>? = null, acknowledge: Boolean = true) {
        val targets=jobs.entries.filter { callIds == null || it.key in callIds }.associate { it.key to it.value }
        val ids=callIds?.toList() ?: targets.keys.toList()
        targets.values.forEach { it.cancel() }
        ControlPolicy.cancelAll()
        BridgeService.stopLocalTools?.invoke()
        if (!acknowledge) return
        scope.launch {
            val stopped=CancellationBarrier.stop(targets.values.toList(), { BridgeService.stopLocalTools?.invoke() }, {
                BridgeService.awaitLocalToolsIdle?.invoke() ?: true
            })
            send(if(stopped) "cancel_ack" else "cancel_pending", buildJsonObject { put("callIds",JsonArray(ids.map(::JsonPrimitive))) })
        }
    }
    fun close() {
        closed = true; authenticated = false
        reconnectJob?.cancel(); handshakeJob?.cancel(); statusJob?.cancel(); cancelTools(acknowledge=false)
        ws?.close(1000, "Encerrado")
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        onState(BridgeState.Disconnected)
    }
    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) { webSocket.close(1000, "Encerrado"); return }
            send(BridgeTypes.HELLO, buildJsonObject {
                put("client", "punk-android"); put("version", "3.5.0"); put("protocolVersion", 3)
                put("deviceModel", Build.MODEL); put("androidVersion", Build.VERSION.RELEASE)
                put("deviceId", config.deviceId); put("lastSeq", BridgePrefs.lastSeq(context)); put("tools", catalog)
            })
            handshakeJob = scope.launch { delay(10_000); if (!authenticated) { onState(BridgeState.Failed("O PC não confirmou o pareamento")); webSocket.cancel() } }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.decodeFromString<BridgeMessage>(text) }.getOrNull() ?: return
            val p = msg.payload as? JsonObject ?: JsonObject(emptyMap())
            if (msg.type == BridgeTypes.HELLO_ACK) {
                if (p["authenticated"]?.jsonPrimitive?.booleanOrNull != true) {
                    onState(BridgeState.Failed("Bridge incompatível ou acesso recusado")); closed = true; webSocket.close(1008,"Autenticação necessária"); return
                }
                authenticated = true; attempt = 0; handshakeJob?.cancel()
                statusJob?.cancel()
                statusJob = scope.launch {
                    while (isActive && authenticated && !closed) {
                        delay(10_000)
                        send("status_request")
                    }
                }
                onState(BridgeState.Connected); onMessage(msg); return
            }
            if (!authenticated) return
            when (msg.type) {
                BridgeTypes.PING -> send(BridgeTypes.PONG)
                "cancel_tools" -> { cancelTools((p["callIds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()); BridgeService.stopLocalTools?.invoke() }
                BridgeTypes.TOOL_CALL -> {
                    val callId = p["callId"]?.jsonPrimitive?.content ?: msg.id
                    val tool = p["tool"]?.jsonPrimitive?.content ?: ""
                    val params = p["params"] as? JsonObject ?: JsonObject(emptyMap())
                    if(p["supervised"]?.jsonPrimitive?.booleanOrNull == true) ControlPolicy.applyMode(true)
                    results[callId]?.let { sendResult(it); return }
                    if (jobs.containsKey(callId)) return
                    // Persist before executing: a reconnect/process restart must never repeat an uncertain action.
                    if (calls.contains(callId)) { sendResult(ToolResult(callId,false,error="Chamada já recebida. Consulte o resultado antes de repetir.")); return }
                    if(calls.all.size>2000) {
                        calls.all.entries.sortedBy { (it.value as? Long) ?: 0L }.take(200).forEach { calls.edit().remove(it.key).apply() }
                    }
                    if(!calls.edit().putLong(callId, System.currentTimeMillis()).commit()) { sendResult(ToolResult(callId,false,error="Não foi possível registrar a chamada com segurança")); return }
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val result = withTimeout(300_000) { toolExecutor?.invoke(tool,params,callId) ?: ToolResult(callId,false,error="Executor indisponível") }
                            if(json.encodeToString(result).length < 65536) { if(results.size>=16) results.clear(); results[callId] = result }; sendResult(result)
                        } catch (e: CancellationException) {
                            sendResult(ToolResult(callId,false,error="Execução cancelada")); throw e
                        } catch (e: Exception) { sendResult(ToolResult(callId,false,error=e.message ?: "Falha ao executar")) }
                        finally { jobs.remove(callId) }
                    }
                    jobs[callId] = job; job.start()
                }
                else -> onMessage(msg)
            }
        }
        private fun sendResult(result: ToolResult) { send(BridgeTypes.TOOL_RESULT, json.encodeToJsonElement(ToolResult.serializer(),result)) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            authenticated = false; handshakeJob?.cancel(); statusJob?.cancel(); cancelTools(acknowledge=false); BridgeService.stopLocalTools?.invoke()
            if (response?.code == 401 || response?.code == 403) { closed = true; onState(BridgeState.Failed("Acesso revogado. Faça o pareamento novamente.")); return }
            onState(BridgeState.Failed("PC indisponível. Tentando reconectar…")); scheduleReconnect()
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code,reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false; handshakeJob?.cancel()
            statusJob?.cancel()
            if (closed) return
            cancelTools(acknowledge=false); BridgeService.stopLocalTools?.invoke()
            onState(BridgeState.Disconnected); scheduleReconnect()
        }
    }
    @Synchronized private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        attempt++
        val backoff = min(config.reconnectMaxMs, config.reconnectBaseMs * (1L shl min(attempt, 10)))
        reconnectJob = scope.launch {
            delay(backoff + (0..500).random())
            val target=runCatching { PunkAccount.reconnectTarget(context,config) }.getOrDefault("")
            if(target.isNotEmpty()) {
                val updated=runCatching { PunkAccount.connect(context,target) }.getOrNull()
                if(closed)return@launch
                if(updated!=null && updated!=config) {
                    client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
                    config=updated
                    client=BridgeHttp.client(config).newBuilder().pingInterval(20,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
                }
            } else if(BridgePrefs.autoFailover(context)) {
                val updated=ConnectionRoutes.reachable(BridgePrefs.pairRoutes(context,config))
                if(closed)return@launch
                if(updated!=null && updated!=config) {
                    client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
                    config=updated;BridgePrefs.save(context,updated)
                    client=BridgeHttp.client(config).newBuilder().pingInterval(20,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
                }
            }
            if (!closed) connect()
        }
    }
}
