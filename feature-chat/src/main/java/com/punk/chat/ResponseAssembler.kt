package com.punk.chat

/** Idempotent reduction of a single response; history snapshots share the same sequence boundary. */
object ResponseAssembler {
    fun apply(previous: ChatMessage?, conversationId: String, messageId: String, text: String, seq: Long,
              done: Boolean, status: String = "completed", error: String? = null): ChatMessage? {
        require(messageId.isNotBlank() && conversationId.isNotBlank())
        require(previous == null || (previous.role == "assistant" && previous.messageId == messageId && previous.conversationId == conversationId))
        if (previous != null && seq > 0 && seq <= previous.lastSeq) return null
        val content = (previous?.text ?: "") + text
        return ChatMessage(id=previous?.id ?: 0, conversationId=conversationId,role="assistant",messageId=messageId,
            text=if(content.isBlank() && error!=null) error else content,ts=previous?.ts ?: System.currentTimeMillis(),
            status=if(done)status else "running",lastSeq=seq)
    }
}
