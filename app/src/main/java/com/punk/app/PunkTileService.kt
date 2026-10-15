package com.punk.app

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.punk.bridge.BridgePrefs
import com.punk.bridge.BridgeService
import com.punk.bridge.BridgeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@RequiresApi(Build.VERSION_CODES.N)
class PunkTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(BridgeService.state.value)
        scope.launch {
            BridgeService.state.collectLatest { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        scope.coroutineContext.cancelChildren()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val currentState = BridgeService.state.value

        when (currentState) {
            is BridgeState.Connected, is BridgeState.Connecting -> {
                // Parar serviço
                val intent = Intent(this, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_STOP
                }
                startService(intent)
                updateTileState(BridgeState.Disconnected)
            }
            else -> {
                // Iniciar serviço
                val host = BridgePrefs.host(this)
                val port = BridgePrefs.port(this)
                val token = BridgePrefs.token(this)
                val intent = Intent(this, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_START
                    putExtra(BridgeService.EXTRA_HOST, host)
                    putExtra(BridgeService.EXTRA_PORT, port)
                    putExtra(BridgeService.EXTRA_TOKEN, token)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                updateTileState(BridgeState.Connecting)
            }
        }
    }

    private fun updateTileState(state: BridgeState) {
        val tile = qsTile ?: return
        when (state) {
            is BridgeState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "PUNK: Ativo"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Conectado ao JARVIS"
                }
            }
            is BridgeState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "PUNK: Ligando..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Conectando..."
                }
            }
            is BridgeState.Failed -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "PUNK: Falha"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Erro de conexão"
                }
            }
            is BridgeState.Disconnected -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "PUNK Agent"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Desconectado"
                }
            }
        }
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }
}
