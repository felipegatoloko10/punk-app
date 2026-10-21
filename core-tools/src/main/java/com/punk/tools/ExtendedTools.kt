package com.punk.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.punk.axmanager.AxShell
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

internal object FileTools {
    private fun roots(ctx: Context): List<File> = listOfNotNull(
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull(),
        ctx.getExternalFilesDir(null)?.canonicalFile
    ).distinctBy { it.path }

    private fun resolve(ctx: Context, path: String, allowRoot: Boolean = true): File {
        require('\u0000' !in path) { "Caminho inválido" }
        val file = File(path).canonicalFile
        val root = roots(ctx).firstOrNull { file.path == it.path || file.path.startsWith(it.path + File.separator) }
            ?: error("Acesso permitido somente ao armazenamento compartilhado")
        if (!allowRoot) require(file.path != root.path) { "A raiz do armazenamento não pode ser alterada" }
        return file
    }

    fun list(ctx: Context, path: String, limit: Int): JsonObject = runCatching {
        val directory = resolve(ctx, path)
        require(directory.isDirectory) { "Diretório não encontrado" }
        val entries = directory.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(limit.coerceIn(1, 500))
        buildJsonObject {
            put("ok", true); put("path", directory.path); put("count", entries.size)
            put("entries", buildJsonArray { entries.forEach { add(it.metadata()) } })
        }
    }.getOrElse(::failure)

    fun search(ctx: Context, path: String, query: String, maxDepth: Int, limit: Int): JsonObject = runCatching {
        val root = resolve(ctx, path)
        require(root.isDirectory) { "Diretório não encontrado" }
        val wanted = query.trim().lowercase()
        require(wanted.isNotBlank()) { "Busca vazia" }
        data class Pending(val file: File, val depth: Int)
        val queue = ArrayDeque<Pending>().apply { add(Pending(root, 0)) }
        val matches = mutableListOf<File>()
        while (queue.isNotEmpty() && matches.size < limit.coerceIn(1, 200)) {
            val (directory, depth) = queue.removeFirst()
            directory.listFiles().orEmpty().forEach { child ->
                if (matches.size >= limit) return@forEach
                val safeChild = runCatching { resolve(ctx, child.path) }.getOrNull() ?: return@forEach
                if (safeChild.name.lowercase().contains(wanted)) matches += safeChild
                if (safeChild.isDirectory && depth < maxDepth.coerceIn(0, 8) && safeChild.name !in setOf("Android", ".Trash")) {
                    queue.add(Pending(safeChild, depth + 1))
                }
            }
        }
        buildJsonObject {
            put("ok", true); put("path", root.path); put("query", query); put("count", matches.size)
            put("matches", buildJsonArray { matches.forEach { add(it.metadata()) } })
        }
    }.getOrElse(::failure)

