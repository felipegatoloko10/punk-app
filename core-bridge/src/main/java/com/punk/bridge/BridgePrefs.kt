package com.punk.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.legacyBridgePrefs by preferencesDataStore("punk_bridge")

object BridgePrefs {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("punk_connection_v3", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(BridgeConfig(host = ""))
    val config: StateFlow<BridgeConfig> = _config
    suspend fun initialize(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("migrated", false)) {
            runCatching {
                val old = ctx.legacyBridgePrefs.data.first()
                p.edit().putString("host", old[stringPreferencesKey("host")] ?: "").apply()
                val token = old[stringPreferencesKey("token")] ?: ""
                if (token.isNotEmpty()) saveToken(ctx, token)
                ctx.legacyBridgePrefs.edit { it.remove(stringPreferencesKey("token")) }
            }
            p.edit().putBoolean("migrated", true).apply()
        }
        _config.value = load(ctx)
    }
    fun load(ctx: Context) = BridgeConfig(host(ctx), port(ctx), token(ctx), tls(ctx), certificateFingerprint(ctx), deviceId(ctx),
        trustMode=prefs(ctx).getString("trust_mode", "pinned") ?: "pinned",
        accountComputerId=prefs(ctx).getString("account_computer", "") ?: "")
    fun host(ctx: Context): String = prefs(ctx).getString("host", "") ?: ""
    fun port(ctx: Context): Int = prefs(ctx).getInt("port", 8765)
    fun tls(ctx: Context): Boolean = prefs(ctx).getBoolean("tls", false)
    fun certificateFingerprint(ctx: Context): String = prefs(ctx).getString("fingerprint", "") ?: ""
    fun deviceId(ctx: Context): String = prefs(ctx).getString("device_id", "") ?: ""
    fun localHost(ctx: Context): String = prefs(ctx).getString("local_host", "") ?: ""
    fun tailscaleHost(ctx: Context): String = prefs(ctx).getString("tailscale_host", "") ?: ""
    fun autoFailover(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_failover", false)
    fun supervised(ctx: Context): Boolean = prefs(ctx).getBoolean("supervised", false)
    fun setSupervised(ctx: Context, enabled: Boolean) { prefs(ctx).edit().putBoolean("supervised", enabled).apply() }
    fun lastSeq(ctx: Context): Long = prefs(ctx).getLong("last_seq", 0)
    fun setLastSeq(ctx: Context, seq: Long) { if (seq > lastSeq(ctx)) prefs(ctx).edit().putLong("last_seq", seq).apply() }
    fun token(ctx: Context): String = runCatching {
        val encoded = prefs(ctx).getString("credential", null) ?: return ""
        val parts = encoded.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("punk.bridge.credentials.v3", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("punk.bridge.credentials.v3", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun saveToken(ctx: Context, token: String) {
        if (token.isEmpty()) { prefs(ctx).edit().remove("credential").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit().putString("credential", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun save(ctx: Context, c: BridgeConfig) {
        require(c.port in 1..65535) { "Porta inválida" }
        if (c.deviceId != deviceId(ctx)) prefs(ctx).edit().remove("last_seq").apply()
        saveToken(ctx, c.token)
        prefs(ctx).edit().putString("host", c.host.trim()).putInt("port", c.port).putBoolean("tls", c.useTls)
            .putString("fingerprint", c.certificateFingerprint).putString("device_id", c.deviceId).putBoolean("migrated", true)
            .putString("trust_mode",c.trustMode).putString("account_computer",c.accountComputerId).apply()
        _config.value = c
    }
    fun saveFailoverSettings(ctx: Context, localHost: String, tailscaleHost: String, autoFailover: Boolean) {
        prefs(ctx).edit().putString("local_host", localHost).putString("tailscale_host", tailscaleHost).putBoolean("auto_failover", autoFailover).apply()
    }
    fun savePairRoutes(ctx:Context,routes:List<BridgeConfig>) {
        val public=JsonArray(routes.map { Json.encodeToJsonElement(BridgeConfig.serializer(),it.copy(token="",deviceId="")) })
        prefs(ctx).edit().putString("pair_routes",public.toString()).putString("pair_routes_device",deviceId(ctx)).apply()
    }
    fun pairRoutes(ctx:Context,current:BridgeConfig):List<BridgeConfig> {
        if(prefs(ctx).getString("pair_routes_device",null)!=current.deviceId)return listOf(current)
        return runCatching { Json.parseToJsonElement(prefs(ctx).getString("pair_routes","[]")!!).jsonArray.map {
            Json.decodeFromJsonElement(BridgeConfig.serializer(),it).copy(token=current.token,deviceId=current.deviceId,accountComputerId=current.accountComputerId)
        }.onEach(AccountRoutes::validateTls) }.getOrDefault(listOf(current))
    }
}
