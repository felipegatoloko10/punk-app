package com.punk.security

import android.content.Context
import java.io.File
import java.time.Instant

data class AuditEntry(val time: String, val tool: String, val params: String, val result: String)

object AuditLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "punk_audit.log")
    fun append(ctx: Context, e: AuditEntry) {
        try { file(ctx).appendText("[${e.time}] ${e.tool} params=${e.params} => ${e.result.take(800)}\n") } catch(_:Exception){}
    }
    fun tail(ctx: Context, maxLines: Int = 200): String = try {
        val f = file(ctx); if(!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
    } catch(_:Exception){ "" }
    fun now(): String = Instant.now().toString()
}
