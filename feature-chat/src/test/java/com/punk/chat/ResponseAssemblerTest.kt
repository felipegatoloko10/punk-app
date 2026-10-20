package com.punk.chat

import org.junit.Assert.*
import org.junit.Test

class ResponseAssemblerTest {
    @Test fun replayDoesNotDuplicateTokens() {
        val first=ResponseAssembler.apply(null,"a","reply:1","Olá",10,false)!!
        assertNull(ResponseAssembler.apply(first,"a","reply:1","Olá",10,false))
        assertNull(ResponseAssembler.apply(first,"a","reply:1","antigo",9,false))
        assertEquals("Olá!",ResponseAssembler.apply(first,"a","reply:1","!",11,true)!!.text)
    }
    @Test fun authoritativeHistoryBoundarySkipsOldDeltas() {
        val snapshot=ChatMessage(conversationId="a",role="assistant",messageId="reply:1",text="Resposta completa",lastSeq=200)
        assertNull(ResponseAssembler.apply(snapshot,"a","reply:1","Resposta",150,false))
    }
    @Test(expected=IllegalArgumentException::class) fun cannotAppendToAnotherConversation() {
        ResponseAssembler.apply(ChatMessage(conversationId="a",role="assistant",messageId="r",text="privado"),"b","r","x",1,false)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotOverwriteUserMessage() {
        ResponseAssembler.apply(ChatMessage(conversationId="a",role="user",messageId="r",text="pedido"),"a","r","x",1,false)
    }
    @Test fun terminalErrorHasHonestStatus() {
        val response=ResponseAssembler.apply(null,"a","reply:1","",1,true,"failed","PC indisponível")!!
        assertEquals("failed",response.status); assertEquals("PC indisponível",response.text)
    }
}
