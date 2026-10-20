package com.punk.tools

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.punk.accessibility.PunkAccessibilityService
import com.punk.axmanager.AxShell
import kotlinx.coroutines.delay

object AppAutomation {
    suspend fun sendWhatsAppMessage(ctx: Context, phone: String, message: String, autoSend: Boolean = true): Map<String, Any> {
        val cleanPhone = phone.filter(Char::isDigit)
        if (cleanPhone.length !in 7..15) return mapOf("ok" to false, "error" to "Telefone inválido")
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")).apply {
                setPackage("com.whatsapp"); flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            if (!autoSend) return mapOf("ok" to true, "opened" to true, "sent" to false, "status" to "draft_opened")
            var clicked = false
            repeat(10) {
                if (!clicked) {
                    delay(250)
                    val service = PunkAccessibilityService.instance
                    clicked = service?.clickNode("com.whatsapp:id/send", true) == true
                }
            }
            // A click is observable, delivery is not. Never replace this with an unconditional Enter.
            mapOf("ok" to clicked, "opened" to true, "sent" to false, "sendAttempted" to clicked,
                "status" to if (clicked) "send_button_activated_delivery_unverified" else "draft_opened",
                "deliveryVerified" to false,
                "detail" to if (clicked) "Botão enviar acionado; entrega não confirmada." else "Rascunho aberto; botão enviar não encontrado.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { mapOf("ok" to false, "error" to (e.message ?: "Falha ao abrir WhatsApp")) }
    }

    suspend fun openUber(ctx: Context, destination: String? = null): Map<String, Any> = try {
        val uri = if (destination.isNullOrBlank()) "uber://" else "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=${Uri.encode(destination)}"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        mapOf("ok" to true, "opened" to true, "destination" to destination.orEmpty())
    } catch (e: Exception) { mapOf("ok" to false, "error" to (e.message ?: "Falha ao abrir Uber")) }

    suspend fun unlockDevice(ctx: Context, pin: String? = null): Map<String, Any> {
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked) return mapOf("ok" to true, "unlocked" to true)
        val wake = AxShell.keyEvent(224)
        if (!wake.isSuccess) return mapOf("ok" to false, "error" to wake.stderr.ifBlank { "Shizuku necessário" })
        delay(300)
        val metrics = ctx.resources.displayMetrics
        val swipe = AxShell.inputSwipe(metrics.widthPixels / 2, (metrics.heightPixels * .85).toInt(), metrics.widthPixels / 2, (metrics.heightPixels * .25).toInt(), 250)
        if (!swipe.isSuccess) return mapOf("ok" to false, "error" to "Falha ao abrir tela de desbloqueio")
        delay(300)
        if (!pin.isNullOrBlank()) {
            if (!AxShell.inputText(pin).isSuccess || !AxShell.keyEvent(66).isSuccess) return mapOf("ok" to false, "error" to "Não foi possível enviar PIN")
            delay(500)
        }
        val unlocked = !keyguard.isKeyguardLocked
        return if (unlocked) mapOf("ok" to true, "unlocked" to true)
            else mapOf("ok" to false, "unlocked" to false, "error" to "Tela acordada; o aparelho continua bloqueado. Desbloqueie no telefone.")
    }
}
