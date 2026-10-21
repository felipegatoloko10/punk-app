package com.punk.bridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.*

/** Re-probe configured endpoints; never toggle a VPN or infer trusted Wi-Fi from transport type. */
object NetworkFailoverManager {
    var shellExecutor: (suspend (String) -> Boolean)? = null
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var job: Job? = null
    fun start(ctx: Context, scope: CoroutineScope) {
        if (callback != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        manager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val managed=BridgePrefs.load(ctx).accountComputerId
                if(managed.isNotEmpty() || PunkAccount.email(ctx).isNotEmpty()) {
                    job?.cancel()
                    job=scope.launch(Dispatchers.IO) {
                        delay(1500)
                        val current=BridgePrefs.load(ctx)
                        val target=runCatching { PunkAccount.reconnectTarget(ctx,current) }.getOrDefault("")
                        val config=if(target.isNotEmpty())runCatching { PunkAccount.connect(ctx,target) }.getOrNull()
                            else ConnectionRoutes.reachable(BridgePrefs.pairRoutes(ctx,current))
                        if(config==null)return@launch
                        if(config!=current || BridgeService.state.value !is BridgeState.Connected)
                            ctx.startForegroundService(Intent(ctx,BridgeService::class.java).setAction(BridgeService.ACTION_START))
                    }
                    return
                }
                if (!BridgePrefs.autoFailover(ctx)) return
                job?.cancel()
                job = scope.launch(Dispatchers.IO) {
                    delay(1500)
                    val current = BridgePrefs.load(ctx)
                    if (current.token.isBlank() || !current.useTls) return@launch
                    val saved=BridgePrefs.pairRoutes(ctx,current)
                    val candidates=if(saved.size>1)saved else listOf(current.host,BridgePrefs.tailscaleHost(ctx),BridgePrefs.localHost(ctx))
                        .filter { it.isNotBlank() }.distinct().map { current.copy(host=it) }
                    val config=ConnectionRoutes.reachable(candidates)
                    if(config!=null) {
                            if(config != current || BridgeService.state.value !is BridgeState.Connected) {
                                BridgePrefs.save(ctx,config)
                                ctx.startForegroundService(Intent(ctx,BridgeService::class.java).setAction(BridgeService.ACTION_START))
                            }
                    }
                }
            }
        }
        callback=cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }
    fun stop() { callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }; callback=null; job?.cancel() }
    suspend fun setTailscaleState(enable: Boolean) { throw UnsupportedOperationException("Gerencie a VPN diretamente no Tailscale") }
}
