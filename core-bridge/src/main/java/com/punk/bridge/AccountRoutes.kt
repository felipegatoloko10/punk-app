package com.punk.bridge

/** No implicit certificate fallback when switching from a pinned PC to the Cloudflare edge. */
object AccountRoutes {
    fun validateTls(c: BridgeConfig) {
        require(c.host.matches(Regex("[A-Za-z0-9._:-]+"))) { "Endereço inválido" }
        when(c.trustMode) {
            "pinned" -> require(c.certificateFingerprint.replace(":", "").matches(Regex("[a-fA-F0-9]{64}"))) { "Identidade TLS do PC ausente" }
            "cloudflare" -> require(c.port==443 && c.certificateFingerprint.isEmpty() && c.host.matches(Regex("[a-z0-9-]+\\.trycloudflare\\.com"))) { "Rota externa inválida" }
            else -> error("Modo TLS desconhecido")
        }
    }
}
