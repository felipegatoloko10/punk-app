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
                if (!BridgePrefs.autoFailover(ctx)) return
                job?.cancel()
                job = scope.launch(Dispatchers.IO) {
                    delay(1500)
                    val current = BridgePrefs.load(ctx)
                    if (current.token.isBlank() || !current.useTls) return@launch
                    val candidates = listOf(current.host, BridgePrefs.tailscaleHost(ctx), BridgePrefs.localHost(ctx)).filter { it.isNotBlank() }.distinct()
                    for (host in candidates) {
                        val config = current.copy(host=host)
                        val reachable = runCatching { BridgeHttp.client(config).useClient { http ->
                            http.newCall(BridgeHttp.request(config,"/api/status").build()).execute().use { it.isSuccessful }
                        } }.getOrDefault(false)
                        if (reachable) {
                            if(host != current.host || BridgeService.state.value !is BridgeState.Connected) {
                                BridgePrefs.save(ctx,config)
                                ctx.startForegroundService(Intent(ctx,BridgeService::class.java).setAction(BridgeService.ACTION_START))
                            }
                            break
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
