package com.punk.bridge
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class CloudflareDnsTest {
    private fun dns(block:(String)->List<InetAddress>):Dns = object:Dns { override fun lookup(hostname:String)=block(hostname) }
    @Test fun prefersSystemDns() {
        val expected=listOf(InetAddress.getByName("104.16.230.132"))
        val dns=CloudflareDns.fallback(dns { expected },dns { error("Fallback must not run") })
        assertEquals(expected,dns.lookup("example.trycloudflare.com"))
    }
    @Test fun fallbackOnlyAfterDnsFailure() {
        val expected=listOf(InetAddress.getByName("104.16.230.132"))
        val dns=CloudflareDns.fallback(dns { throw UnknownHostException() },dns { expected })
        assertEquals(expected,dns.lookup("example.trycloudflare.com"))
    }
    @Test(expected=UnknownHostException::class) fun neverOverridesLocalDns() {
        CloudflareDns.fallback(dns { throw UnknownHostException() },dns { error("Must not run") }).lookup("private.lan")
    }
    @Test(expected=UnknownHostException::class) fun rejectsPrivateFallbackAddress() {
        CloudflareDns.fallback(dns { throw UnknownHostException() },dns { listOf(InetAddress.getByName("127.0.0.1")) }).lookup("example.trycloudflare.com")
    }
}
