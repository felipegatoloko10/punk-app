package com.punk.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import kotlinx.serialization.json.JsonObject

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: BridgeClient? = null

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

        private val _incomingMessages = MutableSharedFlow<BridgeMessage>(replay = 20, extraBufferCapacity = 64)
        val incomingMessages: SharedFlow<BridgeMessage> = _incomingMessages.asSharedFlow()

        var toolExecutor: (suspend (tool: String, params: JsonObject, callId: String) -> ToolResult)? = null

        private var activeClient: BridgeClient? = null

        fun sendMessage(type: String, payload: JsonElement? = null) {
            activeClient?.send(type, payload)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("PUNK Bridge — aguardando conexão"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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

        val config = BridgeConfig(host = host, port = port, token = token)
        BridgePrefs.save(this, config)

        scope.launch {
            client?.close()
            val newClient = BridgeClient(
                config = config,
                scope = scope,
                onState = { s -> updateState(s); updateNotificationFor(s) },
                onMessage = { msg -> _incomingMessages.tryEmit(msg) },
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
            is BridgeState.Connected -> "Conectado ao Hermes"
            is BridgeState.Connecting -> "Conectando ao Hermes..."
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PUNK")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        client?.close()
        activeClient = null
        scope.cancel()
        super.onDestroy()
    }
}
