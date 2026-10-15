package com.punk.bridge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.prefs by preferencesDataStore("punk_bridge")

object BridgePrefs {
    private val K_HOST = stringPreferencesKey("host")
    private val K_PORT = intPreferencesKey("port")
    private val K_TOKEN = stringPreferencesKey("token")

    fun host(ctx: Context): String = runBlocking { ctx.prefs.data.first()[K_HOST] ?: "100.64.0.1" }
    fun port(ctx: Context): Int = runBlocking { ctx.prefs.data.first()[K_PORT] ?: 8765 }
    fun token(ctx: Context): String = runBlocking { ctx.prefs.data.first()[K_TOKEN] ?: "" }

    fun save(ctx: Context, c: BridgeConfig) {
        runBlocking {
            ctx.prefs.edit { e ->
                e[K_HOST] = c.host
                e[K_PORT] = c.port
                e[K_TOKEN] = c.token
            }
        }
    }
}
