package com.punk.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolContractsTest {
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun missingCoordinatesNeverBecomeZeroTaps() {
        assertNotNull(ToolContracts.validate("input_tap", args("{}")))
        assertNotNull(ToolContracts.validate("input_swipe", args("{\"x1\":3,\"y1\":4}")))
    }
    @Test fun legacyPackageNameAndClearAliasesArePreserved() {
        val launch = ToolContracts.normalize("open_app", args("{\"packageName\":\"com.android.settings\"}"))
        assertEquals("com.android.settings", launch["package"]?.jsonPrimitive?.content)
        assertNull(ToolContracts.validate("open_app",launch))
        val input = ToolContracts.normalize("input_text", args("{\"text\":\"Olá mundo\",\"clear\":true}"))
        assertEquals(true, input["clearFirst"]?.jsonPrimitive?.boolean)
        assertNull(ToolContracts.validate("input_text",input))
    }
    @Test fun invalidPackagesAndDeadlinesAreRejectedBeforeExecution() {
        assertNotNull(ToolContracts.validate("force_stop",args("{\"package\":\"com.android.settings; reboot\"}")))
        assertNotNull(ToolContracts.validate("shell_exec",args("{\"cmd\":\"echo ok\",\"timeoutMs\":999999}")))
        assertNotNull(ToolContracts.validate("shell_exec",args("{\"cmd\":\"\"}")))
    }
    @Test fun togglesRequireAnExplicitState() {
        assertNotNull(ToolContracts.validate("set_mobile_data",args("{}")))
        assertNotNull(ToolContracts.validate("set_wifi",args("{\"enabled\":\"maybe\"}")))
        assertNull(ToolContracts.validate("set_wifi",args("{\"enabled\":false}")))
    }
    @Test fun catalogContainsEveryToolAndRejectsUnknownArguments() {
        assertEquals(50,ToolContracts.schemas.size)
        assertTrue(ToolContracts.schemas.values.all { it["type"]?.jsonPrimitive?.content == "object" })
        assertNotNull(ToolContracts.validate("device_status",args("{\"command\":\"reboot\"}")))
        assertNull(ToolContracts.validate("find_and_click",args("{\"query\":\"Enviar\",\"scrolls\":2}")))
        assertNotNull(ToolContracts.validate("notification_reply",args("{\"key\":\"n1\"}")))
    }
    @Test fun otpAndFinancialNotificationsNeverLeaveRawCodes() {
        assertEquals("[Conteúdo protegido]",NotificationPrivacy.protect("Seu código é 124578"))
        assertEquals("[Conteúdo protegido]",NotificationPrivacy.protect("Compra aprovada","com.nu.nubank"))
        assertEquals("Música tocando",NotificationPrivacy.protect("Música tocando","com.player"))
    }
    @Test fun failedToolResultsNeverLookSuccessfulInBridgeEnvelope() {
        assertFalse(ToolRegistry.isSuccessful(args("{\"ok\":false}")))
        assertFalse(ToolRegistry.isSuccessful(args("{\"exitCode\":1,\"stdout\":\"\"}")))
        assertFalse(ToolRegistry.isSuccessful(args("{\"error\":{\"code\":\"permission\"}}")))
        assertTrue(ToolRegistry.isSuccessful(args("{\"ok\":true,\"exitCode\":0}")))
    }
    @Test fun shellArgumentsKeepApostrophesInsideOneQuotedValue() {
        assertEquals("'a'\\''b'",com.punk.axmanager.AxShell.quote("a'b"))
        assertEquals("''",com.punk.axmanager.AxShell.quote(""))
        assertEquals("'x; reboot'",com.punk.axmanager.AxShell.quote("x; reboot"))
    }
}
