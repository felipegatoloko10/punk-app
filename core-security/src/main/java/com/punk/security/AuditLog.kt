package com.punk.security

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant

data class AuditEntry(val time: String, val tool: String, val params: String, val result: String)

/** Bounded local hash chain: detects changes while its head is trusted; not immutable storage. */
object AuditLog {
    private const val MAX_BYTES = 512 * 1024L
    private fun file(ctx: Context) = File(ctx.filesDir, "punk_audit.log")
    private val sensitive = Regex("(?i)token|pin|password|secret|auth|phone|number|address|latitude|longitude|sms|body|text|message|content|base64|nodes|stdout|stderr|cmd|command|query|filter|url|path|name|value|output|error|detail|title|email|contact|account|recipient|sender|^to$|^from$")
    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun redact(raw: String): String = try {
        fun scrub(value: JsonElement): JsonElement = when(value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (sensitive.containsMatchIn(key)) JsonPrimitive("[redacted]") else scrub(child) })
            is JsonArray -> JsonArray(value.take(100).map { scrub(it) })
            is JsonPrimitive -> if (value.isString && value.content.length > 200) JsonPrimitive("[redacted]") else value
        }
        val parsed = Json.parseToJsonElement(raw)
        if (parsed !is JsonObject && parsed !is JsonArray) "[redacted]" else scrub(parsed).toString().take(4000)
    } catch (_: Exception) { "[redacted]" }

    private fun hash(entry: JsonObject): String = digest(listOf("previousHash", "time", "tool", "params", "result")
        .joinToString("\n") { entry[it]?.jsonPrimitive?.content.orEmpty() })
    internal fun encode(entry: AuditEntry, previousHash: String): JsonObject {
        val payload = buildJsonObject {
            put("time",entry.time); put("tool",entry.tool); put("params",redact(entry.params)); put("result",redact(entry.result)); put("previousHash",previousHash)
        }
        return JsonObject(payload + ("hash" to JsonPrimitive(hash(payload))))
    }
    internal fun verifyLines(lines: List<String>): Boolean = try {
        var previous: String? = null
        lines.all { line ->
            val entry = Json.parseToJsonElement(line).jsonObject
            val actual = entry.getValue("hash").jsonPrimitive.content
            val linked = previous == null || entry["previousHash"]?.jsonPrimitive?.content == previous
            previous = actual
            linked && actual == hash(entry)
        }
    } catch (_: Exception) { false }

    @Synchronized fun append(ctx: Context, entry: AuditEntry) {
        try {
            val current = file(ctx)
            // Older builds wrote raw commands, PINs and messages. Do not keep or display those records.
            if (current.exists() && current.useLines { it.firstOrNull()?.startsWith("{") == false }) current.writeText("")
            if (current.exists() && current.length() > MAX_BYTES) {
                val previous = File(ctx.filesDir, "punk_audit.previous.log")
                previous.delete()
                current.renameTo(previous)
            }
            val last = if(current.exists()) current.useLines { it.lastOrNull() } else null
            val previousHash = last?.let { runCatching { Json.parseToJsonElement(it).jsonObject["hash"]?.jsonPrimitive?.content }.getOrNull() } ?: "GENESIS"
            current.appendText(encode(entry,previousHash).toString() + "\n")
        } catch (_: Exception) { /* Never replay an action after an audit failure. */ }
    }
    fun verify(ctx: Context): Boolean = try { val source = file(ctx); !source.exists() || verifyLines(source.readLines()) } catch (_: Exception) { false }
    fun tail(ctx: Context, maxLines: Int = 200): String = try {
        val source = file(ctx)
        if(!source.exists()) "" else source.readLines().takeLast(maxLines.coerceIn(1,500))
            .joinToString("\n") { if(it.startsWith("{")) it else "[Registro antigo protegido]" }
    } catch (_: Exception) { "" }
    fun now(): String = Instant.now().toString()
}
