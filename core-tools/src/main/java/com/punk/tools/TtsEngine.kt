package com.punk.tools

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

object TtsEngine {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val mutex = Mutex()
    private suspend fun getEngine(ctx: Context): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        if (tts != null && ready) return@withContext tts
        tts?.shutdown()
        suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(ctx.applicationContext) { result ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val language = if (result == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("pt","BR")) else null
                    ready = language != null && language >= TextToSpeech.LANG_AVAILABLE
                    if (continuation.isActive) continuation.resume(if (ready) tts else null)
                }
            }
            continuation.invokeOnCancellation { stop() }
        }
    }
    suspend fun speak(ctx: Context, text: String, pitch: Float = 1f, rate: Float = 1f): Boolean {
        if (!mutex.tryLock()) return false
        try {
            return withTimeoutOrNull(120000) {
                val engine = withTimeoutOrNull(5000) { getEngine(ctx) } ?: return@withTimeoutOrNull false
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { continuation ->
                        val id = UUID.randomUUID().toString()
                        engine.setPitch(pitch.coerceIn(.5f,2f)); engine.setSpeechRate(rate.coerceIn(.5f,2f))
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(true) }
                            @Deprecated("Legacy callback") override fun onError(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(false) }
                        })
                        continuation.invokeOnCancellation { stop() }
                        if (engine.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle(),id) != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        } finally { mutex.unlock() }
    }
    fun stop() { android.os.Handler(android.os.Looper.getMainLooper()).post { tts?.stop() } }
}
