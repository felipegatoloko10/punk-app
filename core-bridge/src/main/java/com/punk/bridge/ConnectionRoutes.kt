package com.punk.bridge

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

object ConnectionRoutes {
    fun accountTarget(current:BridgeConfig,computers:JsonArray):String {
        if(current.accountComputerId.isNotEmpty())return current.accountComputerId
        val matches=computers.map { it.jsonObject }.filter { current.certificateFingerprint.isNotEmpty() && it["fingerprint"]?.jsonPrimitive?.content==current.certificateFingerprint }
        val pc=matches.singleOrNull() ?: if(current.deviceId.isEmpty())computers.singleOrNull()?.jsonObject else null
        return pc?.get("id")?.jsonPrimitive?.content ?: ""
    }
    fun remote(base:BridgeConfig,endpoint:String):BridgeConfig {
        val url=endpoint.toHttpUrl()
        require(url.scheme=="https" && url.port==443 && url.encodedPath=="/" && url.encodedQuery==null && url.fragment==null && url.username.isEmpty() && url.password.isEmpty()) { "Rota externa inválida" }
        return base.copy(host=url.host,port=443,certificateFingerprint="",trustMode="cloudflare").also(AccountRoutes::validateTls)
    }
    fun ordered(base:BridgeConfig,endpoint:String,tailscale:String=""):List<BridgeConfig> = buildList {
        // A published Internet route is usable on Wi-Fi and mobile data; avoid probing RFC1918 first on 4G.
        if(endpoint.isNotBlank())add(remote(base,endpoint))
        if(base.host.isNotBlank())add(base.also(AccountRoutes::validateTls))
        if(tailscale.isNotBlank() && tailscale!=base.host)add(base.copy(host=tailscale).also(AccountRoutes::validateTls))
    }
    fun pairing(data:JsonObject):List<BridgeConfig> {
        require(data["version"]?.jsonPrimitive?.int==3) { "Código de pareamento incompatível" }
        val base=BridgeConfig(host=data.getValue("host").jsonPrimitive.content,port=data["port"]?.jsonPrimitive?.int ?: 8765,
            useTls=true,certificateFingerprint=data.getValue("certificateFingerprint").jsonPrimitive.content)
        return ordered(base,data["remoteEndpoint"]?.jsonPrimitive?.content ?: "",data["tailscaleHost"]?.jsonPrimitive?.content ?: "")
    }
    fun failure(error:Throwable):String = when(error) {
        is UnknownHostException -> "DNS não resolveu o endereço"
        is SocketTimeoutException, is java.io.InterruptedIOException -> "tempo de conexão esgotado"
        is SSLException -> "não foi possível validar o certificado TLS"
        is ConnectException -> "endereço não acessível nesta rede"
        is IllegalArgumentException -> error.message?.takeIf { it.startsWith("Acesso recusado") || it.startsWith("Pareamento recusado") } ?: "dados de conexão inválidos"
        else -> "falha de comunicação"
    }
    fun label(route:BridgeConfig)=if(route.trustMode=="cloudflare")"Internet" else "Rede local/VPN"
    fun reachable(routes:List<BridgeConfig>):BridgeConfig? = routes.firstOrNull { route ->
        runCatching { BridgeHttp.client(route).newBuilder().callTimeout(if(route.trustMode=="cloudflare")10 else 3,TimeUnit.SECONDS).build().useClient { client ->
            client.newCall(BridgeHttp.request(route,"/api/status").build()).execute().use { it.isSuccessful }
        } }.getOrDefault(false)
    }
}
