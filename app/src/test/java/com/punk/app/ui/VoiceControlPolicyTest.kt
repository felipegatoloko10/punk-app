package com.punk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceControlPolicyTest {
    @Test
    fun processingReplyCanNeverBecomeCancelAction() {
        assertEquals(
            VoicePrimaryAction.WAIT_FOR_REPLY,
            VoiceControlPolicy.primaryAction(
                permissionGranted = true,
                speaking = false,
                processing = true,
                liveActive = true
            )
        )
    }

    @Test
    fun spokenReplyCanStillBeInterruptedExplicitly() {
        assertEquals(
            VoicePrimaryAction.INTERRUPT_SPEECH,
            VoiceControlPolicy.primaryAction(
                permissionGranted = true,
                speaking = true,
                processing = false,
                liveActive = true
            )
        )
    }
}
