package com.punk.security

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuditLogTest {
    @Test fun secretsAndNestedPayloadsAreRedacted() {
        val raw = """{"pin":"123456","phone":"5511999999999","text":"private message","nested":{"token":"private-token","base64":"private-image"},"ok":false}"""
        val result = AuditLog.redact(raw)
        listOf("123456","5511999999999","private message","private-token","private-image").forEach { assertFalse(result.contains(it)) }
        assertEquals(false,Json.parseToJsonElement(result).jsonObject["ok"]?.jsonPrimitive?.boolean)
    }
    @Test fun chainDetectsEditedRecordsAndBrokenLinks() {
        val entry = AuditEntry("2026-10-19T00:00:00Z","input_tap","{\"x\":100,\"y\":200}","{\"ok\":true}")
        val first = AuditLog.encode(entry,"GENESIS")
        val next = AuditLog.encode(entry,first.getValue("hash").jsonPrimitive.content)
        assertTrue(AuditLog.verifyLines(listOf(first.toString(),next.toString())))
        assertFalse(AuditLog.verifyLines(listOf(first.toString().replace("input_tap","shell_exec"),next.toString())))
        assertFalse(AuditLog.verifyLines(listOf(first.toString(),AuditLog.encode(entry,"wrong-head").toString())))
    }
    @Test fun malformedAuditInputIsNeverCopiedVerbatim() {
        assertEquals("[redacted]",AuditLog.redact("pin=1234 secret=example"))
        assertFalse(AuditLog.verifyLines(listOf("broken line")))
    }
}
