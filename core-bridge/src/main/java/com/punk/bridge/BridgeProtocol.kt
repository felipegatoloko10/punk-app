package com.punk.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class BridgeMessage(
    val id: String,
    val type: String,
    val payload: JsonElement? = null
)

@Serializable
data class ToolCall(
    val tool: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val callId: String = ""
)

@Serializable
data class ToolResult(
    val callId: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null
)

@Serializable
data class ChatChunk(
    val conversationId: String,
    val text: String,
    val done: Boolean = false
)

object BridgeTypes {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val PING = "ping"
    const val PONG = "pong"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val CHAT = "chat"
    const val CHAT_CHUNK = "chat_chunk"
    const val DEVICE_STATUS = "device_status"
    const val ERROR = "error"
}
