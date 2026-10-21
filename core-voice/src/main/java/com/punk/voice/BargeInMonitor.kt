package com.punk.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Detects nearby speech while TTS is playing. It is enabled only when hardware AEC exists. */
class BargeInMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var recorder: AudioRecord? = null
    private var job: Job? = null

    val supported: Boolean
        get() = AcousticEchoCanceler.isAvailable()

    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit = {}, onDetected: () -> Unit) {
        stop()
        if (!supported) return
        job = scope.launch {
            val rate = 16_000
            val minimum = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) return@launch
            val audio = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, rate / 2)
                )
            }.getOrNull() ?: return@launch
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                return@launch
            }
            recorder = audio
            val echo = runCatching { AcousticEchoCanceler.create(audio.audioSessionId) }.getOrNull()
            val noise = runCatching { NoiseSuppressor.create(audio.audioSessionId) }.getOrNull()
            runCatching { echo?.enabled = true }
            runCatching { noise?.enabled = true }
            val samples = ShortArray(640)
            val ignoreUntil = System.currentTimeMillis() + 800
            var consecutive = 0
            try {
                audio.startRecording()
                while (isActive && audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    var sum = 0.0
                    for (index in 0 until count) {
                        val normalized = samples[index] / 32768.0
                        sum += normalized * normalized
                    }
                    val level = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
                    onLevel(level)
                    if (System.currentTimeMillis() >= ignoreUntil && level >= 0.075f) consecutive++ else consecutive = 0
                    if (consecutive >= 4) {
                        onDetected()
                        break
                    }
                }
            } catch (_: Throwable) {
                // Barge-in is an optional enhancement; the manual interrupt remains available.
            } finally {
                runCatching { audio.stop() }
                echo?.release()
                noise?.release()
                audio.release()
                if (recorder === audio) recorder = null
                onLevel(0f)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        recorder?.let { runCatching { it.stop() } }
        recorder = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
