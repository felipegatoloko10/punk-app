package com.punk.bridge

import kotlinx.serialization.Serializable

@Serializable
data class BridgeConfig(
    val host: String = "100.64.0.1",
    val port: Int = 8765,
    val token: String = "",
    val useTls: Boolean = false,
    val reconnectBaseMs: Long = 1000L,
    val reconnectMaxMs: Long = 30000L
) {
    fun wsUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        return "${scheme}://${host}:${port}/punk-bridge"
    }
}
