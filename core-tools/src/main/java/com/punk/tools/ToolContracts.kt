package com.punk.tools

import kotlinx.serialization.json.*

/** The catalog and argument validation share one contract, including legacy aliases. */
internal object ToolContracts {
    private fun field(type: String, description: String, min: Int? = null, max: Int? = null, values: List<String>? = null) = buildJsonObject {
        put("type", type); put("description", description)
        if (min != null) put(if (type == "string") "minLength" else "minimum", min)
        if (max != null) put(if (type == "string") "maxLength" else "maximum", max)
        if (values != null) put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    private fun text(description: String, max: Int = 10000, allowEmpty: Boolean = false) = field("string", description, if (allowEmpty) 0 else 1, max)
    private fun integer(description: String, min: Int, max: Int) = field("integer", description, min, max)
    private fun boolean(description: String) = field("boolean", description)
    private fun voiceNumber(description: String) = buildJsonObject { put("type","number"); put("description",description); put("minimum",0.5); put("maximum",2.0) }
    private fun choice(description: String, vararg values: String) = field("string", description, values = values.toList())
    private fun contract(vararg fields: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields.toMap()))
        put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
    }
    private val limit = integer("Quantidade máxima de itens", 1, 100)
    private val path = text("Caminho absoluto no armazenamento compartilhado", 1024)
    private val pkg = text("Nome completo do pacote Android", 255)
    private val phone = text("Número com código do país", 40)
    private val coordinate = integer("Coordenada na tela em pixels", 0, 10000)
    val schemas: Map<String, JsonObject> = mapOf(
        "device_status" to contract(), "shizuku_status" to contract(),
        "flashlight" to contract("enabled" to boolean("Ligar ou desligar"), required = listOf("enabled")),
        "vibrate" to contract("durationMs" to integer("Duração em ms", 1, 10000)),
        "toast" to contract("text" to text("Mensagem", 1000), required = listOf("text")),
        "tts_speak" to contract("text" to text("Texto para falar"), "pitch" to voiceNumber("Tom da voz"), "rate" to voiceNumber("Velocidade da fala"), required = listOf("text")),
        "audio_record" to contract("durationSec" to integer("Duração em segundos; requer app visível", 2, 60)),
        "screen_capture_b64" to contract(),
        "whatsapp_send" to contract("phone" to phone, "message" to text("Mensagem"), "autoSend" to boolean("Tentar acionar enviar; entrega não é confirmada"), required = listOf("phone", "message")),
        "open_uber" to contract("destination" to text("Endereço de destino", 1000)),
        "unlock_device" to contract("pin" to text("PIN desta execução; ausente usa o cofre local, se configurado", 32)),
        "unlock_pin_store" to contract("pin" to text("PIN cifrado no Android Keystore", 16), required = listOf("pin")),
        "unlock_pin_clear" to contract(),
        "device_wake" to contract(),
        "device_lock" to contract(),
        "install_apk_silent" to contract("path" to path, required = listOf("path")),
        "set_wifi" to contract("enabled" to boolean("Wi-Fi ligado"), required = listOf("enabled")),
        "set_mobile_data" to contract("enabled" to boolean("Dados móveis ligados"), required = listOf("enabled")),
        "system_state" to contract(),
        "set_bluetooth" to contract("enabled" to boolean("Bluetooth ligado"), required = listOf("enabled")),
        "set_airplane_mode" to contract("enabled" to boolean("Modo avião ligado"), required = listOf("enabled")),
        "set_brightness" to contract("level" to integer("Brilho entre 0 e 255", 0, 255), required = listOf("level")),
        "set_rotation" to contract("auto" to boolean("Rotação automática"), "rotation" to integer("Orientação fixa: 0, 1, 2 ou 3", 0, 3), required = listOf("auto")),
        "set_dnd" to contract("mode" to choice("Modo não perturbe", "none", "priority", "alarms", "all"), required = listOf("mode")),
        "set_battery_saver" to contract("enabled" to boolean("Economia de bateria ligada"), required = listOf("enabled")),
        "send_sms" to contract("phone" to phone, "message" to text("Mensagem SMS", 5000), required = listOf("phone", "message")),
        "read_sms" to contract("limit" to limit),
        "list_contacts" to contract("limit" to limit, "filter" to text("Parte do nome", 200, true)),
        "contact_create" to contract("name" to text("Nome do contato", 300), "phone" to text("Telefone", 60, true), "email" to text("E-mail", 320, true), required = listOf("name")),
        "calendar_list" to contract("fromEpochSec" to integer("Início em epoch segundos", 0, Int.MAX_VALUE), "toEpochSec" to integer("Fim em epoch segundos", 0, Int.MAX_VALUE), "limit" to limit, required = listOf("fromEpochSec", "toEpochSec")),
        "calendar_create" to contract("title" to text("Título", 500), "startEpochSec" to integer("Início em epoch segundos", 0, Int.MAX_VALUE), "endEpochSec" to integer("Fim em epoch segundos", 0, Int.MAX_VALUE), "location" to text("Local", 1000, true), "description" to text("Descrição", 5000, true), "calendarId" to integer("ID da agenda", 0, Int.MAX_VALUE), required = listOf("title", "startEpochSec", "endEpochSec")),
        "set_alarm" to contract("hour" to integer("Hora", 0, 23), "minute" to integer("Minuto", 0, 59), "message" to text("Descrição", 500, true), "skipUi" to boolean("Criar sem abrir confirmação"), required = listOf("hour", "minute")),
        "list_calls" to contract("limit" to limit),
        "list_notifications" to contract("limit" to limit),
        "notification_reply" to contract("key" to text("Chave retornada por list_notifications", 1000), "message" to text("Resposta", 5000), required = listOf("key", "message")),
        "notification_dismiss" to contract("key" to text("Chave retornada por list_notifications", 1000), required = listOf("key")),
        "notification_open" to contract("key" to text("Chave retornada por list_notifications", 1000), required = listOf("key")),
        "dial_phone" to contract("phone" to phone, "direct" to boolean("Iniciar chamada diretamente; padrão false"), required = listOf("phone")),
        "open_app" to contract("package" to pkg, required = listOf("package")),
        "force_stop" to contract("package" to pkg, required = listOf("package")),
        "app_info" to contract("package" to pkg, required = listOf("package")),
        "uninstall_app" to contract("package" to pkg, "keepData" to boolean("Preservar dados"), "userOnly" to boolean("Remover apenas para o usuário atual"), required = listOf("package")),
        "clear_app_cache" to contract("package" to pkg, required = listOf("package")),
        "clear_app_data" to contract("package" to pkg, required = listOf("package")),
        "grant_permission" to contract("package" to pkg, "permission" to text("Permissão Android completa", 255), required = listOf("package", "permission")),
        "revoke_permission" to contract("package" to pkg, "permission" to text("Permissão Android completa", 255), required = listOf("package", "permission")),
        "open_url" to contract("url" to text("URL HTTP ou HTTPS", 4096), required = listOf("url")),
        "open_map_route" to contract("query" to text("Endereço ou coordenadas", 1000), "navigation" to boolean("Iniciar modo de navegação"), required = listOf("query")),
        "share_text" to contract("text" to text("Texto para compartilhar", 20000), "title" to text("Título", 300, true), required = listOf("text")),
        "set_volume" to contract("stream" to choice("Canal", "media", "ring", "call", "alarm", "notification"), "level" to integer("Percentual", 0, 100), required = listOf("level")),
        "get_volume" to contract(),
        "clipboard_set" to contract("text" to text("Texto", 10000, true), required = listOf("text")),
        "clipboard_get" to contract(),
        "global_action" to contract("action" to choice("Ação do Android", "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot"), required = listOf("action")),
        "screen_dump" to contract("maxNodes" to integer("Máximo de nós", 1, 500), "maxDepth" to integer("Profundidade máxima", 1, 30)),
        "find_element" to contract("query" to text("Texto, descrição ou ID do elemento", 1000), "byId" to boolean("true: apenas ID; false: texto/descrição; ausente: ambos"), "exact" to boolean("Exigir correspondência exata"), "limit" to integer("Máximo de resultados", 1, 50), required = listOf("query")),
        "find_and_click" to contract("query" to text("Texto, descrição ou ID do elemento", 1000), "byId" to boolean("true: apenas ID; false: texto/descrição; ausente: ambos"), "exact" to boolean("Exigir correspondência exata"), "retries" to integer("Novas tentativas", 0, 5), "scrolls" to integer("Rolagens automáticas", 0, 8), "direction" to choice("Direção de busca", "up", "down", "forward", "backward"), required = listOf("query")),
        "accessibility_events" to contract("sinceSeq" to integer("Retornar eventos posteriores a esta sequência", 0, Int.MAX_VALUE), "limit" to integer("Máximo de eventos", 1, 200)),
        "ocr_screen" to contract(),
        "input_tap" to contract("x" to coordinate, "y" to coordinate, required = listOf("x", "y")),
        "input_swipe" to contract("x1" to coordinate, "y1" to coordinate, "x2" to coordinate, "y2" to coordinate, "durationMs" to integer("Duração do gesto", 50, 2000), required = listOf("x1", "y1", "x2", "y2")),
        "long_press" to contract("x" to coordinate, "y" to coordinate, "durationMs" to integer("Duração do toque", 400, 5000), required = listOf("x", "y")),
        "drag_drop" to contract("x1" to coordinate, "y1" to coordinate, "x2" to coordinate, "y2" to coordinate, "durationMs" to integer("Duração do arrasto", 400, 5000), required = listOf("x1", "y1", "x2", "y2")),
        "pinch" to contract("centerX" to coordinate, "centerY" to coordinate, "startSpan" to integer("Distância inicial entre dedos", 20, 2000), "endSpan" to integer("Distância final entre dedos", 20, 2000), "durationMs" to integer("Duração", 100, 3000), required = listOf("centerX", "centerY", "startSpan", "endSpan")),
        "gesture_path" to contract("points" to text("Pontos x,y separados por ponto e vírgula", 1000), "durationMs" to integer("Duração", 100, 5000), required = listOf("points")),
        "input_text" to contract("text" to text("Texto Unicode", 10000, true), "clearFirst" to boolean("Substituir texto existente"), required = listOf("text")),
        "click_node" to contract("query" to text("Texto ou ID completo pacote:id/nome", 1000), "byId" to boolean("Buscar pelo ID completo"), required = listOf("query")),
        "scroll" to contract("direction" to choice("Direção", "up", "down", "forward", "backward")),
        "key_event" to contract("code" to integer("Android KeyEvent code", 0, 400), required = listOf("code")),
        "screencap" to contract("path" to path),
        "shell_exec" to contract("cmd" to text("Comando shell explícito", 10000), "timeoutMs" to integer("Prazo em ms", 100, 120000), required = listOf("cmd")),
        "pm_list" to contract("filter" to text("Filtro do pacote", 255, true)),
        "getprop" to contract("key" to text("Propriedade Android", 255), required = listOf("key")),
        "dumpsys" to contract("service" to text("Nome do serviço", 100), required = listOf("service")),
        "send_notification" to contract("title" to text("Título", 200), "text" to text("Mensagem", 2000), required = listOf("text")),
        "get_location" to contract("maxAgeMs" to integer("Idade máxima de localização em cache", 0, 300000), "timeoutMs" to integer("Prazo para atualização", 1000, 30000)),
        "camera_capture" to contract("facing" to choice("Câmera; requer app visível", "front", "back")),
        "file_list" to contract("path" to path, "limit" to integer("Máximo de itens", 1, 500), required = listOf("path")),
        "file_search" to contract("path" to path, "query" to text("Nome parcial", 500), "maxDepth" to integer("Profundidade", 0, 8), "limit" to integer("Máximo de resultados", 1, 200), required = listOf("path", "query")),
        "file_read" to contract("path" to path, "maxBytes" to integer("Máximo de bytes", 1, 262144), required = listOf("path")),
        "file_mkdir" to contract("path" to path, required = listOf("path")),
        "file_copy" to contract("source" to path, "destination" to path, "overwrite" to boolean("Sobrescrever destino"), required = listOf("source", "destination")),
        "file_move" to contract("source" to path, "destination" to path, "overwrite" to boolean("Sobrescrever destino"), required = listOf("source", "destination")),
        "file_trash" to contract("path" to path, required = listOf("path"))
    )
    private val aliases = mapOf("speak" to "tts_speak", "record_audio" to "audio_record", "screenshot_b64" to "screen_capture_b64",
        "torch" to "flashlight", "sms" to "send_sms", "list_sms" to "read_sms", "call_phone" to "dial_phone",
        "launch_app" to "open_app", "swipe" to "input_swipe", "notify" to "send_notification",
        "location" to "get_location", "gps" to "get_location", "take_photo" to "camera_capture")
    fun canonical(name: String): String = aliases[name] ?: name
    fun normalize(name: String, params: JsonObject): JsonObject {
        val result = params.toMutableMap()
        fun alias(target: String, vararg old: String) {
            if (target !in result) old.firstNotNullOfOrNull { result[it] }?.let { result[target] = it }
            old.forEach { result.remove(it) }
        }
        when (name) {
            "tts_speak", "toast", "send_notification" -> alias("text", "message")
            "audio_record" -> alias("durationSec", "duration")
            "send_sms" -> { alias("phone", "to"); alias("message", "text") }
            "dial_phone" -> alias("phone", "number")
            "open_app", "force_stop", "app_info", "uninstall_app", "clear_app_cache", "clear_app_data", "grant_permission", "revoke_permission" -> alias("package", "packageName", "pkg")
            "input_text" -> alias("clearFirst", "clear")
            "click_node", "find_element", "find_and_click" -> alias("query", "text")
            "shell_exec" -> alias("cmd", "command")
            "camera_capture" -> alias("facing", "lens", "camera")
            "flashlight" -> {
                alias("enabled", "on", "state")
                (result["enabled"] as? JsonPrimitive)?.content?.let { raw ->
                    when (raw.lowercase()) { "on", "1" -> result["enabled"] = JsonPrimitive(true); "off", "0" -> result["enabled"] = JsonPrimitive(false) }
                }
            }
        }
        return JsonObject(result)
    }
    fun validate(name: String, params: JsonObject): String? {
        val schema = schemas[name] ?: return "Ferramenta desconhecida: $name"
        val properties = schema["properties"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        required.firstOrNull { it !in params }?.let { return "Parâmetro obrigatório: $it" }
        params.keys.firstOrNull { it !in properties }?.let { return "Parâmetro desconhecido: $it" }
        for ((key, value) in params) {
            val spec = properties.getValue(key).jsonObject
            val primitive = value as? JsonPrimitive ?: return "Parâmetro $key deve ser primitivo"
            val type = spec.getValue("type").jsonPrimitive.content
            val number = primitive.doubleOrNull
            val valid = when (type) {
                "boolean" -> primitive.booleanOrNull != null
                "integer" -> primitive.longOrNull != null
                "number" -> number != null && number.isFinite()
                else -> primitive.isString && primitive.content.length >= (spec["minLength"]?.jsonPrimitive?.int ?: 0) && primitive.content.length <= (spec["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)
            }
            if (!valid) return "Parâmetro $key inválido; esperado $type"
            if (number != null && (number < (spec["minimum"]?.jsonPrimitive?.double ?: -Double.MAX_VALUE) || number > (spec["maximum"]?.jsonPrimitive?.double ?: Double.MAX_VALUE))) return "Parâmetro $key fora do intervalo permitido"
            val choices = spec["enum"] as? JsonArray
            if (choices != null && choices.none { it.jsonPrimitive.content == primitive.content }) return "Valor inválido para $key"
        }
        if (name in listOf("open_app", "force_stop", "app_info", "uninstall_app", "clear_app_cache", "clear_app_data", "grant_permission", "revoke_permission") && !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(params.getValue("package").jsonPrimitive.content)) return "Pacote Android inválido"
        if (name in listOf("unlock_device", "unlock_pin_store") && params["pin"]?.jsonPrimitive?.content?.let { !Regex("[0-9]{4,16}").matches(it) } == true) return "PIN deve conter de 4 a 16 dígitos"
        if (name == "tts_speak" && listOf("pitch", "rate").any { params[it]?.jsonPrimitive?.doubleOrNull?.let { value -> value !in 0.5..2.0 } == true }) return "Tom e velocidade devem estar entre 0.5 e 2.0"
        if (name in listOf("screencap", "install_apk_silent", "file_list", "file_search", "file_read", "file_mkdir", "file_trash") && params["path"]?.jsonPrimitive?.content?.let { !it.startsWith("/") || '\u0000' in it } == true) return "Use caminho absoluto válido"
        if (name in listOf("file_copy", "file_move") && listOf("source", "destination").any { key -> params[key]?.jsonPrimitive?.content?.let { !it.startsWith("/") || '\u0000' in it } == true }) return "Use caminhos absolutos válidos"
        if (name in listOf("grant_permission", "revoke_permission") && params["permission"]?.jsonPrimitive?.content?.let { !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) } == true) return "Permissão Android inválida"
        return null
    }
}
