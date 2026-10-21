package com.punk.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object BridgeHttp {
    fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    fun client(config: BridgeConfig): OkHttpClient {
        if (config.useTls) AccountRoutes.validateTls(config)
        val builder = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        if(config.trustMode=="cloudflare")builder.dns(CloudflareDns.resolver)
        if (config.useTls && config.certificateFingerprint.isNotBlank()) {
            val pin = config.certificateFingerprint.replace(":", "").lowercase()
            require(pin.matches(Regex("[a-f0-9]{64}"))) { "Identidade TLS inválida" }
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("Cliente TLS não suportado") }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Certificado ausente")
                    leaf.checkValidity()
                    if (!MessageDigest.isEqual(fingerprint(leaf).toByteArray(), pin.toByteArray())) throw java.security.cert.CertificateException("Identidade do PC mudou. Faça o pareamento novamente.")
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, session ->
                runCatching { fingerprint(session.peerCertificates[0] as X509Certificate) == pin }.getOrDefault(false)
            }
        }
        return builder.build()
    }
    fun request(config: BridgeConfig, path: String): Request.Builder = Request.Builder()
        .url(config.httpUrl() + path).header("Authorization", "Bearer ${config.token}").header("X-Punk-Device", config.deviceId)
    fun jsonBody(value: JsonElement) = value.toString().toRequestBody("application/json".toMediaType())
}

object BridgePairing {
    suspend fun pair(ctx: Context, pairingJson: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val data = Json.parseToJsonElement(pairingJson.trim()).jsonObject
        val routes=ConnectionRoutes.pairing(data)
        val body = buildJsonObject { put("code", data.getValue("pairingCode").jsonPrimitive.content); put("deviceName", android.os.Build.MODEL) }
        val failures=mutableListOf<String>()
        var paired:BridgeConfig?=null
        for(config in routes) {
            paired=runCatching { BridgeHttp.client(config).newBuilder().callTimeout(if(config.trustMode=="cloudflare")25 else 4,TimeUnit.SECONDS).build().useClient { client ->
                client.newCall(Request.Builder().url(config.httpUrl() + "/api/pair").post(BridgeHttp.jsonBody(body)).build()).execute().use { response ->
                    require(response.isSuccessful) { "Pareamento recusado ou expirado (HTTP ${response.code}). Gere um novo QR no PC." }
                    val result=Json.parseToJsonElement(response.body!!.string()).jsonObject
                    config.copy(deviceId=result.getValue("deviceId").jsonPrimitive.content,token=result.getValue("token").jsonPrimitive.content)
                }
            } }.onFailure { failures.add(ConnectionRoutes.label(config)+": "+ConnectionRoutes.failure(it)) }.getOrNull()
            if(paired!=null)break
        }
        var connected=paired ?: error("Não foi possível parear. "+failures.joinToString("; ")+
            if(data["remoteEndpoint"]==null)". Este QR não tem rota de Internet; gere outro com acesso externo ativo no Desktop." else "")
        val computer=data["computerId"]?.jsonPrimitive?.content ?: ""
        if(computer.isNotEmpty() && PunkAccount.email(ctx).isNotEmpty()) {
            val owned=runCatching { PunkAccount.computers(ctx).any { it.jsonObject["id"]?.jsonPrimitive?.content==computer } }.getOrDefault(false)
            if(owned)connected=connected.copy(accountComputerId=computer)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        BridgePrefs.save(ctx,connected)
        // Keep QR-only routes as complete configs: remote and LAN use different ports and trust identities.
        BridgePrefs.savePairRoutes(ctx,routes)
        val local=data["localHost"]?.jsonPrimitive?.content ?: data.getValue("host").jsonPrimitive.content
        val tailscale=data["tailscaleHost"]?.jsonPrimitive?.content ?: ""
        BridgePrefs.saveFailoverSettings(ctx,local,tailscale,routes.size>1)
    } }
}

inline fun <T> OkHttpClient.useClient(block: (OkHttpClient) -> T): T = try { block(this) } finally { connectionPool.evictAll(); dispatcher.executorService.shutdown() }
