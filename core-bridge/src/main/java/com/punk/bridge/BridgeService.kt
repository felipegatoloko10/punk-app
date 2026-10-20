package com.punk.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.*

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: BridgeClient? = null
    private var connectionJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "punk_bridge"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.punk.bridge.START"
        const val ACTION_STOP = "com.punk.bridge.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"

        private val _state = MutableStateFlow<BridgeState>(BridgeState.Disconnected)
        val state: StateFlow<BridgeState> = _state
        internal fun updateState(s: BridgeState) { _state.value = s }

        private val _incomingMessages = MutableSharedFlow<BridgeMessage>(replay = 0, extraBufferCapacity = 128)
        val incomingMessages: SharedFlow<BridgeMessage> = _incomingMessages.asSharedFlow()

        var stopLocalTools: (() -> Unit)? = null
        var awaitLocalToolsIdle: (suspend () -> Boolean)? = null
        var messageConsumer: ((BridgeMessage) -> Unit)? = null
        var toolCatalog: (() -> JsonArray)? = null
        private val _health = MutableStateFlow<JsonObject>(JsonObject(emptyMap()))
        val health: StateFlow<JsonObject> = _health

        var toolExecutor: (suspend (tool: String, params: JsonObject, callId: String) -> ToolResult)? = null

        private var activeClient: BridgeClient? = null

        fun sendMessage(type: String, payload: JsonElement? = null): Boolean = activeClient?.send(type, payload) ?: false

        fun stopAll(conversationId: String? = null) {
            activeClient?.send("cancel", buildJsonObject { if(conversationId != null) put("conversationId", conversationId) })
            activeClient?.cancelTools()
            ControlPolicy.cancelAll()
            stopLocalTools?.invoke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("PUNK Bridge — aguardando conexão"))
        NetworkFailoverManager.start(this, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                client?.close()
                activeClient = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val host = intent?.getStringExtra(EXTRA_HOST) ?: BridgePrefs.host(this)
        val port = if (intent?.hasExtra(EXTRA_PORT) == true) intent.getIntExtra(EXTRA_PORT, 8765) else BridgePrefs.port(this)
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: BridgePrefs.token(this)

        val config = BridgePrefs.load(this).copy(host = host, port = port, token = token)
        if (host.isBlank() || token.isBlank() || config.deviceId.isBlank() || !config.useTls) {
            updateState(BridgeState.Failed("Conecte ao PC pelo pareamento"))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }

        connectionJob?.cancel()
        connectionJob = scope.launch {
            client?.close()
            val newClient = BridgeClient(
                context = this@BridgeService,
                config = config,
                catalog = toolCatalog?.invoke() ?: JsonArray(emptyList()),
                scope = scope,
                onState = { s -> updateState(s); updateNotificationFor(s) },
                onMessage = { msg ->
                    val payload = msg.payload as? JsonObject
                    if (msg.type == BridgeTypes.HELLO_ACK || msg.type == "health_update") {
                        _health.value = payload?.get("health") as? JsonObject ?: JsonObject(emptyMap())
                    }
                    if (msg.type == BridgeTypes.HELLO_ACK) {
                        payload?.get("supervised")?.jsonPrimitive?.booleanOrNull?.let { mode ->
                            if(BridgePrefs.supervised(this@BridgeService) && !mode) ControlPolicy.setSupervised(true)
                            else { ControlPolicy.applyMode(mode); BridgePrefs.setSupervised(this@BridgeService,mode) }
                        }
                    }
                    if (msg.type == "control_mode" && payload?.get("applied")?.jsonPrimitive?.booleanOrNull == true) {
                        val mode = payload["supervised"]?.jsonPrimitive?.booleanOrNull ?: true
                        ControlPolicy.applyMode(mode); BridgePrefs.setSupervised(this@BridgeService,mode)
                    }
                    if (msg.type == "approval_request" && payload != null) {
                        ControlPolicy.addRemote(ApprovalRequest(payload["id"]?.jsonPrimitive?.content ?: msg.id,
                            payload["tool"]?.jsonPrimitive?.content ?: "Ferramenta",
                            payload["description"]?.jsonPrimitive?.content ?: "Autorizar execução?",
                            payload["device"]?.jsonPrimitive?.content ?: "PC"))
                    }
                    messageConsumer?.invoke(msg)
                    _incomingMessages.tryEmit(msg)
                },
                toolExecutor = { tool, params, callId ->
                    toolExecutor?.invoke(tool, params, callId)
                        ?: ToolResult(callId = callId, ok = false, error = "Nenhum executor registrado")
                }
            )
            client = newClient
            activeClient = newClient
            newClient.connect()
        }
        return START_STICKY
    }

    private fun updateNotificationFor(s: BridgeState) {
        val text = when (s) {
            is BridgeState.Connected -> "Punk conectado ao PC"
            is BridgeState.Connecting -> "Conectando ao PC…"
            is BridgeState.Disconnected -> "Desconectado"
            is BridgeState.Failed -> "Falha: ${s.reason}"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "PUNK Bridge", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Conexão persistente com o Hermes Agent"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceIntent = Intent().apply {
            setClassName(packageName, "com.punk.app.VoiceActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val voicePendingIntent = PendingIntent.getActivity(
            this,
            1,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reconnectIntent = Intent(this, BridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val reconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            reconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Punk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, "Falar", voicePendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "Parar tudo", reconnectPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$text\nConverse ou interrompa as tarefas do Punk por aqui.")
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NetworkFailoverManager.stop()
        stopAll()
        connectionJob?.cancel()
        client?.close()
        activeClient = null
        scope.cancel()
        super.onDestroy()
    }
}
