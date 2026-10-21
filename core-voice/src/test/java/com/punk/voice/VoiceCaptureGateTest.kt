package com.punk.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class VoiceCaptureGateTest {
    @Test fun speakerPlaybackNeverOpensMicrophoneEvenForLongResponses() {
        val gate = VoiceCaptureGate()
        gate.playbackStarted()
        assertFalse(gate.mayListen(1_000))
        assertFalse(gate.mayListen(600_000))
    }

    @Test fun naturalCompletionAndManualStopBothWaitForSpeakerEchoToFade() {
        val gate = VoiceCaptureGate()
        gate.playbackStarted()
        gate.playbackEnded(5_000)
        assertFalse(gate.mayListen(5_899))
        assertEquals(900L, gate.remainingDelayMs(5_000))
        assertTrue(gate.mayListen(5_900))
    }

    @Test fun repeatedStopWhileIdleDoesNotPostponeNextTurn() {
        val gate = VoiceCaptureGate()
        gate.playbackStarted()
        gate.playbackEnded(1_000)
        gate.playbackEnded(1_800)
        assertTrue(gate.mayListen(1_900))
    }

    @Test fun newPlaybackBlocksRecognitionDuringPreviousEchoTail() {
        val gate = VoiceCaptureGate()
        gate.playbackStarted()
        gate.playbackEnded(1_000)
        gate.playbackStarted()
        assertFalse(gate.mayListen(2_000))
        gate.playbackEnded(3_000)
        assertFalse(gate.mayListen(3_899))
        assertTrue(gate.mayListen(3_900))
    }
}
