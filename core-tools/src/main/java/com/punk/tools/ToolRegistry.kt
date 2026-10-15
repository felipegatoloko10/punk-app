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
        ToolDef("install_apk_silent","Instala ou atualiza APK silenciosamente via Shizuku/ADB","destructive","{}"),
        ToolDef("set_wifi","Liga ou desliga o Wi-Fi via ADB","reversible_write","{}"),
        ToolDef("set_mobile_data","Liga ou desliga os dados móveis via ADB","reversible_write","{}"),
        ToolDef("send_sms","Envia SMS diretamente","reversible_write","{}"),
        ToolDef("read_sms","Lê as últimas mensagens SMS recebidas","reading","{}"),
        ToolDef("list_contacts","Lê lista de contatos do telefone","reading","{}"),
        ToolDef("list_calls","Lê histórico de chamadas recentes","reading","{}"),
        ToolDef("list_notifications","Lê notificações recentes recebidas de apps","reading","{}"),
        ToolDef("dial_phone","Abre discador para número de telefone","reversible_write","{}"),
        ToolDef("open_app","Abre aplicativo pelo pacote","reversible_write","{}"),
        ToolDef("force_stop","Força encerramento de um app (via Shizuku/ADB)","destructive","{}"),
        ToolDef("open_url","Abre endereço no navegador","reversible_write","{}"),
        ToolDef("set_volume","Ajusta volume do sistema","reversible_write","{}"),
        ToolDef("get_volume","Lê volume do sistema","reading","{}"),
        ToolDef("clipboard_set","Copia texto para a área de transferência","reversible_write","{}"),
        ToolDef("clipboard_get","Lê área de transferência","reading","{}"),
        ToolDef("global_action","Executa ação global (home, back, recents, notifications, screenshot)","reversible_write","{}"),
        ToolDef("screen_dump","Extrai árvore visual da tela atual","reading","{}"),
        ToolDef("input_tap","Toque em coordenada x,y","reversible_write","{}"),
        ToolDef("input_swipe","Gesto de arrasto entre coordenadas","reversible_write","{}"),
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
        ToolDef("camera_capture","Captura foto da câmera frontal ou traseira em Base64","reading","{}")
    )

    suspend fun invoke(ctx: Context, tool: String, params: JsonObject): JsonObject {
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
                val tempPath = "/sdcard/punk_screencap_temp.png"
                val r = AxShell.screencap(tempPath)
                if (r.isSuccess) {
                    val b64Result = AxShell.exec("base64 -w 0 $tempPath && rm -f $tempPath")
                    if (b64Result.isSuccess && b64Result.stdout.isNotBlank()) {
                        buildJsonObject {
                            put("ok", true)
                            put("mime", "image/png")
                            put("base64", b64Result.stdout.trim())
                        }
                    } else {
                        buildJsonObject { put("ok", false); put("error", "Falha ao codificar imagem em base64") }
                    }
                } else {
                    buildJsonObject { put("ok", false); put("error", r.stderr.ifBlank { "Erro ao capturar tela" }) }
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
                val pin = (params["pin"] as? JsonPrimitive)?.content
                val res = AppAutomation.unlockDevice(pin)
                buildJsonObject {
                    put("ok", res["ok"] as? Boolean ?: false)
                }
            }
            "install_apk_silent" -> {
                val path = (params["path"] as? JsonPrimitive)?.content ?: "/sdcard/download/app.apk"
                val r = AxShell.exec("pm install -r -d $path")
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("stdout", r.stdout)
                    put("stderr", r.stderr)
                    put("exitCode", r.exitCode)
                }
            }
            "set_wifi" -> {
                val enabled = (params["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                val r = AxShell.exec("svc wifi " + (if (enabled) "enable" else "disable"))
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("wifi", enabled)
                }
            }
            "set_mobile_data" -> {
                val enabled = (params["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                val r = AxShell.exec("svc data " + (if (enabled) "enable" else "disable"))
                buildJsonObject {
                    put("ok", r.isSuccess)
                    put("mobile_data", enabled)
                }
            }
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
                    smsMgr.sendTextMessage(to, null, msg, null, null)
                    buildJsonObject {
                        put("ok", true)
                        put("to", to)
                        put("message", msg)
                        put("method", "direct_sms")
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
                            put("ok", true)
                            put("method", "intent_fallback")
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
                val limit = (params["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
                val notifs = PunkNotificationListener.recentNotifications.take(limit)
                val arr = buildJsonArray {
                    for (n in notifs) {
                        add(buildJsonObject {
                            put("package", n.packageName)
                            put("title", n.title)
                            put("text", n.text)
                            put("postTime", n.postTime)
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
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(intent)
                        buildJsonObject { put("ok", true); put("package", pkg) }
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
            "set_volume" -> {
                val streamName = (params["stream"] as? JsonPrimitive)?.content ?: "media"
                val level = (params["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 50
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val streamType = when (streamName.lowercase()) {
                        "ring", "call" -> AudioManager.STREAM_RING
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
                    val keycode = when(action.lowercase()) {
                        "back" -> 4
                        "home" -> 3
                        "recents", "app_switch" -> 187
                        "notifications" -> 83
                        else -> 3
                    }
                    val r = AxShell.keyEvent(keycode)
                    buildJsonObject {
                        put("ok", r.isSuccess)
                        put("action", action)
                        put("method", "shizuku_keyevent")
                        put("code", keycode)
                    }
                }
            }
            "screen_dump" -> {
                val axSnapshot = PunkAccessibilityService.instance?.snapshotCompact()
                if (!axSnapshot.isNullOrBlank() && !axSnapshot.contains("sem janela ativa")) {
                    buildJsonObject {
                        put("ok", true)
                        put("source", "accessibility")
                        put("nodes", axSnapshot)
                    }
                } else {
                    val raw = AxShell.exec("uiautomator dump /dev/stdout")
                    buildJsonObject {
                        put("ok", true)
                        put("source", raw.source)
                        put("nodes", raw.stdout.take(12000))
                    }
                }
            }
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
            "input_text" -> {
                val text = (params["text"] as? JsonPrimitive)?.content ?: ""
                val clear = (params["clear"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val axOk = PunkAccessibilityService.instance?.inputText(text, clear)
                if (axOk == true) {
                    buildJsonObject { put("ok", true); put("method", "accessibility"); put("text", text) }
                } else {
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
                val cmd = (params["cmd"] as? JsonPrimitive)?.content ?: ""
                val t = (params["timeoutMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 15000L
                val r = AxShell.exec(cmd, t)
                buildJsonObject {
                    put("stdout", r.stdout.take(8000))
                    put("stderr", r.stderr.take(4000))
                    put("exitCode", r.exitCode)
                    put("source", r.source)
                }
            }
            "pm_list" -> {
                val f = (params["filter"] as? JsonPrimitive)?.content ?: ""
                val raw = AxShell.listPackages()
                val filtered = if (f.isBlank()) raw else raw.lineSequence().filter { it.contains(f, true) }.joinToString("\n")
                buildJsonObject { put("packages", filtered.take(12000)) }
            }
            "getprop" -> {
                val k = (params["key"] as? JsonPrimitive)?.content ?: ""
                val v = AxShell.getProp(k)
                buildJsonObject { put("value", v) }
            }
            "dumpsys" -> {
                val svc = (params["service"] as? JsonPrimitive)?.content ?: "battery"
                val v = AxShell.dumpsys(svc)
                buildJsonObject { put("output", v.take(10000)) }
            }
            "send_notification", "notify" -> {
                val title = (params["title"] as? JsonPrimitive)?.content ?: "Mr. Punk (JARVIS)"
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
                val loc = LocationProvider.getCurrentLocation(ctx)
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
            else -> buildJsonObject { put("error", "tool desconhecida: $tool") }
        }
        AuditLog.append(ctx, com.punk.security.AuditEntry(AuditLog.now(), tool, params.toString(), result.toString()))
        return result
    }
}
