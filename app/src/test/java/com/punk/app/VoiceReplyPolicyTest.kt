package com.punk.app

import com.punk.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReplyPolicyTest {
    private val acknowledged = ChatMessage(conversationId="c", role="user", messageId="r", text="Olá", status="completed")

    @Test fun acknowledgedRequestAndStreamingResponseMustKeepMicrophoneWaiting() {
        assertNull(VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged)))
        val streaming = ChatMessage(conversationId="c", role="assistant", messageId="assistant:r", text="Estou", status="running")
        assertNull(VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged, streaming)))
        assertEquals("completed", VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged, streaming.copy(status="completed"))))
    }

    @Test fun completionOfAnotherTurnMustNotReopenMicrophone() {
        val unrelated = ChatMessage(conversationId="c", role="assistant", messageId="assistant:old", text="Anterior")
        assertNull(VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged, unrelated)))
    }

    @Test fun failedReplyOrFailedSendBothReleaseWaitingState() {
        assertEquals("failed", VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged.copy(status="failed"))))
        val failed = ChatMessage(conversationId="c", role="assistant", messageId="assistant:r", text="", status="interrupted")
        assertEquals("interrupted", VoiceReplyPolicy.terminalStatus("r", listOf(acknowledged, failed)))
    }
}
