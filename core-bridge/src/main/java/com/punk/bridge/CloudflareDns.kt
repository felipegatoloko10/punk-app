package com.punk.bridge

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.UnknownHostException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Scoped DNS fallback, not a device-wide VPN or DNS setting change. TLS is still verified. */
object CloudflareDns {
    private val doh by lazy {
        DnsOverHttps.Builder().client(OkHttpClient.Builder().callTimeout(6,TimeUnit.SECONDS).build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl()).includeIPv6(true).build()
    }
    val resolver:Dns by lazy { fallback(Dns.SYSTEM,doh) }
    fun fallback(primary:Dns,secondary:Dns):Dns = object : Dns {
        override fun lookup(hostname:String):List<InetAddress> = try { primary.lookup(hostname) } catch(e:UnknownHostException) {
            if(!hostname.matches(Regex("[a-z0-9-]+\\.trycloudflare\\.com")))throw e
            secondary.lookup(hostname).also { addresses ->
                if(addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress })
                    throw UnknownHostException("Resposta DNS externa inválida")
            }
        }
    }
}
