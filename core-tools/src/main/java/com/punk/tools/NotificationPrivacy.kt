package com.punk.tools

import android.content.Context

object NotificationPrivacy {
    private const val PREFS = "punk_notification_privacy"
    fun isForwardingEnabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("forwarding_enabled", false)
    fun setForwardingEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("forwarding_enabled", enabled).apply()
    }
    fun protect(text: String, packageName: String = ""): String {
        val lower = text.lowercase()
        val financialApp = listOf("nubank", "itau", "bradesco", "picpay", "caixa", "bank", "wallet").any { packageName.contains(it, true) }
        val secretWords = Regex("(?i)otp|one.?time|verification|verifica[çc][aã]o|c[oó]digo|senha|password|autentica|token|seguran[çc]a")
        return if (financialApp || secretWords.containsMatchIn(lower) || Regex("(?<![0-9])[0-9]{4,8}(?![0-9])").containsMatchIn(text)) "[Conteúdo protegido]" else text.take(500)
    }
}
