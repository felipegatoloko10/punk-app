package com.punk.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

data class ApprovalRequest(val id: String, val tool: String, val description: String, val device: String, val expiresAt: Long = System.currentTimeMillis() + 300_000)

object ControlPolicy {
    private val _supervised = MutableStateFlow(false)
    val supervised: StateFlow<Boolean> = _supervised
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending
    private val local = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val stateLock = Any()
    fun applyMode(value: Boolean) { _supervised.value = value }
    fun setSupervised(value: Boolean) {
        if (value) _supervised.value = true
        BridgeService.sendMessage("control_mode", buildJsonObject { put("supervised", value) })
    }
    fun addRemote(request: ApprovalRequest) = synchronized(stateLock) { _pending.value = (_pending.value.filterNot { it.id == request.id } + request) }
    fun respond(id: String, allow: Boolean) {
        val waiting = local.remove(id)
        if (waiting != null) waiting.complete(allow)
        else BridgeService.sendMessage("approval_response", buildJsonObject { put("id", id); put("allow", allow) })
        synchronized(stateLock) { _pending.value = _pending.value.filterNot { it.id == id } }
    }
    suspend fun authorize(tool: String, callId: String, readOnly: Boolean, description: String? = null): Boolean {
        if (!_supervised.value || readOnly) return true
        val id = "local:$callId"
        val deferred = CompletableDeferred<Boolean>()
        local[id] = deferred
        addRemote(ApprovalRequest(id, tool, description ?: "Permitir a execução de $tool no celular?", "Celular"))
        return try { withTimeoutOrNull(300_000) { deferred.await() } ?: false }
        finally { local.remove(id); synchronized(stateLock) { _pending.value = _pending.value.filterNot { it.id == id } } }
    }
    fun cancelAll() { local.values.forEach { it.complete(false) }; local.clear(); synchronized(stateLock) { _pending.value = emptyList() } }
}
