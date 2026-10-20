package com.punk.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val builder = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
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
        require(data["version"]?.jsonPrimitive?.int == 3) { "Código de pareamento incompatível" }
        val config = BridgeConfig(host = data.getValue("host").jsonPrimitive.content, port = data["port"]?.jsonPrimitive?.int ?: 8765,
            useTls = true, certificateFingerprint = data.getValue("certificateFingerprint").jsonPrimitive.content)
        require(config.host.matches(Regex("[A-Za-z0-9._:-]+"))) { "Endereço do PC inválido" }
        val body = buildJsonObject { put("code", data.getValue("pairingCode").jsonPrimitive.content); put("deviceName", android.os.Build.MODEL) }
        BridgeHttp.client(config).useClient { client ->
            client.newCall(Request.Builder().url(config.httpUrl() + "/api/pair").post(BridgeHttp.jsonBody(body)).build()).execute().use { response ->
                require(response.isSuccessful) { "Pareamento recusado ou expirado. Gere um novo código no PC." }
                val result = Json.parseToJsonElement(response.body!!.string()).jsonObject
                BridgePrefs.save(ctx, config.copy(deviceId = result.getValue("deviceId").jsonPrimitive.content, token = result.getValue("token").jsonPrimitive.content))
                val local=data["localHost"]?.jsonPrimitive?.content ?: config.host
                val tailscale=data["tailscaleHost"]?.jsonPrimitive?.content ?: ""
                BridgePrefs.saveFailoverSettings(ctx,local,tailscale,tailscale.isNotBlank())
            }
        }
    } }
}

inline fun <T> OkHttpClient.useClient(block: (OkHttpClient) -> T): T = try { block(this) } finally { connectionPool.evictAll(); dispatcher.executorService.shutdown() }
