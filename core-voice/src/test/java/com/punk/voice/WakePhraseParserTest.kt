package com.punk.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class WakePhraseParserTest {
    @Test fun sendsImmediatelyWhenWakePhraseIsDisabled() {
        assertEquals(WakePhraseResult(WakeAction.SEND, "abra a câmera"), WakePhraseParser.parse("abra a câmera", false, 0, 100))
    }

    @Test fun ignoresSpeechWithoutWakePhrase() {
        assertEquals(WakeAction.IGNORE, WakePhraseParser.parse("conversa ao fundo", true, 0, 100).action)
    }

    @Test fun armsOnWakePhraseOnly() {
        assertEquals(WakeAction.ARM, WakePhraseParser.parse("Ei, Punk!", true, 0, 100).action)
    }

    @Test fun extractsCommandAfterWakePhrase() {
        assertEquals("abra o mapa", WakePhraseParser.parse("Punk, abra o mapa", true, 0, 100).text)
    }

    @Test fun sendsNextUtteranceWhileArmed() {
        assertEquals(WakePhraseResult(WakeAction.SEND, "qual é a bateria?"), WakePhraseParser.parse("qual é a bateria?", true, 200, 100))
    }
}
