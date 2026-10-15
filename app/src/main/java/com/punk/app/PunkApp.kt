package com.punk.app

import android.app.Application
import com.punk.bridge.BridgeService
import com.punk.bridge.ToolResult
import com.punk.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PunkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeService.toolExecutor = { tool, params, callId ->
            try {
                val res = withContext(Dispatchers.IO) {
                    ToolRegistry.invoke(this@PunkApp, tool, params)
                }
                val isOk = !res.containsKey("error")
                ToolResult(
                    callId = callId,
                    ok = isOk,
                    result = res,
                    error = if (!isOk) res["error"]?.toString() else null
                )
            } catch (e: Exception) {
                ToolResult(
                    callId = callId,
                    ok = false,
                    error = e.message ?: "Exceção ao executar tool"
                )
            }
        }
    }
}