    fun read(ctx: Context, path: String, maxBytes: Int): JsonObject = runCatching {
        val file = resolve(ctx, path, allowRoot = false)
        require(file.isFile) { "Arquivo não encontrado" }
        val cap = maxBytes.coerceIn(1, 262_144)
        val output = ByteArrayOutputStream(minOf(file.length().coerceAtLeast(0L), cap.toLong()).toInt())
        var truncated = false
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (output.size() <= cap) {
                val read = input.read(buffer)
                if (read < 0) break
                val remaining = cap - output.size()
                if (read > remaining) {
                    output.write(buffer, 0, remaining.coerceAtLeast(0)); truncated = true; break
                }
                output.write(buffer, 0, read)
            }
        }
        buildJsonObject {
            put("ok", true); put("path", file.path); put("size", file.length()); put("truncated", truncated || file.length() > cap)
            put("text", output.toByteArray().toString(Charsets.UTF_8))
        }
    }.getOrElse(::failure)

    fun mkdir(ctx: Context, path: String): JsonObject = runCatching {
        val directory = resolve(ctx, path, allowRoot = false)
        val created = directory.exists() && directory.isDirectory || directory.mkdirs()
        require(created) { "Não foi possível criar o diretório" }
        buildJsonObject { put("ok", true); put("path", directory.path) }
    }.getOrElse(::failure)

    fun copy(ctx: Context, source: String, destination: String, move: Boolean, overwrite: Boolean): JsonObject = runCatching {
        val from = resolve(ctx, source, allowRoot = false)
        val to = resolve(ctx, destination, allowRoot = false)
        require(from.isFile) { "A origem precisa ser um arquivo" }
        require(!to.exists() || overwrite) { "O destino já existe" }
        to.parentFile?.mkdirs()
        if (move && from.renameTo(to)) Unit else {
            from.copyTo(to, overwrite = overwrite)
            if (move) require(from.delete()) { "Arquivo copiado, mas a origem não pôde ser removida" }
        }
        buildJsonObject { put("ok", true); put("source", from.path); put("destination", to.path); put("moved", move); put("bytes", to.length()) }
    }.getOrElse(::failure)

    fun trash(ctx: Context, path: String): JsonObject = runCatching {
        val source = resolve(ctx, path, allowRoot = false)
        require(source.exists()) { "Arquivo ou diretório não encontrado" }
        val storageRoot = roots(ctx).first { source.path == it.path || source.path.startsWith(it.path + File.separator) }
        val trash = File(storageRoot, ".Trash/Punk").apply { mkdirs() }
        val target = File(trash, "${System.currentTimeMillis()}-${source.name}")
        val moved = source.renameTo(target)
        if (!moved) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = false) else source.copyTo(target, overwrite = false)
            require(source.deleteRecursively()) { "Item copiado para a lixeira, mas a origem não pôde ser removida" }
        }
        buildJsonObject { put("ok", true); put("original", source.path); put("trashPath", target.path); put("recoverable", true) }
    }.getOrElse(::failure)

    private fun File.metadata(): JsonObject = buildJsonObject {
        put("name", name); put("path", path); put("directory", isDirectory); put("size", if (isFile) length() else 0L)
        put("modified", lastModified()); put("readable", canRead()); put("writable", canWrite())
    }

    private fun failure(error: Throwable) = buildJsonObject { put("ok", false); put("error", error.message ?: "Falha no gerenciador de arquivos") }
}

internal object PersonalDataTools {
    fun createContact(ctx: Context, name: String, phone: String?, email: String?): JsonObject = runCatching {
        require(ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Permissão para editar contatos pendente" }
        require(phone?.isNotBlank() == true || email?.isNotBlank() == true) { "Informe telefone ou e-mail" }
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
        operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()
        phone?.takeIf { it.isNotBlank() }?.let {
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
        }
        email?.takeIf { it.isNotBlank() }?.let {
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, it)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME).build()
        }
        val results = ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        buildJsonObject { put("ok", true); put("name", name); put("rawContactUri", results.firstOrNull()?.uri?.toString().orEmpty()) }
    }.getOrElse(::failure)

    fun listCalendarEvents(ctx: Context, fromEpochSec: Long, toEpochSec: Long, limit: Int): JsonObject = runCatching {
        require(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Permissão para ler agenda pendente" }
        val events = buildJsonArray {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.CALENDAR_ID),
                "${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?",
                arrayOf((fromEpochSec * 1000).toString(), (toEpochSec * 1000).toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < limit.coerceIn(1, 100)) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0)); put("title", cursor.getString(1).orEmpty()); put("startMs", cursor.getLong(2))
                        put("endMs", cursor.getLong(3)); put("location", cursor.getString(4).orEmpty()); put("calendarId", cursor.getLong(5))
                    })
                }
            }
        }
        buildJsonObject { put("ok", true); put("count", events.size); put("events", events) }
    }.getOrElse(::failure)

    fun createCalendarEvent(ctx: Context, title: String, startEpochSec: Long, endEpochSec: Long, location: String?, description: String?, calendarId: Long?): JsonObject = runCatching {
        require(ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Permissão para editar agenda pendente" }
        require(endEpochSec > startEpochSec) { "O término precisa ser posterior ao início" }
        val selectedCalendar = calendarId ?: firstWritableCalendar(ctx) ?: error("Nenhuma agenda gravável encontrada")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, selectedCalendar); put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startEpochSec * 1000); put(CalendarContract.Events.DTEND, endEpochSec * 1000)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.takeIf(String::isNotBlank)?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.takeIf(String::isNotBlank)?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Agenda recusou o evento")
        buildJsonObject { put("ok", true); put("eventUri", uri.toString()); put("eventId", uri.lastPathSegment.orEmpty()); put("calendarId", selectedCalendar) }
    }.getOrElse(::failure)

    fun setAlarm(ctx: Context, hour: Int, minute: Int, message: String, skipUi: Boolean): JsonObject = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message); putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        buildJsonObject { put("ok", true); put("hour", hour); put("minute", minute); put("skipUi", skipUi) }
    }.getOrElse(::failure)

    private fun firstWritableCalendar(ctx: Context): Long? {
        return ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun failure(error: Throwable) = buildJsonObject { put("ok", false); put("error", error.message ?: "Falha ao acessar dados pessoais") }
}

