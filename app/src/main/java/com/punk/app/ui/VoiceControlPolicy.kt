package com.punk.app.ui

internal enum class VoicePrimaryAction {
    REQUEST_PERMISSION,
    INTERRUPT_SPEECH,
    WAIT_FOR_REPLY,
    PAUSE_LIVE,
    START_LIVE
}

internal object VoiceControlPolicy {
    fun primaryAction(
        permissionGranted: Boolean,
        speaking: Boolean,
        processing: Boolean,
        liveActive: Boolean
    ): VoicePrimaryAction = when {
        !permissionGranted -> VoicePrimaryAction.REQUEST_PERMISSION
        speaking -> VoicePrimaryAction.INTERRUPT_SPEECH
        processing -> VoicePrimaryAction.WAIT_FOR_REPLY
        liveActive -> VoicePrimaryAction.PAUSE_LIVE
        else -> VoicePrimaryAction.START_LIVE
    }
}
