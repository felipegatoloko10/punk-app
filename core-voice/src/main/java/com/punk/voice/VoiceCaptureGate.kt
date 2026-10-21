package com.punk.voice

/** Half-duplex gate. Availability of hardware AEC is not proof that TTS echo is removed. */
class VoiceCaptureGate(private val echoTailMs: Long = 900) {
    private var playbackActive = false
    private var blockedUntil = 0L

    fun playbackStarted() { playbackActive = true }

    fun playbackEnded(nowMs: Long) {
        if (playbackActive) blockedUntil = nowMs + echoTailMs
        playbackActive = false
    }

    fun mayListen(nowMs: Long): Boolean = !playbackActive && nowMs >= blockedUntil

    fun remainingDelayMs(nowMs: Long): Long = (blockedUntil - nowMs).coerceAtLeast(0)
}
