package com.punk.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.punk.accessibility.PunkAccessibilityService
import com.punk.axmanager.AxShell
import com.punk.axmanager.DeviceStatusProvider
import com.punk.security.AuditLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive

data class ToolDef(val name: String, val description: String, val risk: String, val schema: String)

object ToolRegistry {
    val tools: List<ToolDef> = listOf(
        ToolDef("device_status","Resumo estruturado do estado do aparelho","reading","{}"),
        ToolDef("shizuku_status","Verifica status do serviço e permissão Shizuku (ADB)","reading","{}"),
        ToolDef("flashlight","Liga ou desliga lanterna","reversible_write","{}"),
        ToolDef("vibrate","Vibra o aparelho","reversible_write","{}"),
        ToolDef("toast","Exibe aviso na tela","reversible_write","{}"),
        ToolDef("tts_speak","Fala texto em voz alta no alto-falante do celular","reversible_write","{}"),
        ToolDef("audio_record","Grava trecho de áudio ambiente pelo microfone","reading","{}"),
        ToolDef("screen_capture_b64","Captura a tela em alta definição e retorna em Base64","reading","{}"),
        ToolDef("whatsapp_send","Envia mensagem de WhatsApp nativamente no aparelho","reversible_write","{}"),
        ToolDef("open_uber","Abre o Uber com destino pré-selecionado","reversible_write","{}"),
        ToolDef("unlock_device","Acorda a tela, desliza e digita o PIN para desbloquear","reversible_write","{}"),
        ToolDef("unlock_pin_store","Guarda o PIN cifrado pelo Android Keystore","destructive","{}"),
        ToolDef("unlock_pin_clear","Apaga o PIN guardado e sua chave criptográfica","destructive","{}"),
        ToolDef("device_wake","Acorda a tela do aparelho","reversible_write","{}"),
        ToolDef("device_lock","Bloqueia a tela do aparelho","reversible_write","{}"),
        ToolDef("install_apk_silent","Instala ou atualiza APK silenciosamente via Shizuku/ADB","destructive","{}"),
        ToolDef("set_wifi","Liga ou desliga o Wi-Fi via ADB","reversible_write","{}"),
        ToolDef("set_mobile_data","Liga ou desliga os dados móveis via ADB","reversible_write","{}"),
        ToolDef("system_state","Lê estados de conectividade, energia, brilho e rotação","reading","{}"),
        ToolDef("set_bluetooth","Liga ou desliga Bluetooth via Shizuku","reversible_write","{}"),
        ToolDef("set_airplane_mode","Liga ou desliga modo avião via Shizuku","reversible_write","{}"),
        ToolDef("set_brightness","Ajusta o brilho do sistema via Shizuku","reversible_write","{}"),
        ToolDef("set_rotation","Controla rotação automática ou orientação fixa","reversible_write","{}"),
        ToolDef("set_dnd","Ajusta o modo Não Perturbe via Shizuku","reversible_write","{}"),
        ToolDef("set_battery_saver","Liga ou desliga economia de bateria","reversible_write","{}"),
        ToolDef("send_sms","Envia SMS diretamente","reversible_write","{}"),
        ToolDef("read_sms","Lê as últimas mensagens SMS recebidas","reading","{}"),
        ToolDef("list_contacts","Lê lista de contatos do telefone","reading","{}"),
        ToolDef("contact_create","Cria um contato na agenda do aparelho","destructive","{}"),
        ToolDef("calendar_list","Lista eventos da agenda em um intervalo","reading","{}"),
        ToolDef("calendar_create","Cria um evento em uma agenda gravável","destructive","{}"),
        ToolDef("set_alarm","Cria um alarme pelo aplicativo de relógio","reversible_write","{}"),
        ToolDef("list_calls","Lê histórico de chamadas recentes","reading","{}"),
        ToolDef("list_notifications","Lê notificações recentes recebidas de apps","reading","{}"),
        ToolDef("notification_reply","Responde uma notificação pela ação inline do app","reversible_write","{}"),
        ToolDef("notification_dismiss","Dispensa uma notificação ativa","destructive","{}"),
        ToolDef("notification_open","Abre o conteúdo de uma notificação ativa","reversible_write","{}"),
        ToolDef("dial_phone","Abre discador para número de telefone","reversible_write","{}"),
        ToolDef("open_app","Abre aplicativo pelo pacote","reversible_write","{}"),
        ToolDef("force_stop","Força encerramento de um app (via Shizuku/ADB)","destructive","{}"),
        ToolDef("app_info","Lê detalhes e permissões de um pacote","reading","{}"),
        ToolDef("uninstall_app","Desinstala um pacote para o usuário ou sistema","destructive","{}"),
        ToolDef("clear_app_cache","Limpa somente o cache de um aplicativo","destructive","{}"),
        ToolDef("clear_app_data","Apaga dados, contas e sessões de um aplicativo","destructive","{}"),
        ToolDef("grant_permission","Concede uma permissão runtime a um pacote","destructive","{}"),
        ToolDef("revoke_permission","Revoga uma permissão runtime de um pacote","destructive","{}"),
        ToolDef("open_url","Abre endereço no navegador","reversible_write","{}"),
        ToolDef("open_map_route","Abre mapa ou navegação para um destino","reversible_write","{}"),
        ToolDef("share_text","Abre a folha segura de compartilhamento com texto","reversible_write","{}"),
        ToolDef("set_volume","Ajusta volume do sistema","reversible_write","{}"),
        ToolDef("get_volume","Lê volume do sistema","reading","{}"),
        ToolDef("clipboard_set","Copia texto para a área de transferência","reversible_write","{}"),
        ToolDef("clipboard_get","Lê área de transferência","reading","{}"),
        ToolDef("global_action","Executa ação global (home, back, recents, notifications, screenshot)","reversible_write","{}"),
        ToolDef("screen_dump","Extrai árvore estruturada da interface atual","reading","{}"),
        ToolDef("find_element","Busca elementos por texto, descrição ou ID","reading","{}"),
        ToolDef("find_and_click","Busca, rola automaticamente e clica em um elemento","reversible_write","{}"),
        ToolDef("accessibility_events","Lê eventos recentes da interface em sequência","reading","{}"),
        ToolDef("ocr_screen","Reconhece localmente o texto visível na tela","reading","{}"),
        ToolDef("input_tap","Toque em coordenada x,y","reversible_write","{}"),
        ToolDef("input_swipe","Gesto de arrasto entre coordenadas","reversible_write","{}"),
        ToolDef("long_press","Mantém um toque pressionado em uma coordenada","reversible_write","{}"),
        ToolDef("drag_drop","Arrasta um elemento entre duas coordenadas","reversible_write","{}"),
        ToolDef("pinch","Executa pinça ou zoom com dois dedos","reversible_write","{}"),
        ToolDef("gesture_path","Executa gesto por uma sequência de pontos","reversible_write","{}"),
        ToolDef("input_text","Digita texto no campo ativo na tela","reversible_write","{}"),
        ToolDef("click_node","Clica em elemento na tela por texto ou ID","reversible_write","{}"),
        ToolDef("scroll","Rola a tela para cima ou para baixo","reversible_write","{}"),
        ToolDef("key_event","Envia código de tecla do Android","reversible_write","{}"),
        ToolDef("screencap","Captura screenshot da tela","reading","{}"),
        ToolDef("shell_exec","Executa comando shell (com Shizuku ADB quando disponível)","destructive","{}"),
        ToolDef("pm_list","Lista pacotes instalados","reading","{}"),
        ToolDef("getprop","Lê propriedade do Android","reading","{}"),
        ToolDef("dumpsys","Lê dumpsys de serviço","reading","{}"),
        ToolDef("send_notification","Publica uma notificação na barra do sistema","reversible_write","{}"),
        ToolDef("get_location","Obtém localização GPS em tempo real com endereço completo","reading","{}"),
        ToolDef("camera_capture","Captura foto da câmera frontal ou traseira em Base64","reading","{}"),
        ToolDef("file_list","Lista arquivos do armazenamento compartilhado","reading","{}"),
        ToolDef("file_search","Busca arquivos por nome no armazenamento compartilhado","reading","{}"),
        ToolDef("file_read","Lê trecho limitado de um arquivo de texto","reading","{}"),
        ToolDef("file_mkdir","Cria diretório no armazenamento compartilhado","reversible_write","{}"),
        ToolDef("file_copy","Copia um arquivo no armazenamento compartilhado","reversible_write","{}"),
        ToolDef("file_move","Move um arquivo no armazenamento compartilhado","destructive","{}"),
        ToolDef("file_trash","Move arquivo ou diretório para uma lixeira recuperável","destructive","{}")
    ).map { it.copy(schema = ToolContracts.schemas.getValue(it.name).toString()) }

