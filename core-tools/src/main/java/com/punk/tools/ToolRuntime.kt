package com.punk.tools

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

internal object ToolRuntime {
    val activeCalls = ConcurrentHashMap.newKeySet<Job>()
    fun hasPermission(ctx: Context, permission: String): Boolean = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun isUserVisible(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
    fun captureError(ctx: Context, permission: String): String? = when {
        !hasPermission(ctx, permission) -> "Permissão necessária não concedida. Abra Capacidades no PUNK."
        !isUserVisible() -> "Abra o PUNK e mantenha a sessão visível para usar câmera ou microfone. Captura em segundo plano não está disponível."
        else -> null
    }
}
