package com.punk.bridge

sealed interface BridgeState {
    data object Disconnected : BridgeState
    data object Connecting : BridgeState
    data object Connected : BridgeState
    data class Failed(val reason: String) : BridgeState
}
