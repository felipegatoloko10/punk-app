package com.punk.bridge

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.Request
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ConnectionRoutesTest {
    private val base=BridgeConfig(host="192.168.15.148",port=18765,useTls=true,certificateFingerprint="ab".repeat(32))
    @Test fun existingLocalQrMigratesToItsAccountPcOnNetworkChange() {
        val pcs=buildJsonArray { add(buildJsonObject { put("id","same-pc");put("fingerprint",base.certificateFingerprint) }) }
        assertEquals("same-pc",ConnectionRoutes.accountTarget(base.copy(deviceId="paired"),pcs))
    }
    @Test fun neverSwitchesExistingQrToAnUnrelatedAccountPc() {
        val pcs=buildJsonArray { add(buildJsonObject { put("id","other-pc");put("fingerprint","cd".repeat(32)) }) }
        assertEquals("",ConnectionRoutes.accountTarget(base.copy(deviceId="paired"),pcs))
    }
    @Test fun internetFirstWithDistinctPortAndTrustIdentity() {
        val routes=ConnectionRoutes.ordered(base,"https://test.trycloudflare.com","100.76.116.36")
        assertEquals(listOf("test.trycloudflare.com","192.168.15.148","100.76.116.36"),routes.map { it.host })
        assertEquals(443,routes[0].port);assertEquals("cloudflare",routes[0].trustMode);assertEquals("",routes[0].certificateFingerprint)
        assertEquals(18765,routes[1].port);assertEquals(base.certificateFingerprint,routes[1].certificateFingerprint)
    }
    @Test fun oldQrStillHasLocalAndVpnRoutes() {
        val data=buildJsonObject { put("version",3);put("host",base.host);put("port",base.port);put("certificateFingerprint",base.certificateFingerprint);put("tailscaleHost","100.76.116.36") }
        assertEquals(2,ConnectionRoutes.pairing(data).size)
    }
    @Test fun remoteQrWorksWithoutAnyAccountFields() {
        val data=buildJsonObject { put("version",3);put("host",base.host);put("certificateFingerprint",base.certificateFingerprint);put("remoteEndpoint","https://test.trycloudflare.com") }
        assertEquals("cloudflare",ConnectionRoutes.pairing(data).first().trustMode)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsHttpRemote() { ConnectionRoutes.remote(base,"http://test.trycloudflare.com") }
    @Test(expected=IllegalArgumentException::class) fun rejectsRemotePath() { ConnectionRoutes.remote(base,"https://test.trycloudflare.com/redirect") }
    @Test(expected=IllegalArgumentException::class) fun rejectsRemoteQuery() { ConnectionRoutes.remote(base,"https://test.trycloudflare.com?token=secret") }
    @Test(expected=IllegalArgumentException::class) fun rejectsDifferentPublicHost() { ConnectionRoutes.remote(base,"https://attacker.example") }
    @Test fun diagnosticDoesNotReflectSecretsFromAnException() {
        assertEquals("falha de comunicação",ConnectionRoutes.failure(RuntimeException("Bearer private-token")))
        assertTrue(ConnectionRoutes.failure(UnknownHostException("private-token")).contains("DNS"))
    }
    @Test fun liveExternalRouteUsesTheSameOkHttpClientAsAndroid() {
        val endpoint=System.getenv("PUNK_EXTERNAL_TEST_URL") ?: ""
        assumeTrue("Opt-in live network check",endpoint.isNotEmpty())
        val route=ConnectionRoutes.remote(base,endpoint)
        BridgeHttp.client(route).newBuilder().callTimeout(25,TimeUnit.SECONDS).build().useClient { client ->
            client.newCall(Request.Builder().url(route.httpUrl()+"/api/status").build()).execute().use { assertEquals(401,it.code) }
        }
    }
}
