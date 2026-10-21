package com.punk.app

import com.punk.chat.ChatMessage

/** Acknowledging a user message is not completion of the assistant's turn. */
internal object VoiceReplyPolicy {
    fun terminalStatus(requestId: String, messages: List<ChatMessage>): String? {
        val user = messages.lastOrNull { it.messageId == requestId }
        if (user?.status in setOf("failed", "cancelled", "interrupted")) return user?.status
        val reply = messages.lastOrNull { it.role == "assistant" && it.messageId == "assistant:$requestId" }
        return reply?.status?.takeIf { it in setOf("completed", "failed", "cancelled", "interrupted") }
    }
}