    fun isSuccessful(result: JsonObject): Boolean =
        result["error"]?.let { it !is JsonNull && ((it as? JsonPrimitive)?.content?.isNotBlank() ?: true) } != true &&
            (result["ok"] as? JsonPrimitive)?.booleanOrNull != false &&
            ((result["exitCode"] as? JsonPrimitive)?.intOrNull ?: 0) == 0

    fun catalog(): JsonArray = buildJsonArray {
        tools.forEach { tool -> add(buildJsonObject {
            put("name", tool.name); put("description", tool.description); put("risk", tool.risk)
            put("sensitive", tool.name in sensitiveTools)
            put("parameters", ToolContracts.schemas.getValue(tool.name))
        }) }
    }

    private val sensitiveTools = setOf("audio_record", "camera_capture", "screen_capture_b64", "screen_dump", "find_element",
        "find_and_click", "accessibility_events", "ocr_screen", "screencap", "clipboard_get", "read_sms", "list_contacts",
        "list_calls", "list_notifications", "notification_reply", "notification_dismiss", "notification_open", "get_location",
        "unlock_device", "unlock_pin_store", "unlock_pin_clear", "contact_create", "calendar_list", "calendar_create",
        "file_list", "file_search", "file_read", "file_copy", "file_move", "file_trash", "app_info", "uninstall_app",
        "clear_app_cache", "clear_app_data", "grant_permission", "revoke_permission", "shell_exec")

