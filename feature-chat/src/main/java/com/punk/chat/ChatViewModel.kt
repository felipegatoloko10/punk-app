package com.punk.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.punk.bridge.BridgeMessage
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState
import com.punk.bridge.BridgeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatViewModel : ViewModel() {
    companion object {
        private val _messages = MutableStateFlow<List<ChatMessage>>(
            listOf(
                ChatMessage(
                    conversationId = "default",
                    role = "assistant",
                    text = "Mr. Punk (JARVIS) online. Às suas ordens, Sir."
                )
            )
        )
        val messages: StateFlow<List<ChatMessage>> = _messages

        fun appendMessage(m: ChatMessage) {
            _messages.value = _messages.value + m
        }

        fun appendChunk(chunk: String) {
            val currentList = _messages.value
            val last = currentList.lastOrNull()
            if (last != null && last.role == "assistant") {
                val updated = last.copy(text = last.text + chunk)
                _messages.value = currentList.dropLast(1) + updated
            } else {
                val newMsg = ChatMessage(conversationId = "default", role = "assistant", text = chunk)
                _messages.value = currentList + newMsg
            }
        }
    }

    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    val bridgeState: StateFlow<BridgeState> = BridgeService.state

    init {
        viewModelScope.launch {
            BridgeService.incomingMessages.collect { msg ->
                handleIncomingMessage(msg)
            }
        }
    }

    fun onInput(v: String) { _input.value = v }

    fun send() {
        val t = _input.value.trim()
        if (t.isEmpty()) return

        val m = ChatMessage(conversationId = "default", role = "user", text = t)
        appendMessage(m)
        _input.value = ""

        if (bridgeState.value is BridgeState.Connected) {
            val payload = buildJsonObject {
                put("role", "user")
                put("text", t)
                put("conversationId", "default")
            }
            BridgeService.sendMessage(BridgeTypes.CHAT, payload)
        } else {
            viewModelScope.launch {
                val reply = ChatMessage(
                    conversationId = "default",
                    role = "assistant",
                    text = "PUNK (offline): Sem conexão ativa com o Bridge do PC. Verifique o status na aba Config."
                )
                _messages.value = _messages.value + reply
            }
        }
    }

    private fun handleIncomingMessage(msg: BridgeMessage) {
        when (msg.type) {
            BridgeTypes.CHAT, "chat_message" -> {
                val payloadObj = msg.payload as? JsonObject
                val role = payloadObj?.get("role")?.let { (it as? JsonPrimitive)?.content } ?: "assistant"
                val text = payloadObj?.get("text")?.let { (it as? JsonPrimitive)?.content }
                    ?: payloadObj?.get("content")?.let { (it as? JsonPrimitive)?.content }
                    ?: ""
                if (text.isNotEmpty()) {
                    val reply = ChatMessage(conversationId = "default", role = role, text = text)
                    appendMessage(reply)
                }
            }
            BridgeTypes.CHAT_CHUNK -> {
                val payloadObj = msg.payload as? JsonObject
                val textChunk = payloadObj?.get("text")?.let { (it as? JsonPrimitive)?.content } ?: ""
                if (textChunk.isNotEmpty()) {
                    appendChunk(textChunk)
                }
            }
        }
    }
}
