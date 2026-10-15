package com.punk.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class BridgeClient(
    private val config: BridgeConfig,
    private val scope: CoroutineScope,
    private val onState: (BridgeState) -> Unit,
    private val onMessage: (BridgeMessage) -> Unit,
    private val toolExecutor: (suspend (tool: String, params: JsonObject, callId: String) -> ToolResult)? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ws: WebSocket? = null
    private var closed = false
    private var attempt = 0

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (closed) return
        onState(BridgeState.Connecting)
        val req = Request.Builder().url(config.wsUrl()).build()
        val builder = req.newBuilder()
        if (config.token.isNotEmpty()) builder.header("Authorization", "Bearer ${config.token}")
        ws = client.newWebSocket(builder.build(), Listener())
    }

    fun send(type: String, payload: JsonElement? = null) {
        val msg = BridgeMessage(id = UUID.randomUUID().toString(), type = type, payload = payload)
        val text = json.encodeToString(msg)
        ws?.send(text)
    }

    fun close() {
        closed = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        client.dispatcher.executorService.shutdown()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            onState(BridgeState.Connected)
            val payload = buildJsonObject {
                put("client", "punk-android")
                put("version", "2.0.0")
                put("token", config.token)
            }
            send(BridgeTypes.HELLO, payload)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = json.decodeFromString<BridgeMessage>(text)
                when (msg.type) {
                    BridgeTypes.PING -> send(BridgeTypes.PONG, null)
                    BridgeTypes.TOOL_CALL -> {
                        scope.launch {
                            try {
                                val payloadObj = msg.payload as? JsonObject
                                val tool = payloadObj?.get("tool")?.let { (it as? JsonPrimitive)?.content } ?: ""
                                val params = (payloadObj?.get("params") as? JsonObject) ?: JsonObject(emptyMap())
                                val callId = payloadObj?.get("callId")?.let { (it as? JsonPrimitive)?.content } ?: msg.id

                                val result = toolExecutor?.invoke(tool, params, callId)
                                    ?: ToolResult(callId = callId, ok = false, error = "Nenhum executor de ferramentas registrado")

                                val resPayload = json.encodeToJsonElement(ToolResult.serializer(), result)
                                send(BridgeTypes.TOOL_RESULT, resPayload)
                            } catch (e: Exception) {
                                val errResult = ToolResult(callId = msg.id, ok = false, error = e.message ?: "Erro ao executar tool")
                                val resPayload = json.encodeToJsonElement(ToolResult.serializer(), errResult)
                                send(BridgeTypes.TOOL_RESULT, resPayload)
                            }
                        }
                    }
                    else -> {
                        onMessage(msg)
                    }
                }
            } catch (_: Exception) {}
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            onState(BridgeState.Failed(t.message ?: "ws failure"))
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) {
                onState(BridgeState.Disconnected)
                return
            }
            onState(BridgeState.Disconnected)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closed) return
        attempt++
        val base = config.reconnectBaseMs
        val max = config.reconnectMaxMs
        val backoff = min(max, base * (1L shl min(attempt, 10)))
        val jitter = (Math.random() * 500).toLong()
        Thread {
            try { Thread.sleep(backoff + jitter) } catch (_: InterruptedException) {}
            if (!closed) connect()
        }.start()
    }
}
