package com.punk.tools

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object AudioCapture {
    private val mutex = Mutex()
    private val active = AtomicReference<MediaRecorder?>()
    fun cancelAll() { active.getAndSet(null)?.let { runCatching { it.stop() }; runCatching { it.release() } } }

    @Suppress("DEPRECATION")
    suspend fun recordSnippet(ctx: Context, durationSec: Int = 10): Map<String, Any> = withContext(Dispatchers.IO) {
        ToolRuntime.captureError(ctx, Manifest.permission.RECORD_AUDIO)?.let { return@withContext mapOf("ok" to false, "error" to it) }
        if (!mutex.tryLock()) return@withContext mapOf("ok" to false, "error" to "Já existe uma gravação em andamento")
        val output = File(ctx.cacheDir, "punk_audio_${System.nanoTime()}.m4a")
        var recorder: MediaRecorder? = null
        try {
            val duration = durationSec.coerceIn(2, 60)
            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            active.set(recorder)
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(duration * 1000L)
            if (active.get() !== recorder) return@withContext mapOf("ok" to false, "error" to "Gravação cancelada")
            recorder.stop()
            val bytes = output.readBytes()
            if (bytes.isEmpty()) mapOf("ok" to false, "error" to "Gravação vazia")
            else mapOf("ok" to true, "durationSec" to duration, "sizeBytes" to bytes.size, "mime" to "audio/mp4",
                "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { mapOf("ok" to false, "error" to (e.message ?: "Falha ao gravar áudio")) }
        finally {
            if (active.compareAndSet(recorder, null)) runCatching { recorder?.release() }
            output.delete()
            mutex.unlock()
        }
    }
}
