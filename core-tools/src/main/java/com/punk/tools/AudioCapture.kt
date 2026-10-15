package com.punk.tools

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object AudioCapture {

    @Suppress("DEPRECATION")
    suspend fun recordSnippet(ctx: Context, durationSec: Int = 10): Map<String, Any> = withContext(Dispatchers.IO) {
        val duration = durationSec.coerceIn(2, 60)
        val outFile = File(ctx.cacheDir, "audio_capture_${System.currentTimeMillis()}.m4a")

        var recorder: MediaRecorder? = null
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(outFile.absolutePath)

            recorder.prepare()
            recorder.start()

            delay(duration * 1000L)

            try {
                recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
            recorder = null

            if (outFile.exists() && outFile.length() > 0) {
                val bytes = outFile.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                outFile.delete()
                mapOf(
                    "ok" to true,
                    "durationSec" to duration,
                    "sizeBytes" to bytes.size,
                    "mime" to "audio/mp4",
                    "base64" to b64
                )
            } else {
                mapOf("ok" to false, "error" to "Arquivo de gravação vazio ou inexistente")
            }
        } catch (e: Exception) {
            recorder?.release()
            outFile.delete()
            mapOf("ok" to false, "error" to (e.message ?: "Falha ao gravar áudio"))
        }
    }
}
