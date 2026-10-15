package com.punk.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.punk.accessibility.PunkAccessibilityService
import com.punk.axmanager.AxShell
import kotlinx.coroutines.delay

object AppAutomation {

    suspend fun sendWhatsAppMessage(
        ctx: Context,
        phone: String,
        message: String,
        autoSend: Boolean = true
    ): Map<String, Any> {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.isBlank()) {
            return mapOf("ok" to false, "error" to "Telefone inválido")
        }

        try {
            // Abre conversa direta via deep link oficial do WhatsApp
            val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)

            if (!autoSend) {
                return mapOf("ok" to true, "opened" to true, "autoSend" to false)
            }

            // Aguarda o WhatsApp carregar a tela de conversa com o texto pré-preenchido
            delay(1500)

            // Tenta clicar no botão de enviar via acessibilidade
            val ax = PunkAccessibilityService.instance
            var clicked = false
            if (ax != null) {
                // Tenta pelo ID do botão de enviar do WhatsApp
                clicked = ax.clickNode("send", byId = true)
                if (!clicked) {
                    clicked = ax.clickNode("Enviar", byId = false)
                }
                if (!clicked) {
                    clicked = ax.clickNode("Send", byId = false)
                }
            }

            // Se a acessibilidade não conseguir clicar direto no botão (ex: layout dinâmico),
            // usa Shizuku para enviar a tecla Enter ou toque na coordenada do botão
            if (!clicked && AxShell.isShizukuAvailable()) {
                // Tenta Enter
                AxShell.keyEvent(66) // KEYCODE_ENTER
                clicked = true
            }

            return mapOf(
                "ok" to true,
                "phone" to cleanPhone,
                "opened" to true,
                "sent" to clicked
            )
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to (e.message ?: "Falha ao abrir WhatsApp"))
        }
    }

    suspend fun openUber(ctx: Context, destination: String? = null): Map<String, Any> {
        return try {
            val uriStr = if (!destination.isNullOrBlank()) {
                "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=${Uri.encode(destination)}"
            } else {
                "uber://"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            mapOf("ok" to true, "destination" to (destination ?: ""))
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: "Falha ao abrir Uber"))
        }
    }

    suspend fun unlockDevice(pin: String? = null): Map<String, Any> {
        return try {
            // 1. Acorda a tela
            AxShell.keyEvent(224) // KEYCODE_WAKEUP
            delay(300)

            // 2. Swipe para cima para abrir o teclado/PIN se estiver na lockscreen
            AxShell.inputSwipe(540, 1800, 540, 600, 250)
            delay(400)

            // 3. Se fornecido PIN, digita e aperta enter
            if (!pin.isNullOrBlank()) {
                AxShell.inputText(pin)
                delay(200)
                AxShell.keyEvent(66) // KEYCODE_ENTER
            }

            mapOf("ok" to true, "deviceAwake" to true)
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: "Falha ao desbloquear aparelho"))
        }
    }
}