internal object IntentTools {
    fun openMap(ctx: Context, query: String, navigation: Boolean): JsonObject = runCatching {
        val uri = if (navigation) Uri.parse("google.navigation:q=${Uri.encode(query)}") else Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.startActivity(intent)
        buildJsonObject { put("ok", true); put("query", query); put("navigation", navigation) }
    }.getOrElse(::failure)

    fun shareText(ctx: Context, text: String, title: String?): JsonObject = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        ctx.startActivity(Intent.createChooser(send, title ?: "Compartilhar com").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        buildJsonObject { put("ok", true); put("chooserOpened", true) }
    }.getOrElse(::failure)

    private fun failure(error: Throwable) = buildJsonObject { put("ok", false); put("error", error.message ?: "Não foi possível abrir a ação") }
}

internal object PrivilegedTools {
    suspend fun state(): JsonObject {
        val values = linkedMapOf(
            "airplaneMode" to "settings get global airplane_mode_on",
            "brightness" to "settings get system screen_brightness",
            "autoRotation" to "settings get system accelerometer_rotation",
            "rotation" to "settings get system user_rotation",
            "batterySaver" to "settings get global low_power",
            "wifi" to "settings get global wifi_on",
            "mobileData" to "settings get global mobile_data",
            "bluetooth" to "settings get global bluetooth_on",
            "dnd" to "settings get global zen_mode"
        )
        val results = values.mapValues { (_, command) -> AxShell.exec(command, requirePrivilege = true) }
        val success = results.values.all { it.isSuccess }
        return buildJsonObject {
            put("ok", success); put("source", results.values.firstOrNull()?.source ?: "unavailable")
            results.forEach { (key, result) ->
                put(key, result.stdout.trim().take(2_000))
            }
            if (!success) {
                put("error", results.values.joinToString("\n") { it.stderr }.ifBlank { "Não foi possível ler todos os estados do sistema" }.take(2_000))
            }
        }
    }

    suspend fun setBluetooth(enabled: Boolean) = command(
        "cmd bluetooth_manager ${if (enabled) "enable" else "disable"}",
        "settings get global bluetooth_on", "bluetooth", if (enabled) "1" else "0"
    )

    suspend fun setWifi(enabled: Boolean) = command(
        "svc wifi ${if (enabled) "enable" else "disable"}",
        "settings get global wifi_on", "wifi", if (enabled) "1" else "0"
    )

    suspend fun setMobileData(enabled: Boolean) = command(
        "svc data ${if (enabled) "enable" else "disable"}",
        "settings get global mobile_data", "mobileData", if (enabled) "1" else "0"
    )

    suspend fun setAirplane(enabled: Boolean) = command(
        "settings put global airplane_mode_on ${if (enabled) 1 else 0} && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled",
        "settings get global airplane_mode_on", "airplaneMode", if (enabled) "1" else "0"
    )

    suspend fun setBrightness(level: Int) = command(
        "settings put system screen_brightness_mode 0 && settings put system screen_brightness ${level.coerceIn(0, 255)}",
        "settings get system screen_brightness", "brightness", level.coerceIn(0, 255).toString()
    )

