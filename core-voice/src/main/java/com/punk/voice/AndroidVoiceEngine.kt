package com.punk.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

sealed interface VoiceStatus {
    object Idle : VoiceStatus
    object Ready : VoiceStatus
    object Listening : VoiceStatus
    object Processing : VoiceStatus
    data class Speaking(val text: String) : VoiceStatus
    data class Error(val code: Int, val message: String) : VoiceStatus
}
data class VoiceAvailability(val recognitionAvailable: Boolean, val ttsReady: Boolean, val initializing: Boolean)

/** One main-thread owner for recognition, audio focus and TTS; no transcript logging. */
class AndroidVoiceEngine(private val context: Context) : VoiceEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var utteranceId: String? = null
    private var onDone: (() -> Unit)? = null
    private var generation = 0
    private var recognitionTimeout: Runnable? = null
    private var destroyed = false
    private var ttsReady = false
    @Volatile private var listening = false
    @Volatile private var speaking = false
    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val status = _status.asStateFlow()
    private val _amplitude = MutableStateFlow(0f)
    val amplitudeFlow = _amplitude.asStateFlow()
    private val _availability = MutableStateFlow(VoiceAvailability(false, false, true))
    val availability: StateFlow<VoiceAvailability> = _availability.asStateFlow()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) { cancelListening(); stopSpeaking() } }
        .build()

    init {
        main {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            _availability.value = VoiceAvailability(available, false, true)
            tts = TextToSpeech(context.applicationContext) { result ->
                handler.post {
                    if (destroyed) return@post
                    val language = if (result == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("pt", "BR")) else null
                    ttsReady = result == TextToSpeech.SUCCESS && language != null && language >= TextToSpeech.LANG_AVAILABLE
                    _availability.value = VoiceAvailability(available, ttsReady, false)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main { finishUtterance(id, null) } }
                        @Deprecated("Legacy callback") override fun onError(id: String?) { main { finishUtterance(id, "Falha na síntese de voz") } }
                        override fun onError(id: String?, code: Int) { main { finishUtterance(id, "Falha na síntese de voz ($code)") } }
                    })
                }
            }
        }
    }
    private fun main(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block) }
    private fun focus(): Boolean = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    private fun releaseFocus() { audioManager.abandonAudioFocusRequest(focusRequest) }
    private fun finishRecognition() {
        generation++
        recognitionTimeout?.let { handler.removeCallbacks(it) }; recognitionTimeout = null
        runCatching { recognizer?.destroy() }; recognizer = null
        listening = false; _amplitude.value = 0f
    }
    private fun finishUtterance(id: String?, error: String?) {
        if (id == null || id != utteranceId) return
        utteranceId = null
        speaking = false
        _status.value = if (error == null) VoiceStatus.Idle else VoiceStatus.Error(-1, error)
        releaseFocus()
        val callback = onDone
        onDone = null
        callback?.invoke()
    }

    override suspend fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        startListeningWithCallbacks(onPartial, onFinal)
    }
    fun startListeningWithCallbacks(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        main {
            if (destroyed) { onError?.invoke("Motor de voz encerrado"); return@main }
            if (listening || recognizer != null) return@main
            stopSpeaking()
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            _availability.value = _availability.value.copy(recognitionAvailable = available)
            if (!available) {
                val error = "Reconhecimento de voz indisponível. Ative um serviço de voz nas configurações do Android."
                _status.value = VoiceStatus.Error(-1, error); onError?.invoke(error); return@main
            }
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val error = "Conceda permissão de microfone ao PUNK."
                _status.value = VoiceStatus.Error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, error); onError?.invoke(error); return@main
            }
            try {
                val currentGeneration = ++generation
                recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        private fun current() = currentGeneration == generation && !destroyed
                        override fun onReadyForSpeech(params: Bundle?) { if (current()) { listening = true; _status.value = VoiceStatus.Listening } }
                        override fun onBeginningOfSpeech() { if (current()) _status.value = VoiceStatus.Listening }
                        override fun onRmsChanged(rmsdB: Float) { if (current()) _amplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0f,1f) }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { if (current()) { listening = false; _amplitude.value = 0f; _status.value = VoiceStatus.Processing } }
                        override fun onError(error: Int) {
                            if (!current()) return
                            finishRecognition()
                            val message = "${getErrorMessage(error)} (código $error)"
                            _status.value = VoiceStatus.Error(error,message); onError?.invoke(message)
                        }
                        override fun onResults(results: Bundle?) {
                            if (!current()) return
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                            finishRecognition(); _status.value = VoiceStatus.Idle
                            if (text.isNotBlank()) onFinal(text) else { val message = "Nenhuma fala reconhecida"; _status.value = VoiceStatus.Error(-1,message); onError?.invoke(message) }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            if (!current()) return
                            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            if (text.isNotBlank()) onPartial(text)
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    })
                }
                listening = true
                _status.value = VoiceStatus.Ready
                recognitionTimeout = Runnable {
                    if (generation == currentGeneration) {
                        cancelListening()
                        val error = "Reconhecimento excedeu o tempo limite. Toque para tentar de novo."
                        _status.value = VoiceStatus.Error(-1,error); onError?.invoke(error)
                    }
                }.also { handler.postDelayed(it,45000) }
            } catch (_: Exception) {
                finishRecognition()
                val error = "Não foi possível iniciar o serviço de reconhecimento de voz"
                _status.value = VoiceStatus.Error(-1,error); onError?.invoke(error)
            }
        }
    }
    override suspend fun stopListening() { main { runCatching { recognizer?.stopListening() }; listening = false; _amplitude.value = 0f; _status.value = if(recognizer == null) VoiceStatus.Idle else VoiceStatus.Processing } }
    fun cancelListening() {
        main {
            runCatching { recognizer?.cancel() }; finishRecognition()
            if (!speaking) _status.value = VoiceStatus.Idle
        }
    }
    override suspend fun speak(text: String, onDone: () -> Unit) { speakText(text,onDone) }
    fun speakText(text: String, onDone: (() -> Unit)? = null) {
        main {
            if (destroyed || text.isBlank()) { onDone?.invoke(); return@main }
            cancelListening()
            stopSpeaking()
            if (!ttsReady) { _status.value = VoiceStatus.Error(-1,"Voz ainda não está pronta. Verifique o mecanismo TTS do Android."); onDone?.invoke(); return@main }
            if (!focus()) { _status.value = VoiceStatus.Error(-1,"Áudio ocupado por outro aplicativo"); onDone?.invoke(); return@main }
            val id = UUID.randomUUID().toString()
            utteranceId = id; this.onDone = onDone; speaking = true; _status.value = VoiceStatus.Speaking(text)
            tts?.setSpeechRate(1.05f)
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) finishUtterance(id,"Não foi possível falar a resposta")
        }
    }
    fun stopSpeaking() { main { utteranceId = null; onDone = null; tts?.stop(); speaking = false; _status.value = VoiceStatus.Idle; releaseFocus() } }
    override fun isListening(): Boolean = listening
    fun isSpeaking(): Boolean = speaking
    override fun amplitude(): Float = _amplitude.value
    fun destroy() {
        main {
            cancelListening(); stopSpeaking(); destroyed = true; tts?.shutdown(); tts = null; ttsReady = false
            _availability.value = VoiceAvailability(false,false,false)
        }
    }
    private fun getErrorMessage(code: Int): String = when(code) {
        SpeechRecognizer.ERROR_AUDIO -> "Erro de áudio"
        SpeechRecognizer.ERROR_CLIENT -> "O serviço de voz recusou a sessão"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone ausente"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Rede indisponível para reconhecimento de voz"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nenhuma fala reconhecida"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Serviço de voz ocupado"
        SpeechRecognizer.ERROR_SERVER -> "Falha no serviço de reconhecimento"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Serviço de reconhecimento desconectado"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Muitas tentativas de reconhecimento em sequência"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Português do Brasil não é aceito pelo serviço de voz"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "O pacote de voz pt-BR não está instalado"
        else -> "Falha no reconhecimento ($code)"
    }
}
