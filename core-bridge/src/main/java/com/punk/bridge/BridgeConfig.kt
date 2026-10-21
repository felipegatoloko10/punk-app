package com.punk.bridge

import kotlinx.serialization.Serializable

@Serializable
data class BridgeConfig(
    val host: String = "192.168.15.148",
    val port: Int = 8765,
    val token: String = "",
    val useTls: Boolean = false,
    val certificateFingerprint: String = "",
    val deviceId: String = "",
    val reconnectBaseMs: Long = 1000L,
    val reconnectMaxMs: Long = 30000L,
    val trustMode: String = "pinned",
    val accountComputerId: String = ""
) {
    fun wsUrl(): String {
        return "${if (useTls) "wss" else "ws"}://${host}:${port}/punk-bridge"
    }
    fun httpUrl(): String = "${if (useTls) "https" else "http"}://$host:$port"
}