    suspend fun setRotation(auto: Boolean, rotation: Int) = command(
        if (auto) "settings put system accelerometer_rotation 1" else "settings put system accelerometer_rotation 0 && settings put system user_rotation ${rotation.coerceIn(0, 3)}",
        "settings get system accelerometer_rotation", "autoRotation", if (auto) "1" else "0"
    )

    suspend fun setDnd(mode: String) = command(
        "cmd notification set_dnd ${AxShell.quote(mode)}", "settings get global zen_mode", "dnd",
        when (mode) { "all" -> "0"; "priority" -> "1"; "none" -> "2"; "alarms" -> "3"; else -> "" }
    )
    suspend fun setBatterySaver(enabled: Boolean) = command(
        "cmd power set-mode ${if (enabled) 1 else 0}", "settings get global low_power", "batterySaver", if (enabled) "1" else "0"
    )

    suspend fun appInfo(pkg: String): JsonObject {
        val result = AxShell.exec("dumpsys package ${AxShell.quote(pkg)}", requirePrivilege = true)
        val found = result.isSuccess && result.stdout.isNotBlank() && !result.stdout.contains("Unable to find package", ignoreCase = true)
        return buildJsonObject {
            put("ok", found); put("package", pkg); put("details", result.stdout.take(16_000)); put("source", result.source)
            if (!found) put("error", result.stderr.ifBlank { "Pacote não encontrado" })
        }
    }

    suspend fun uninstall(pkg: String, keepData: Boolean, userOnly: Boolean): JsonObject {
        val command = buildString {
            append("pm uninstall "); if (keepData) append("-k "); if (userOnly) append("--user 0 "); append(AxShell.quote(pkg))
        }
        return shellAction(command, pkg)
    }

    suspend fun clearCache(pkg: String) = shellAction("pm clear --cache-only ${AxShell.quote(pkg)}", pkg)
    suspend fun clearData(pkg: String) = shellAction("pm clear ${AxShell.quote(pkg)}", pkg)
    suspend fun permission(pkg: String, permission: String, grant: Boolean) = shellAction(
        "pm ${if (grant) "grant" else "revoke"} ${AxShell.quote(pkg)} ${AxShell.quote(permission)}", pkg,
        buildJsonObject { put("permission", permission); put("granted", grant) }
    )

    suspend fun wake() = shellAction("input keyevent 224", "device")
    suspend fun lock() = shellAction("input keyevent 223", "device")

    private suspend fun command(command: String, verifyCommand: String, key: String, expected: String): JsonObject {
        val result = AxShell.exec(command, requirePrivilege = true)
        if (result.isSuccess) delay(400)
        val verification = if (result.isSuccess) AxShell.exec(verifyCommand, requirePrivilege = true) else result
        val actual = verification.stdout.trim().lineSequence().lastOrNull().orEmpty()
        val verified = result.isSuccess && verification.isSuccess && actual == expected
        return buildJsonObject {
            put("ok", verified); put("requested", expected); put("actual", actual)
            put("verified", verified); put(key, actual); put("source", result.source)
            if (!verified) put("error", (result.stderr + verification.stderr).ifBlank { "O comando foi enviado, mas o estado final não foi confirmado" }.take(2_000))
        }
    }

    private suspend fun shellAction(command: String, target: String, extra: JsonObject = buildJsonObject {}): JsonObject {
        val result = AxShell.exec(command, requirePrivilege = true)
        val semanticFailure = result.stdout.lineSequence().any { it.trimStart().startsWith("Failure") || it.trimStart().startsWith("Error") }
        val success = result.isSuccess && !semanticFailure
        return buildJsonObject {
            put("ok", success); put("target", target); put("source", result.source); put("stdout", result.stdout.take(4_000))
            extra.forEach { (key, value) -> put(key, value) }
            if (!success) put("error", result.stderr.ifBlank { result.stdout.ifBlank { "Ação privilegiada falhou" } }.take(2_000))
        }
    }
}
