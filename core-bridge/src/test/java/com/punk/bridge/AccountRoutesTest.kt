package com.punk.bridge

import org.junit.Test

class AccountRoutesTest {
    @Test fun pinnedLocalRoute() { AccountRoutes.validateTls(BridgeConfig(host="192.168.1.2",useTls=true,certificateFingerprint="ab".repeat(32))) }
    @Test fun cloudflareUsesPublicCA() { AccountRoutes.validateTls(BridgeConfig(host="one-two.trycloudflare.com",port=443,useTls=true,trustMode="cloudflare")) }
    @Test(expected=IllegalArgumentException::class) fun noSilentPinRemoval() { AccountRoutes.validateTls(BridgeConfig(host="192.168.1.2",useTls=true)) }
    @Test(expected=IllegalArgumentException::class) fun noSystemTrustOnLocalIP() { AccountRoutes.validateTls(BridgeConfig(host="192.168.1.2",port=443,useTls=true,trustMode="cloudflare")) }
    @Test(expected=IllegalArgumentException::class) fun noSuffixConfusion() { AccountRoutes.validateTls(BridgeConfig(host="a.trycloudflare.com.attacker.test",port=443,useTls=true,trustMode="cloudflare")) }
    @Test(expected=IllegalArgumentException::class) fun noOriginPinOnCloudflare() { AccountRoutes.validateTls(BridgeConfig(host="a.trycloudflare.com",port=443,useTls=true,trustMode="cloudflare",certificateFingerprint="ab".repeat(32))) }
}
