package com.punk.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeTypes
import com.punk.chat.ChatRepository
import com.punk.voice.AndroidVoiceEngine
import com.punk.voice.VoiceManager
import com.punk.voice.VoiceStatus
import com.punk.voice.WakeAction
import com.punk.voice.WakePhraseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class LiveVoiceState(
    val active: Boolean = false,
    val phase: String = "idle",
    val transcript: String = "",
    val lastFinal: String = "",
    val error: String? = null,
    val wakePhraseEnabled: Boolean = false,
    val wakePhraseArmed: Boolean = false,
    val onDeviceRecognition: Boolean = false,
    val bargeInSupported: Boolean = false,
    val batteryAtStart: Int = -1,
    val batteryNow: Int = -1,
    val sessionId: String = ""
)

/** User-started, visible voice session that survives the sheet and screen turning off. */
class LiveVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.punk.app.voice.START"
        const val ACTION_STOP = "com.punk.app.voice.STOP"
        const val ACTION_INTERRUPT = "com.punk.app.voice.INTERRUPT"
        const val ACTION_SET_WAKE = "com.punk.app.voice.SET_WAKE"
        const val EXTRA_WAKE = "wake"
        private const val CHANNEL_ID = "punk_live_voice"
        private const val NOTIFICATION_ID = 3500

        private val _state = MutableStateFlow(LiveVoiceState())
        val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

        fun start(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, LiveVoiceService::class.java).setAction(ACTION_START)
        )

        fun stop(context: Context) = context.startService(Intent(context, LiveVoiceService::class.java).setAction(ACTION_STOP))

        fun interrupt(context: Context) = context.startService(Intent(context, LiveVoiceService::class.java).setAction(ACTION_INTERRUPT))

        fun setWakePhrase(context: Context, enabled: Boolean) = context.startService(
            Intent(context, LiveVoiceService::class.java).setAction(ACTION_SET_WAKE).putExtra(EXTRA_WAKE, enabled)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: AndroidVoiceEngine
    private var listenJob: Job? = null
    private var messageJob: Job? = null
    private var heardReply = false
    private var waitingForReply = false
    private var currentRequestId: String? = null
    private var armedUntil = 0L
    private var lastPartialSentAt = 0L
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        engine = VoiceManager.getOrCreateAndroidEngine(applicationContext)
        scope.launch {
            engine.status.collect { status ->
                if (!_state.value.active) return@collect
                when (status) {
                    is VoiceStatus.Ready -> update(phase = "preparing", error = null)
                    is VoiceStatus.Listening -> update(phase = if (_state.value.wakePhraseArmed) "armed" else "listening", error = null)
                    is VoiceStatus.Processing -> update(phase = "processing")
                    is VoiceStatus.Speaking -> {
                        listenJob?.cancel()
                        waitingForReply = false
                        heardReply = true
                        update(phase = "speaking", error = null)
                        sendSessionEvent("speaking")
                    }
                    is VoiceStatus.Error -> {
                        update(phase = "error", error = status.message)
                        if (_state.value.active && !waitingForReply) scheduleListen(900)
                    }
                    is VoiceStatus.Idle -> {
                        if (engine.isSpeaking()) return@collect
                        if (heardReply && _state.value.active) {
                            heardReply = false
                            currentRequestId = null
                            sendSessionEvent("playback_finished")
                            scheduleListen(900)
                        } else if (!_state.value.active) update(phase = "idle")
                    }
                }
            }
        }
        messageJob = scope.launch {
            ChatRepository.messages.collect { messages ->
                val request = currentRequestId ?: return@collect
                val terminalStatus = VoiceReplyPolicy.terminalStatus(request, messages) ?: return@collect
                if (terminalStatus != "completed") {
                    waitingForReply = false
                    currentRequestId = null
                    update(phase = "error", error = "A conversa foi interrompida antes da resposta.")
                    if (_state.value.active) scheduleListen(1_000)
                } else if (waitingForReply) {
                    waitingForReply = false
                    // TTS cancels this delayed fallback as soon as playback starts.
                    scheduleListen(1_000)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_INTERRUPT -> scope.launch { interruptForSpeech("button") }
            ACTION_SET_WAKE -> {
                val enabled = intent.getBooleanExtra(EXTRA_WAKE, false)
                getSharedPreferences("punk_live_voice", MODE_PRIVATE).edit().putBoolean("wake", enabled).apply()
                armedUntil = 0L
                update(wakePhraseEnabled = enabled, wakePhraseArmed = false)
                sendSessionEvent("wake_phrase")
            }
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update(active = false, phase = "error", error = "Conceda permissão de microfone ao PUNK.")
            stopSelf()
            return
        }
        if (!startedForeground) {
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparando microfone…"), types)
            startedForeground = true
        }
        if (_state.value.active) {
            if (!waitingForReply && !engine.isListening() && !engine.isSpeaking()) scheduleListen(0)
            return
        }
        val battery = battery()
        val wake = getSharedPreferences("punk_live_voice", MODE_PRIVATE).getBoolean("wake", false)
        val availability = engine.availability.value
        _state.value = LiveVoiceState(
            active = true,
            phase = "preparing",
            wakePhraseEnabled = wake,
            onDeviceRecognition = availability.onDeviceRecognitionAvailable,
            bargeInSupported = false,
            batteryAtStart = battery,
            batteryNow = battery,
            sessionId = UUID.randomUUID().toString()
        )
        sendSessionEvent("started")
        scheduleListen(100)
    }

    private fun scheduleListen(delayMs: Long) {
        listenJob?.cancel()
        listenJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!_state.value.active || waitingForReply || engine.isSpeaking()) return@launch
            beginListening()
        }
    }

    private fun beginListening() {
        if (!_state.value.active || waitingForReply || engine.isSpeaking()) return
        update(phase = "preparing", transcript = "", error = null, batteryNow = battery())
        engine.startListeningWithCallbacks(
            onPartial = { partial ->
                if (!_state.value.active) return@startListeningWithCallbacks
                update(phase = if (_state.value.wakePhraseArmed) "armed" else "listening", transcript = partial, error = null)
                val now = System.currentTimeMillis()
                if (now - lastPartialSentAt >= 180) {
                    lastPartialSentAt = now
                    BridgeService.sendMessage(BridgeTypes.VOICE_PARTIAL, buildJsonObject {
                        put("sessionId", _state.value.sessionId)
                        put("conversationId", ChatRepository.selected.value)
                        put("text", partial.take(2_000))
                        put("wakePhraseArmed", _state.value.wakePhraseArmed)
                        put("timestamp", now)
                    })
                }
            },
            onFinal = { final -> if (_state.value.active) scope.launch { handleFinal(final) } },
            onError = { message ->
                if (_state.value.active && !waitingForReply) {
                    update(phase = "error", error = message)
                    scheduleListen(900)
                }
            }
        )
    }

    private suspend fun handleFinal(final: String) {
        if (waitingForReply || engine.isSpeaking()) return
        val now = System.currentTimeMillis()
        val parsed = WakePhraseParser.parse(final, _state.value.wakePhraseEnabled, armedUntil, now)
        when (parsed.action) {
            WakeAction.IGNORE -> {
                update(phase = "waiting_wake", transcript = "", lastFinal = final, wakePhraseArmed = false)
                scheduleListen(180)
            }
            WakeAction.ARM -> {
                armedUntil = now + 8_000
                update(phase = "armed", transcript = "Punk", lastFinal = final, wakePhraseArmed = true, error = null)
                sendSessionEvent("armed")
                scheduleListen(180)
            }
            WakeAction.SEND -> {
                armedUntil = 0L
                val command = parsed.text.trim()
                if (command.isBlank()) {
                    scheduleListen(180)
                    return
                }
                waitingForReply = true
                val requestId = UUID.randomUUID().toString()
                currentRequestId = requestId
                update(phase = "processing", transcript = command, lastFinal = command, wakePhraseArmed = false, error = null)
                sendSessionEvent("submitted")
                try {
                    withContext(Dispatchers.IO) {
                        ChatRepository.send(command, spoken = true, retryId = requestId)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    waitingForReply = false
                    currentRequestId = null
                    update(phase = "error", error = error.message ?: "Não foi possível enviar a mensagem ao Hermes.")
                    sendSessionEvent("send_failed")
                    if (_state.value.active) scheduleListen(1_000)
                }
            }
        }
    }

    private suspend fun interruptForSpeech(source: String) {
        if (!_state.value.active || !engine.isSpeaking()) return
        listenJob?.cancel()
        heardReply = false
        waitingForReply = false
        currentRequestId = null
        engine.stopSpeaking()
        update(phase = "preparing", transcript = "", error = null)
        sendSessionEvent("barge_in", source)
        scheduleListen(900)
    }

    private fun stopSession() {
        listenJob?.cancel()
        waitingForReply = false
        heardReply = false
        currentRequestId = null
        armedUntil = 0L
        engine.cancelListening()
        engine.stopSpeaking()
        sendSessionEvent("stopped")
        _state.value = LiveVoiceState()
        if (startedForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        startedForeground = false
        stopSelf()
    }

    private fun update(
        active: Boolean = _state.value.active,
        phase: String = _state.value.phase,
        transcript: String = _state.value.transcript,
        lastFinal: String = _state.value.lastFinal,
        error: String? = _state.value.error,
        wakePhraseEnabled: Boolean = _state.value.wakePhraseEnabled,
        wakePhraseArmed: Boolean = _state.value.wakePhraseArmed,
        batteryNow: Int = _state.value.batteryNow
    ) {
        _state.value = _state.value.copy(
            active = active,
            phase = phase,
            transcript = transcript,
            lastFinal = lastFinal,
            error = error,
            wakePhraseEnabled = wakePhraseEnabled,
            wakePhraseArmed = wakePhraseArmed,
            batteryNow = batteryNow
        )
        if (startedForeground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notificationText()))
    }

    private fun sendSessionEvent(event: String, source: String = "service") {
        val state = _state.value
        BridgeService.sendMessage(BridgeTypes.VOICE_SESSION, buildJsonObject {
            put("sessionId", state.sessionId)
            put("conversationId", ChatRepository.selected.value)
            put("event", event)
            put("source", source)
            put("phase", state.phase)
            put("wakePhraseEnabled", state.wakePhraseEnabled)
            put("onDeviceRecognition", state.onDeviceRecognition)
            put("bargeInSupported", state.bargeInSupported)
            put("batteryAtStart", state.batteryAtStart)
            put("batteryNow", battery())
            put("timestamp", System.currentTimeMillis())
        })
    }

    private fun battery(): Int = getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private fun notificationText(): String = when (_state.value.phase) {
        "listening" -> "Ouvindo…"
        "armed" -> "Punk ativado — diga seu pedido"
        "waiting_wake" -> "Diga “Punk” para ativar"
        "processing" -> "Enviando ao Hermes…"
        "speaking" -> "Punk está respondendo — toque para interromper"
        "error" -> _state.value.error ?: "Live precisa de atenção"
        else -> "Live ativo"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PUNK Live", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Sessão de voz iniciada pelo usuário"
                    setSound(null, null)
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val interrupt = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveVoiceService::class.java).setAction(ACTION_INTERRUPT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, LiveVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Punk Live")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_btn_speak_now, "Interromper e falar", interrupt)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stop)
            .build()
    }

    override fun onDestroy() {
        listenJob?.cancel()
        messageJob?.cancel()
        engine.cancelListening()
        engine.stopSpeaking()
        _state.value = LiveVoiceState()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
