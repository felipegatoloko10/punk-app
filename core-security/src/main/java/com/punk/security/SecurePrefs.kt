package com.punk.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "punk_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun getToken(ctx: Context): String = prefs(ctx).getString("bridge_token","") ?: ""
    fun setToken(ctx: Context, v: String) { prefs(ctx).edit().putString("bridge_token", v).apply() }
}
