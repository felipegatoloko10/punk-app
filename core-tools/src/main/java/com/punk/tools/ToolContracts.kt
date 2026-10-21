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
        "unlock_device" to contract("pin" to text("PIN fornecido explicitamente; nunca é persistido", 32)),
        "install_apk_silent" to contract("path" to path, required = listOf("path")),
        "set_wifi" to contract("enabled" to boolean("Wi-Fi ligado"), required = listOf("enabled")),
        "set_mobile_data" to contract("enabled" to boolean("Dados móveis ligados"), required = listOf("enabled")),
        "send_sms" to contract("phone" to phone, "message" to text("Mensagem SMS", 5000), required = listOf("phone", "message")),
        "read_sms" to contract("limit" to limit),
        "list_contacts" to contract("limit" to limit, "filter" to text("Parte do nome", 200, true)),
        "list_calls" to contract("limit" to limit),
        "list_notifications" to contract("limit" to limit),
        "notification_reply" to contract("key" to text("Chave retornada por list_notifications", 1000), "message" to text("Resposta", 5000), required = listOf("key", "message")),
        "notification_dismiss" to contract("key" to text("Chave retornada por list_notifications", 1000), required = listOf("key")),
        "notification_open" to contract("key" to text("Chave retornada por list_notifications", 1000), required = listOf("key")),
        "dial_phone" to contract("phone" to phone, "direct" to boolean("Iniciar chamada diretamente; padrão false"), required = listOf("phone")),
        "open_app" to contract("package" to pkg, required = listOf("package")),
        "force_stop" to contract("package" to pkg, required = listOf("package")),
        "open_url" to contract("url" to text("URL HTTP ou HTTPS", 4096), required = listOf("url")),
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
        "camera_capture" to contract("facing" to choice("Câmera; requer app visível", "front", "back"))
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
            "open_app", "force_stop" -> alias("package", "packageName", "pkg")
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
        if (name in listOf("open_app", "force_stop") && !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(params.getValue("package").jsonPrimitive.content)) return "Pacote Android inválido"
        if (name == "unlock_device" && params["pin"]?.jsonPrimitive?.content?.let { !Regex("[0-9]{4,16}").matches(it) } == true) return "PIN deve conter de 4 a 16 dígitos"
        if (name == "tts_speak" && listOf("pitch", "rate").any { params[it]?.jsonPrimitive?.doubleOrNull?.let { value -> value !in 0.5..2.0 } == true }) return "Tom e velocidade devem estar entre 0.5 e 2.0"
        if (name in listOf("screencap", "install_apk_silent") && params["path"]?.jsonPrimitive?.content?.let { !it.startsWith("/") || '\u0000' in it } == true) return "Use caminho absoluto válido"
        return null
    }
}