    fun capabilities(ctx: Context): JsonObject {
        val shizuku = AxShell.isShizukuAvailable()
        val accessibility = PunkAccessibilityService.isEnabled()
        val visible = ToolRuntime.isUserVisible()
        val recognitionAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
        val ttsAvailable = ctx.packageManager.queryIntentServices(Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
        val notificationsGranted = Build.VERSION.SDK_INT < 33 || ToolRuntime.hasPermission(ctx,android.Manifest.permission.POST_NOTIFICATIONS)
        val permissions = mapOf("microphone" to android.Manifest.permission.RECORD_AUDIO, "camera" to android.Manifest.permission.CAMERA,
            "location" to android.Manifest.permission.ACCESS_COARSE_LOCATION, "contacts" to android.Manifest.permission.READ_CONTACTS,
            "contactsWrite" to android.Manifest.permission.WRITE_CONTACTS, "calendarRead" to android.Manifest.permission.READ_CALENDAR,
            "calendarWrite" to android.Manifest.permission.WRITE_CALENDAR,
            "smsRead" to android.Manifest.permission.READ_SMS, "smsSend" to android.Manifest.permission.SEND_SMS,
            "callLog" to android.Manifest.permission.READ_CALL_LOG, "phoneCall" to android.Manifest.permission.CALL_PHONE)
        fun permission(name: String) = ToolRuntime.hasPermission(ctx, permissions.getValue(name))
        return buildJsonObject {
            put("accessibility", accessibility); put("shizuku", shizuku); put("userVisible", visible)
            put("speechRecognition",recognitionAvailable); put("textToSpeech",ttsAvailable)
            put("shizukuStatus", buildJsonObject { AxShell.shizukuStatus().forEach { (key, value) -> when(value) { is Boolean -> put(key,value); is Int -> put(key,value) } } })
            put("notificationListener", PunkNotificationListener.isEnabled())
            put("postNotifications",notificationsGranted)
            put("notificationForwarding", NotificationPrivacy.isForwardingEnabled(ctx))
            put("storedUnlockPin", UnlockPinVault.hasStoredPin(ctx))
            put("allFiles", Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
            put("permissions", buildJsonObject { permissions.forEach { (name, value) -> put(name, ToolRuntime.hasPermission(ctx, value)) } })
            put("tools", buildJsonArray { tools.forEach { tool ->
                val reason = when(tool.name) {
                    "send_notification" -> if(notificationsGranted) null else "Permissão de notificações pendente"
                    "tts_speak" -> if (ttsAvailable) null else "Instale ou ative um mecanismo de voz Android"
                    "audio_record" -> ToolRuntime.captureError(ctx, android.Manifest.permission.RECORD_AUDIO)
                    "camera_capture" -> ToolRuntime.captureError(ctx, android.Manifest.permission.CAMERA)
                    "flashlight" -> if (permission("camera")) null else "Permissão de câmera pendente"
                    "get_location" -> if (permission("location")) null else "Permissão de localização pendente"
                    "list_contacts" -> if (permission("contacts")) null else "Permissão de contatos pendente"
                    "contact_create" -> if (permission("contactsWrite")) null else "Permissão para editar contatos pendente"
                    "calendar_list" -> if (permission("calendarRead")) null else "Permissão para ler agenda pendente"
                    "calendar_create" -> if (permission("calendarWrite")) null else "Permissão para editar agenda pendente"
                    "read_sms" -> if (permission("smsRead")) null else "Permissão de SMS pendente"
                    "send_sms" -> if (permission("smsSend")) null else "Permissão para enviar SMS pendente"
                    "list_calls" -> if (permission("callLog")) null else "Permissão de chamadas pendente"
                    "list_notifications", "notification_reply", "notification_dismiss", "notification_open" -> if (PunkNotificationListener.isEnabled()) null else "Ative acesso a notificações"
                    "screen_dump", "input_tap", "input_swipe", "input_text", "global_action" -> if (accessibility || shizuku) null else "Ative acessibilidade ou Shizuku"
                    "find_element", "find_and_click", "accessibility_events", "click_node", "scroll", "long_press", "drag_drop", "pinch", "gesture_path" -> if (accessibility) null else "Ative acessibilidade"
                    "screen_capture_b64", "ocr_screen", "screencap", "key_event", "force_stop", "install_apk_silent", "set_wifi", "set_mobile_data", "system_state", "set_bluetooth", "set_airplane_mode", "set_brightness", "set_rotation", "set_dnd", "set_battery_saver", "app_info", "uninstall_app", "clear_app_cache", "clear_app_data", "grant_permission", "revoke_permission", "device_wake", "device_lock", "unlock_device", "dumpsys", "pm_list" -> if (shizuku) null else "Ative e autorize Shizuku"
                    "file_list", "file_search", "file_read", "file_mkdir", "file_copy", "file_move", "file_trash" -> if (Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) null else "Conceda acesso a todos os arquivos"
                    "clipboard_get", "clipboard_set" -> if (visible) null else "Abra a sessão do PUNK"
                    else -> null
                }
                add(buildJsonObject { put("name",tool.name); put("available",reason == null); if(reason != null) put("reason",reason) })
            } })
        }
    }

    /** Root also cancels the bridge task jobs; these close the native resources immediately. */
    fun cancelAll(ctx: Context) {
        ToolRuntime.activeCalls.toList().forEach { it.cancel(CancellationException("Controle interrompido pelo usuário")) }
        AxShell.cancelAll(); AudioCapture.cancelAll(); CameraCapture.cancelAll(); TtsEngine.stop()
        runCatching { (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).cancel() }
    }

    suspend fun invoke(ctx: Context, tool: String, params: JsonObject): JsonObject {
        val name = ToolContracts.canonical(tool)
        val arguments = ToolContracts.normalize(name, params)
        val job = currentCoroutineContext().job
        ToolRuntime.activeCalls.add(job)
        try {
            val validation = ToolContracts.validate(name, arguments)
            val raw = if (validation != null) buildJsonObject { put("ok", false); put("error", validation) }
                else if (name.startsWith("clipboard_") && !ToolRuntime.isUserVisible()) buildJsonObject { put("ok",false); put("error","Abra a sessão do PUNK para acessar a área de transferência.") }
                else invokeValidated(ctx, name, arguments)
            currentCoroutineContext().ensureActive()
            val success = isSuccessful(raw)
            val result = JsonObject(raw.toMutableMap().apply {
                put("ok", JsonPrimitive(success))
                if (!success && "error" !in this) put("error", JsonPrimitive("A ação não foi concluída. Verifique capacidade e estado do aparelho."))
            })
            AuditLog.append(ctx, com.punk.security.AuditEntry(AuditLog.now(), name, arguments.toString(), result.toString()))
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            val result = buildJsonObject { put("ok", false); put("error", e.message ?: "Falha ao executar ferramenta") }
            AuditLog.append(ctx, com.punk.security.AuditEntry(AuditLog.now(), name, arguments.toString(), result.toString()))
            return result
        } finally { ToolRuntime.activeCalls.remove(job) }
    }

    private suspend fun invokeValidated(ctx: Context, tool: String, params: JsonObject): JsonObject {
        val result: JsonObject = when(tool){
            "device_status" -> {
                val s = DeviceStatusProvider.collect(ctx)
                val shizuku = AxShell.shizukuStatus()
                buildJsonObject {
                    put("batteryPct", s.batteryPct)
                    put("batteryTempC", s.batteryTempC ?: -1.0)
                    put("isCharging", s.isCharging)
                    put("storageFreeMb", s.storageFreeMb)
                    put("storageTotalMb", s.storageTotalMb)
                    put("ramInfo", s.ramInfo)
                    put("axDaemon", s.axDaemon)
                    put("foregroundApp", s.foregroundApp ?: "")
                    put("shizukuPing", shizuku["ping"] as? Boolean ?: false)
                    put("shizukuGranted", shizuku["granted"] as? Boolean ?: false)
                    put("shizukuUid", shizuku["uid"] as? Int ?: -1)
                }
            }
            "shizuku_status" -> {
                val s = AxShell.shizukuStatus()
                buildJsonObject {
                    put("ok", true)
                    put("ping", s["ping"] as? Boolean ?: false)
                    put("granted", s["granted"] as? Boolean ?: false)
                    put("version", s["version"] as? Int ?: -1)
                    put("uid", s["uid"] as? Int ?: -1)
                }
            }
            "tts_speak", "speak" -> {
                val text = (params["text"] as? JsonPrimitive)?.content
                    ?: (params["message"] as? JsonPrimitive)?.content ?: "Olá Sir Felipe"
                val pitch = (params["pitch"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1.0f
                val rate = (params["rate"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 1.0f
                val ok = TtsEngine.speak(ctx, text, pitch, rate)
                buildJsonObject { put("ok", ok); put("text", text) }
            }
            "audio_record", "record_audio" -> {
                val sec = (params["durationSec"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: (params["duration"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
                val res = AudioCapture.recordSnippet(ctx, sec)
                buildJsonObject {
                    val ok = res["ok"] as? Boolean ?: false
                    put("ok", ok)
                    if (ok) {
                        put("durationSec", res["durationSec"] as? Int ?: sec)
                        put("sizeBytes", res["sizeBytes"] as? Int ?: 0)
                        put("mime", res["mime"] as? String ?: "audio/mp4")
                        put("base64", res["base64"] as? String ?: "")
                    } else {
                        put("error", res["error"] as? String ?: "Erro na gravação")
                    }
                }
            }
            "screen_capture_b64", "screenshot_b64" -> {
                val tempPath = "/sdcard/punk_screencap_${System.nanoTime()}.png"
                try {
                    val r = AxShell.screencap(tempPath)
                    if (!r.isSuccess) return buildJsonObject { put("ok", false); put("error", r.stderr.ifBlank { "Erro ao capturar tela" }) }
                    val b64Result = AxShell.exec("base64 -w 0 ${AxShell.quote(tempPath)}", requirePrivilege = true)
                    if (b64Result.isSuccess && b64Result.stdout.isNotBlank()) {
                        buildJsonObject {
                            put("ok", true)
                            put("mime", "image/png")
                            put("base64", b64Result.stdout.trim())
                        }
                    } else {
                        buildJsonObject { put("ok", false); put("error", "Falha ao codificar imagem em base64") }
                    }
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        AxShell.exec("rm -f ${AxShell.quote(tempPath)}", timeoutMs = 2000, requirePrivilege = true)
                    }
                }
            }
            "whatsapp_send" -> {
                val phone = (params["phone"] as? JsonPrimitive)?.content ?: ""
                val msg = (params["message"] as? JsonPrimitive)?.content ?: ""
                val autoSend = (params["autoSend"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                val res = AppAutomation.sendWhatsAppMessage(ctx, phone, msg, autoSend)
                buildJsonObject {
                    put("ok", res["ok"] as? Boolean ?: false)
                    put("phone", phone)
                    put("opened", res["opened"] as? Boolean ?: false)
                    put("sent", res["sent"] as? Boolean ?: false)
                    put("sendAttempted", res["sendAttempted"] as? Boolean ?: false)
                    put("deliveryVerified", false)
                    put("status", res["status"] as? String ?: "failed")
                    put("detail", res["detail"] as? String ?: "")
                    if (res.containsKey("error")) put("error", res["error"] as? String ?: "")
                }
            }
            "open_uber" -> {
                val dest = (params["destination"] as? JsonPrimitive)?.content
                val res = AppAutomation.openUber(ctx, dest)
                buildJsonObject {
                    put("ok", res["ok"] as? Boolean ?: false)
                    put("destination", dest ?: "")
                }
            }
            "unlock_device" -> {
                val explicitPin = (params["pin"] as? JsonPrimitive)?.content
                val pin = explicitPin ?: UnlockPinVault.load(ctx)
                val res = AppAutomation.unlockDevice(ctx, pin)
                buildJsonObject {
                    put("ok", res["ok"] as? Boolean ?: false)
                    put("unlocked", res["unlocked"] as? Boolean ?: false)
                    put("usedStoredPin", explicitPin == null && pin != null)
                    if (res["error"] != null) put("error", res["error"] as String)
                }
            }
            "unlock_pin_store" -> runCatching {
                UnlockPinVault.store(ctx, params.getValue("pin").jsonPrimitive.content)
                buildJsonObject { put("ok", true); put("stored", true); put("storage", "android_keystore") }
            }.getOrElse { buildJsonObject { put("ok", false); put("error", it.message ?: "Não foi possível guardar o PIN") } }
            "unlock_pin_clear" -> runCatching {
                UnlockPinVault.clear(ctx)
                buildJsonObject { put("ok", true); put("stored", false) }
            }.getOrElse { buildJsonObject { put("ok", false); put("error", it.message ?: "Não foi possível apagar o PIN") } }
            "device_wake" -> PrivilegedTools.wake()
            "device_lock" -> PrivilegedTools.lock()
            "install_apk_silent" -> {
                val path = (params["path"] as? JsonPrimitive)?.content ?: "/sdcard/download/app.apk"
                val tempPath = "/data/local/tmp/punk_install_${System.nanoTime()}.apk"
                val command = "cp ${AxShell.quote(path)} ${AxShell.quote(tempPath)} && chmod 644 ${AxShell.quote(tempPath)} && pm install -r ${AxShell.quote(tempPath)}; rc=\$?; rm -f ${AxShell.quote(tempPath)}; exit \$rc"
                val r = AxShell.exec(command, timeoutMs = 120_000, requirePrivilege = true)
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("stdout", r.stdout)
                    put("stderr", r.stderr)
                    put("exitCode", r.exitCode)
                }
            }
            "set_wifi" -> {
                val enabled = (params["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                PrivilegedTools.setWifi(enabled)
            }
            "set_mobile_data" -> {
                val enabled = (params["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                PrivilegedTools.setMobileData(enabled)
            }
            "system_state" -> PrivilegedTools.state()
            "set_bluetooth" -> PrivilegedTools.setBluetooth(params.getValue("enabled").jsonPrimitive.boolean)
            "set_airplane_mode" -> PrivilegedTools.setAirplane(params.getValue("enabled").jsonPrimitive.boolean)
            "set_brightness" -> PrivilegedTools.setBrightness(params.getValue("level").jsonPrimitive.int)
            "set_rotation" -> PrivilegedTools.setRotation(params.getValue("auto").jsonPrimitive.boolean, params["rotation"]?.jsonPrimitive?.intOrNull ?: 0)
            "set_dnd" -> PrivilegedTools.setDnd(params.getValue("mode").jsonPrimitive.content)
            "set_battery_saver" -> PrivilegedTools.setBatterySaver(params.getValue("enabled").jsonPrimitive.boolean)
            "flashlight", "torch" -> {
                val state = (params["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: (params["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: (params["state"] as? JsonPrimitive)?.content?.let { it == "on" || it == "true" || it == "1" }
                    ?: true
                try {
                    val camManager = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val cameraId = camManager.cameraIdList.firstOrNull { id ->
                        val chars = camManager.getCameraCharacteristics(id)
                        chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: camManager.cameraIdList.firstOrNull() ?: "0"
                    camManager.setTorchMode(cameraId, state)
                    buildJsonObject {
                        put("ok", true)
                        put("flashlight", state)
                        put("cameraId", cameraId)
                    }
                } catch (e: Exception) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", e.message ?: "Erro ao controlar lanterna")
                    }
                }
            }
            "vibrate" -> {
                val ms = (params["durationMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 500L
                try {
                    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(ms)
                    }
                    buildJsonObject { put("ok", true); put("vibratedMs", ms) }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao vibrar") }
                }
            }
            "toast" -> {
                val text = (params["text"] as? JsonPrimitive)?.content ?: "PUNK Companion"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
                }
                buildJsonObject { put("ok", true); put("text", text) }
            }
            "send_sms", "sms" -> {
                val to = (params["phone"] as? JsonPrimitive)?.content
                    ?: (params["to"] as? JsonPrimitive)?.content ?: ""
                val msg = (params["message"] as? JsonPrimitive)?.content
                    ?: (params["text"] as? JsonPrimitive)?.content ?: ""
                try {
                    val smsMgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    val parts = smsMgr.divideMessage(msg)
                    if (parts.size > 1) smsMgr.sendMultipartTextMessage(to, null, parts, null, null)
                    else smsMgr.sendTextMessage(to, null, msg, null, null)
                    buildJsonObject {
                        put("ok", true)
                        put("to", to)
                        put("message", msg)
                        put("method", "direct_sms")
                        put("status", "submitted_to_modem")
                        put("deliveryVerified", false)
                    }
                } catch (e: Exception) {
                    try {
                        val uri = Uri.parse("smsto:$to")
                        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                            putExtra("sms_body", msg)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(intent)
                        buildJsonObject {
                            put("ok", false)
                            put("method", "intent_fallback")
                            put("opened", true)
                            put("status", "draft_opened")
                            put("error", "SMS não enviado. Rascunho aberto no aplicativo de mensagens.")
                            put("to", to)
                            put("message", msg)
                        }
                    } catch (e2: Exception) {
                        buildJsonObject {
                            put("ok", false)
                            put("error", e.message ?: e2.message ?: "Falha ao enviar SMS")
                        }
                    }
                }
            }
            "read_sms", "list_sms" -> {
                val limit = (params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 7
                try {
                    val cursor = ctx.contentResolver.query(
                        Uri.parse("content://sms/inbox"),
                        arrayOf("_id", "address", "body", "date"),
                        null,
                        null,
                        "date DESC"
                    )
                    val smsList = buildJsonArray {
                        var count = 0
                        if (cursor != null) {
                            val addrIdx = cursor.getColumnIndex("address")
                            val bodyIdx = cursor.getColumnIndex("body")
                            val dateIdx = cursor.getColumnIndex("date")
                            while (cursor.moveToNext() && count < limit) {
                                val addr = if (addrIdx != -1) cursor.getString(addrIdx) else ""
                                val body = if (bodyIdx != -1) cursor.getString(bodyIdx) else ""
                                val dateLong = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                                add(buildJsonObject {
                                    put("from", addr)
                                    put("body", body)
                                    put("date", dateLong)
                                })
                                count++
                            }
                            cursor.close()
                        }
                    }
                    buildJsonObject {
                        put("ok", true)
                        put("count", smsList.size)
                        put("sms", smsList)
                    }
                } catch (e: Exception) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", e.message ?: "Erro ao ler SMS")
                    }
                }
            }
            "list_contacts" -> {
                val limit = (params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
                val filter = (params["filter"] as? JsonPrimitive)?.content ?: ""
                try {
                    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    val projection = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    val selection = if (filter.isNotBlank()) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null
                    val selectionArgs = if (filter.isNotBlank()) arrayOf("%$filter%") else null
                    val cursor = ctx.contentResolver.query(uri, projection, selection, selectionArgs, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                    val list = buildJsonArray {
                        var count = 0
                        if (cursor != null) {
                            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (cursor.moveToNext() && count < limit) {
                                val name = if (nameIdx != -1) cursor.getString(nameIdx) else ""
                                val num = if (numIdx != -1) cursor.getString(numIdx) else ""
                                add(buildJsonObject {
                                    put("name", name)
                                    put("number", num)
                                })
                                count++
                            }
                            cursor.close()
                        }
                    }
                    buildJsonObject {
                        put("ok", true)
                        put("count", list.size)
                        put("contacts", list)
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao ler contatos") }
                }
            }
            "contact_create" -> PersonalDataTools.createContact(
                ctx,
                params.getValue("name").jsonPrimitive.content,
                params["phone"]?.jsonPrimitive?.content,
                params["email"]?.jsonPrimitive?.content
            )
            "calendar_list" -> PersonalDataTools.listCalendarEvents(
                ctx,
                params.getValue("fromEpochSec").jsonPrimitive.long,
                params.getValue("toEpochSec").jsonPrimitive.long,
                params["limit"]?.jsonPrimitive?.intOrNull ?: 30
            )
            "calendar_create" -> PersonalDataTools.createCalendarEvent(
                ctx,
                params.getValue("title").jsonPrimitive.content,
                params.getValue("startEpochSec").jsonPrimitive.long,
                params.getValue("endEpochSec").jsonPrimitive.long,
                params["location"]?.jsonPrimitive?.content,
                params["description"]?.jsonPrimitive?.content,
                params["calendarId"]?.jsonPrimitive?.longOrNull
            )
            "set_alarm" -> PersonalDataTools.setAlarm(
                ctx,
                params.getValue("hour").jsonPrimitive.int,
                params.getValue("minute").jsonPrimitive.int,
                params["message"]?.jsonPrimitive?.content.orEmpty(),
                params["skipUi"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "list_calls" -> {
                val limit = (params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
                try {
                    val cursor = ctx.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                    val list = buildJsonArray {
                        var count = 0
                        if (cursor != null) {
                            val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                            val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                            val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                            while (cursor.moveToNext() && count < limit) {
                                val num = if (numIdx != -1) cursor.getString(numIdx) else ""
                                val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: "" else ""
                                val typeInt = if (typeIdx != -1) cursor.getInt(typeIdx) else 0
                                val typeStr = when(typeInt) {
                                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                    CallLog.Calls.MISSED_TYPE -> "missed"
                                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                                    else -> "other"
                                }
                                val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                                val dur = if (durIdx != -1) cursor.getLong(durIdx) else 0L
                                add(buildJsonObject {
                                    put("number", num)
                                    put("name", name)
                                    put("type", typeStr)
                                    put("date", date)
                                    put("durationSec", dur)
                                })
                                count++
                            }
                            cursor.close()
                        }
                    }
                    buildJsonObject {
                        put("ok", true)
                        put("count", list.size)
                        put("calls", list)
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao ler chamadas") }
                }
            }
            "list_notifications" -> {
                if (!PunkNotificationListener.isEnabled()) return buildJsonObject { put("ok",false); put("error","Acesso a notificações não está ativo") }
                val limit = (params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
                val notifs = PunkNotificationListener.recentNotifications.take(limit)
                val arr = buildJsonArray {
                    for (n in notifs) {
                        add(buildJsonObject {
                            put("key", n.key)
                            put("id", n.id)
                            put("package", n.packageName)
                            put("title", n.title)
                            put("text", n.text)
                            put("category", n.category)
                            put("postTime", n.postTime)
                            put("canReply", n.canReply)
                            put("canDismiss", n.canDismiss)
                        })
                    }
                }
                buildJsonObject {
                    put("ok", true)
                    put("count", arr.size)
                    put("listener_active", PunkNotificationListener.instance != null)
                    put("notifications", arr)
                }
            }
            "notification_reply" -> {
                val key = params.getValue("key").jsonPrimitive.content
                val message = params.getValue("message").jsonPrimitive.content
                val result = PunkNotificationListener.instance?.reply(key, message)
                    ?: return buildJsonObject { put("ok", false); put("error", "Acesso a notificações não está ativo") }
                buildJsonObject {
                    put("ok", result.isSuccess); put("key", key)
                    if (result.isFailure) put("error", result.exceptionOrNull()?.message ?: "Não foi possível responder")
                }
            }
            "notification_dismiss" -> {
                val key = params.getValue("key").jsonPrimitive.content
                val result = PunkNotificationListener.instance?.dismiss(key)
                    ?: return buildJsonObject { put("ok", false); put("error", "Acesso a notificações não está ativo") }
                buildJsonObject {
                    put("ok", result.isSuccess); put("key", key)
                    if (result.isFailure) put("error", result.exceptionOrNull()?.message ?: "Não foi possível dispensar")
                }
            }
            "notification_open" -> {
                val key = params.getValue("key").jsonPrimitive.content
                val result = PunkNotificationListener.instance?.open(key)
                    ?: return buildJsonObject { put("ok", false); put("error", "Acesso a notificações não está ativo") }
                buildJsonObject {
                    put("ok", result.isSuccess); put("key", key)
                    if (result.isFailure) put("error", result.exceptionOrNull()?.message ?: "Não foi possível abrir")
                }
            }
            "dial_phone", "call_phone" -> {
                val number = (params["phone"] as? JsonPrimitive)?.content
                    ?: (params["number"] as? JsonPrimitive)?.content ?: ""
                val direct = (params["direct"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                try {
                    val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    val intent = Intent(action, Uri.parse("tel:$number")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                    buildJsonObject {
                        put("ok", true)
                        put("number", number)
                        put("direct", direct)
                    }
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(intent)
                        buildJsonObject { put("ok", true); put("number", number); put("direct", false) }
                    } catch (e2: Exception) {
                        buildJsonObject { put("ok", false); put("error", e.message ?: e2.message ?: "Erro ao discar") }
                    }
                }
            }
            "open_app", "launch_app" -> {
                val pkg = (params["package"] as? JsonPrimitive)?.content
                    ?: (params["pkg"] as? JsonPrimitive)?.content ?: ""
                try {
                    val pm = ctx.packageManager
                    if (Build.VERSION.SDK_INT >= 33) {
                        val sender = pm.getLaunchIntentSenderForPackage(pkg)
                        sender.sendIntent(ctx, 0, null, null, null)
                        return buildJsonObject { put("ok",true); put("package",pkg); put("status","launch_requested") }
                    }
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(intent)
                        buildJsonObject { put("ok", true); put("package", pkg); put("status","launch_requested") }
                    } else {
                        buildJsonObject { put("ok", false); put("error", "App não encontrado: $pkg") }
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao abrir app") }
                }
            }
            "force_stop" -> {
                val pkg = (params["package"] as? JsonPrimitive)?.content
                    ?: (params["pkg"] as? JsonPrimitive)?.content ?: ""
                val res = AxShell.forceStop(pkg)
                buildJsonObject {
                    put("ok", res.isSuccess)
                    put("package", pkg)
                    put("source", res.source)
                }
            }
            "app_info" -> PrivilegedTools.appInfo(params.getValue("package").jsonPrimitive.content)
            "uninstall_app" -> PrivilegedTools.uninstall(
                params.getValue("package").jsonPrimitive.content,
                params["keepData"]?.jsonPrimitive?.booleanOrNull ?: false,
                params["userOnly"]?.jsonPrimitive?.booleanOrNull ?: true
            )
            "clear_app_cache" -> PrivilegedTools.clearCache(params.getValue("package").jsonPrimitive.content)
            "clear_app_data" -> PrivilegedTools.clearData(params.getValue("package").jsonPrimitive.content)
            "grant_permission" -> PrivilegedTools.permission(
                params.getValue("package").jsonPrimitive.content,
                params.getValue("permission").jsonPrimitive.content,
                true
            )
            "revoke_permission" -> PrivilegedTools.permission(
                params.getValue("package").jsonPrimitive.content,
                params.getValue("permission").jsonPrimitive.content,
                false
            )
            "open_url" -> {
                val url = (params["url"] as? JsonPrimitive)?.content ?: ""
                try {
                    val formatted = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatted)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(intent)
                    buildJsonObject { put("ok", true); put("url", formatted) }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao abrir URL") }
                }
            }
            "open_map_route" -> IntentTools.openMap(
                ctx,
                params.getValue("query").jsonPrimitive.content,
                params["navigation"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "share_text" -> IntentTools.shareText(
                ctx,
                params.getValue("text").jsonPrimitive.content,
                params["title"]?.jsonPrimitive?.content
            )
            "set_volume" -> {
                val streamName = (params["stream"] as? JsonPrimitive)?.content ?: "media"
                val level = (params["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 50
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val streamType = when (streamName.lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "call" -> AudioManager.STREAM_VOICE_CALL
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val maxVol = am.getStreamMaxVolume(streamType)
                    val target = (level.coerceIn(0, 100) * maxVol) / 100
                    am.setStreamVolume(streamType, target, 0)
                    buildJsonObject {
                        put("ok", true)
                        put("stream", streamName)
                        put("level", target)
                        put("max", maxVol)
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao ajustar volume") }
                }
            }
            "get_volume" -> {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    buildJsonObject {
                        put("ok", true)
                        put("media", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                        put("media_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        put("ring", am.getStreamVolume(AudioManager.STREAM_RING))
                        put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                        put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao ler volume") }
                }
            }
            "clipboard_set" -> {
                val text = (params["text"] as? JsonPrimitive)?.content ?: ""
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("punk", text))
                    }
                    buildJsonObject { put("ok", true); put("text", text) }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro no clipboard") }
                }
            }
            "clipboard_get" -> {
                try {
                    var clipText = ""
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    }
                    buildJsonObject { put("ok", true); put("text", clipText) }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao ler clipboard") }
                }
            }
            "global_action" -> {
                val action = (params["action"] as? JsonPrimitive)?.content ?: "home"
                val ok = PunkAccessibilityService.instance?.triggerGlobalAction(action) ?: false
                if (ok) {
                    buildJsonObject {
                        put("ok", true)
                        put("action", action)
                        put("method", "accessibility")
                    }
                } else {
                    val command = when(action.lowercase()) {
                        "back" -> "input keyevent 4"
                        "home" -> "input keyevent 3"
                        "recents" -> "input keyevent 187"
                        "notifications" -> "cmd statusbar expand-notifications"
                        "quick_settings" -> "cmd statusbar expand-settings"
                        "lock_screen" -> "input keyevent 223"
                        "screenshot" -> "screencap -p /sdcard/punk_screencap.png"
                        else -> return buildJsonObject { put("ok", false); put("error", "Ação global inválida") }
                    }
                    val r = AxShell.exec(command, requirePrivilege = true)
                    buildJsonObject {
                        put("ok", r.isSuccess)
                        put("action", action)
                        put("method", r.source)
                        if (!r.isSuccess) put("error", r.stderr.ifBlank { "Ação do sistema falhou" })
                    }
                }
            }
            "screen_dump" -> {
                val maxNodes = params["maxNodes"]?.jsonPrimitive?.intOrNull ?: 220
                val maxDepth = params["maxDepth"]?.jsonPrimitive?.intOrNull ?: 18
                val service = PunkAccessibilityService.instance
                if (service != null) {
                    JsonObject(service.snapshotJson(maxNodes, maxDepth).toMutableMap().apply {
                        put("source", JsonPrimitive("accessibility"))
                    })
                } else {
                    val raw = AxShell.exec("uiautomator dump /dev/stdout", requirePrivilege = true)
                    buildJsonObject {
                        put("ok", raw.isSuccess && raw.stdout.isNotBlank())
                        put("source", raw.source)
                        put("nodes", raw.stdout.take(12000))
                        if (!raw.isSuccess) put("error", raw.stderr.ifBlank { "Não foi possível ler a tela" })
                    }
                }
            }
            "find_element" -> {
                val query = params.getValue("query").jsonPrimitive.content
                val byId = params["byId"]?.jsonPrimitive?.booleanOrNull
                val exact = params["exact"]?.jsonPrimitive?.booleanOrNull ?: false
                val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10
                val service = PunkAccessibilityService.instance
                    ?: return buildJsonObject { put("ok", false); put("error", "Serviço de acessibilidade não está ativo") }
                service.findNodes(query, byId, exact, limit)
            }
            "find_and_click" -> {
                val query = params.getValue("query").jsonPrimitive.content
                val byId = params["byId"]?.jsonPrimitive?.booleanOrNull
                val exact = params["exact"]?.jsonPrimitive?.booleanOrNull ?: false
                val retries = params["retries"]?.jsonPrimitive?.intOrNull ?: 2
                val scrolls = params["scrolls"]?.jsonPrimitive?.intOrNull ?: 2
                val direction = params["direction"]?.jsonPrimitive?.content ?: "down"
                val service = PunkAccessibilityService.instance
                    ?: return buildJsonObject { put("ok", false); put("error", "Serviço de acessibilidade não está ativo") }
                service.findAndClick(query, byId, exact, retries, scrolls, direction)
            }
            "accessibility_events" -> {
                val sinceSeq = params["sinceSeq"]?.jsonPrimitive?.longOrNull ?: 0L
                val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50
                val service = PunkAccessibilityService.instance
                    ?: return buildJsonObject { put("ok", false); put("error", "Serviço de acessibilidade não está ativo") }
                service.recentEvents(sinceSeq, limit)
            }
            "ocr_screen" -> OcrEngine.recognizeScreen()
            "input_tap" -> {
                val x = (params["x"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val y = (params["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val tapOk = PunkAccessibilityService.instance?.performTap(x, y)
                if (tapOk == true) {
                    buildJsonObject { put("ok", true); put("method", "accessibility") }
                } else {
                    val r = AxShell.inputTap(x, y)
                    buildJsonObject { put("ok", r.isSuccess); put("method", r.source) }
                }
            }
            "input_swipe", "swipe" -> {
                val x1 = (params["x1"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val y1 = (params["y1"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val x2 = (params["x2"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val y2 = (params["y2"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val dur = (params["durationMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 300L
                val axOk = PunkAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, dur)
                if (axOk == true) {
                    buildJsonObject { put("ok", true); put("method", "accessibility") }
                } else {
                    val r = AxShell.inputSwipe(x1, y1, x2, y2, dur.toInt())
                    buildJsonObject { put("ok", r.isSuccess); put("method", r.source) }
                }
            }
            "long_press" -> {
                val x = params.getValue("x").jsonPrimitive.int
                val y = params.getValue("y").jsonPrimitive.int
                val duration = params["durationMs"]?.jsonPrimitive?.longOrNull ?: 800L
                val ok = PunkAccessibilityService.instance?.performLongPress(x, y, duration) ?: false
                buildJsonObject { put("ok", ok); put("x", x); put("y", y); put("durationMs", duration) }
            }
            "drag_drop" -> {
                val points = listOf(
                    params.getValue("x1").jsonPrimitive.int to params.getValue("y1").jsonPrimitive.int,
                    params.getValue("x2").jsonPrimitive.int to params.getValue("y2").jsonPrimitive.int
                )
                val duration = params["durationMs"]?.jsonPrimitive?.longOrNull ?: 1_000L
                val ok = PunkAccessibilityService.instance?.performPath(points, duration) ?: false
                buildJsonObject { put("ok", ok); put("durationMs", duration) }
            }
            "pinch" -> {
                val duration = params["durationMs"]?.jsonPrimitive?.longOrNull ?: 500L
                val ok = PunkAccessibilityService.instance?.performPinch(
                    params.getValue("centerX").jsonPrimitive.int,
                    params.getValue("centerY").jsonPrimitive.int,
                    params.getValue("startSpan").jsonPrimitive.int,
                    params.getValue("endSpan").jsonPrimitive.int,
                    duration
                ) ?: false
                buildJsonObject { put("ok", ok); put("durationMs", duration) }
            }
            "gesture_path" -> {
                val rawPoints = params.getValue("points").jsonPrimitive.content.split(';').filter(String::isNotBlank)
                val points = rawPoints.mapNotNull { raw ->
                    val parts = raw.split(',').map(String::trim)
                    if (parts.size == 2) parts[0].toIntOrNull()?.let { x -> parts[1].toIntOrNull()?.let { y -> x to y } } else null
                }
                if (points.size != rawPoints.size || points.size !in 2..32) {
                    buildJsonObject { put("ok", false); put("error", "Use de 2 a 32 pontos no formato x,y;x,y") }
                } else {
                    val duration = params["durationMs"]?.jsonPrimitive?.longOrNull ?: 600L
                    val ok = PunkAccessibilityService.instance?.performPath(points, duration) ?: false
                    buildJsonObject { put("ok", ok); put("points", points.size); put("durationMs", duration) }
                }
            }
            "input_text" -> {
                val text = (params["text"] as? JsonPrimitive)?.content ?: ""
                val clear = (params["clearFirst"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val axOk = PunkAccessibilityService.instance?.inputText(text, clear)
                if (axOk == true) {
                    buildJsonObject { put("ok", true); put("method", "accessibility"); put("text", text) }
                } else {
                    if (clear) return buildJsonObject { put("ok",false); put("error","Substituir texto exige acessibilidade e um campo editável focado") }
                    val r = AxShell.inputText(text)
                    buildJsonObject { put("ok", r.isSuccess); put("method", r.source); put("text", text) }
                }
            }
            "click_node" -> {
                val query = (params["query"] as? JsonPrimitive)?.content
                    ?: (params["text"] as? JsonPrimitive)?.content ?: ""
                val byId = (params["byId"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val ok = PunkAccessibilityService.instance?.clickNode(query, byId) ?: false
                buildJsonObject {
                    put("ok", ok)
                    put("query", query)
                    put("byId", byId)
                }
            }
            "scroll" -> {
                val dir = (params["direction"] as? JsonPrimitive)?.content ?: "down"
                val ok = PunkAccessibilityService.instance?.scroll(dir) ?: false
                buildJsonObject {
                    put("ok", ok)
                    put("direction", dir)
                }
            }
            "key_event" -> {
                val code = (params["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 3
                val r = AxShell.keyEvent(code)
                buildJsonObject { put("ok", r.isSuccess); put("code", code); put("source", r.source) }
            }
            "screencap" -> {
                val path = (params["path"] as? JsonPrimitive)?.content ?: "/sdcard/punk_screencap.png"
                val r = AxShell.screencap(path)
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("path", path)
                    put("source", r.source)
                    put("stdout", r.stdout)
                }
            }
            "shell_exec" -> {
                val cmd = (params["cmd"] as? JsonPrimitive)?.content
                    ?: (params["command"] as? JsonPrimitive)?.content ?: ""
                val t = (params["timeoutMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 15000L
                val r = AxShell.exec(cmd, t)
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("stdout", r.stdout.take(8000))
                    put("stderr", r.stderr.take(4000))
                    put("exitCode", r.exitCode)
                    put("source", r.source)
                }
            }
            "pm_list" -> {
                val f = (params["filter"] as? JsonPrimitive)?.content ?: ""
                val raw = AxShell.exec("pm list packages", requirePrivilege = true)
                val filtered = if (f.isBlank()) raw.stdout else raw.stdout.lineSequence().filter { it.contains(f, true) }.joinToString("\n")
                buildJsonObject { put("ok",raw.isSuccess); put("packages", filtered.take(12000)); if(!raw.isSuccess) put("error",raw.stderr) }
            }
            "getprop" -> {
                val k = (params["key"] as? JsonPrimitive)?.content ?: ""
                val r = AxShell.exec("getprop ${AxShell.quote(k)}")
                buildJsonObject { put("ok",r.isSuccess); put("value", r.stdout.trim()); if(!r.isSuccess) put("error",r.stderr) }
            }
            "dumpsys" -> {
                val svc = (params["service"] as? JsonPrimitive)?.content ?: "battery"
                val r = AxShell.exec("dumpsys ${AxShell.quote(svc)}", requirePrivilege = true)
                buildJsonObject { put("ok",r.isSuccess); put("output", r.stdout.take(10000)); if(!r.isSuccess) put("error",r.stderr) }
            }
            "send_notification", "notify" -> {
                val title = (params["title"] as? JsonPrimitive)?.content ?: "PUNK"
                val text = (params["text"] as? JsonPrimitive)?.content
                    ?: (params["message"] as? JsonPrimitive)?.content ?: "⚡ Notificação enviada por Sir Felipe!"
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "punk_alerts"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val chan = android.app.NotificationChannel(channelId, "PUNK Alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
                        nm.createNotificationChannel(chan)
                    }
                    val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.app.Notification.Builder(ctx, channelId)
                    } else {
                        @Suppress("DEPRECATION")
                        android.app.Notification.Builder(ctx)
                    }.apply {
                        setContentTitle(title)
                        setContentText(text)
                        setSmallIcon(android.R.drawable.ic_dialog_info)
                        setAutoCancel(true)
                    }.build()
                    nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
                    buildJsonObject { put("ok", true); put("title", title); put("text", text) }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Erro ao postar notificação") }
                }
            }
            "get_location", "location", "gps" -> {
                val loc = LocationProvider.getCurrentLocation(ctx,
                    params["maxAgeMs"]?.jsonPrimitive?.longOrNull ?: 30000L,
                    params["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 15000L)
                buildJsonObject {
                    val ok = loc["ok"] as? Boolean ?: false
                    put("ok", ok)
                    if (ok) {
                        put("latitude", loc["latitude"] as? Double ?: 0.0)
                        put("longitude", loc["longitude"] as? Double ?: 0.0)
                        put("accuracy", loc["accuracy"] as? Double ?: 0.0)
                        put("altitude", loc["altitude"] as? Double ?: 0.0)
                        put("provider", loc["provider"] as? String ?: "")
                        put("address", loc["address"] as? String ?: "")
                        put("city", loc["city"] as? String ?: "")
                        put("state", loc["state"] as? String ?: "")
                        put("country", loc["country"] as? String ?: "")
                        put("timestamp", loc["timestamp"] as? Long ?: 0L)
                        put("ageMs", loc["ageMs"] as? Long ?: 0L)
                        put("fresh", loc["fresh"] as? Boolean ?: false)
                    } else {
                        put("error", loc["error"] as? String ?: "Falha ao obter localização")
                    }
                }
            }
            "camera_capture", "take_photo" -> {
                val facing = (params["facing"] as? JsonPrimitive)?.content
                    ?: (params["lens"] as? JsonPrimitive)?.content
                    ?: (params["camera"] as? JsonPrimitive)?.content ?: "back"
                val res = CameraCapture.capturePhoto(ctx, facing)
                buildJsonObject {
                    val ok = res["ok"] as? Boolean ?: false
                    put("ok", ok)
                    if (ok) {
                        put("mime", res["mime"] as? String ?: "image/jpeg")
                        put("cameraId", res["cameraId"] as? String ?: "")
                        put("sizeBytes", res["sizeBytes"] as? Int ?: 0)
                        put("base64", res["base64"] as? String ?: "")
                    } else {
                        put("error", res["error"] as? String ?: "Erro na captura da foto")
                    }
                }
            }
            "file_list" -> FileTools.list(ctx, params.getValue("path").jsonPrimitive.content, params["limit"]?.jsonPrimitive?.intOrNull ?: 100)
            "file_search" -> FileTools.search(
                ctx,
                params.getValue("path").jsonPrimitive.content,
                params.getValue("query").jsonPrimitive.content,
                params["maxDepth"]?.jsonPrimitive?.intOrNull ?: 3,
                params["limit"]?.jsonPrimitive?.intOrNull ?: 50
            )
            "file_read" -> FileTools.read(ctx, params.getValue("path").jsonPrimitive.content, params["maxBytes"]?.jsonPrimitive?.intOrNull ?: 65_536)
            "file_mkdir" -> FileTools.mkdir(ctx, params.getValue("path").jsonPrimitive.content)
            "file_copy" -> FileTools.copy(
                ctx,
                params.getValue("source").jsonPrimitive.content,
                params.getValue("destination").jsonPrimitive.content,
                move = false,
                overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "file_move" -> FileTools.copy(
                ctx,
                params.getValue("source").jsonPrimitive.content,
                params.getValue("destination").jsonPrimitive.content,
                move = true,
                overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "file_trash" -> FileTools.trash(ctx, params.getValue("path").jsonPrimitive.content)
            else -> buildJsonObject { put("error", "tool desconhecida: $tool") }
        }
        return result
    }
}
