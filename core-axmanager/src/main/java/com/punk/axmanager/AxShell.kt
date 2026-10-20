package com.punk.axmanager

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int, val source: String = "local_sh") {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Privileged operations never silently fall back to the app sandbox shell. */
object AxShell {
    sealed interface DaemonState {
        data object Online : DaemonState
        data class Offline(val reason: String) : DaemonState
    }
    private val processes = ConcurrentHashMap.newKeySet<Process>()
    private val remoteProcesses = ConcurrentHashMap.newKeySet<ShizukuRemoteProcess>()
    private const val MAX_OUTPUT = 8 * 1024 * 1024

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun shizukuStatus(): Map<String, Any> {
        val ping = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val granted = try { ping && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
        return mapOf("ping" to ping, "granted" to granted,
            "version" to (try { if (ping) Shizuku.getVersion() else -1 } catch (_: Throwable) { -1 }),
            "uid" to (try { if (ping) Shizuku.getUid() else -1 } catch (_: Throwable) { -1 }))
    }

    fun daemonState(): DaemonState = if (isShizukuAvailable()) DaemonState.Online else
        DaemonState.Offline(if (shizukuStatus()["ping"] == true) "shizuku_permission_not_granted" else "shizuku_not_running")

    fun cancelAll() {
        processes.toList().forEach { runCatching { it.destroyForcibly() } }
        remoteProcesses.toList().forEach { runCatching { it.destroy() } }
    }

    private fun readBounded(stream: InputStream, output: StringBuffer) {
        stream.bufferedReader().use { reader ->
            val chunk = CharArray(4096)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                if (output.length < MAX_OUTPUT) output.append(chunk, 0, minOf(count, MAX_OUTPUT - output.length))
            }
        }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 15000, requirePrivilege: Boolean = false): ShellResult = withContext(Dispatchers.IO) {
        require(cmd.isNotBlank()) { "Comando vazio" }
        require(timeoutMs in 100..120000) { "timeoutMs deve estar entre 100 e 120000" }
        coroutineContext.ensureActive()
        val privileged = isShizukuAvailable()
        if (requirePrivilege && !privileged) return@withContext ShellResult("", "Shizuku não está ativo/autorizado. Ação exige shell ADB.", -1, "unavailable")
        val output = StringBuffer()
        val errors = StringBuffer()
        var local: Process? = null
        var remote: ShizukuRemoteProcess? = null
        val source = if (privileged) "shizuku_adb" else "local_sh"
        try {
            val input: InputStream
            val error: InputStream
            if (privileged) {
                // API 13 retains this method for compatibility. Failure is surfaced, never silently demoted.
                val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                remote = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
                remoteProcesses.add(remote)
                input = remote.inputStream
                error = remote.errorStream
            } else {
                local = ProcessBuilder("sh", "-c", cmd).start()
                processes.add(local)
                input = local.inputStream
                error = local.errorStream
            }
            val outThread = Thread { runCatching { readBounded(input, output) } }.apply { isDaemon = true; start() }
            val errThread = Thread { runCatching { readBounded(error, errors) } }.apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var exitCode: Int? = null
            while (exitCode == null) {
                coroutineContext.ensureActive()
                val finished = remote?.waitForTimeout(25, TimeUnit.MILLISECONDS) ?: local!!.waitFor(25, TimeUnit.MILLISECONDS)
                if (finished) exitCode = remote?.exitValue() ?: local!!.exitValue()
                if (exitCode == null && System.nanoTime() >= deadline) return@withContext ShellResult("", "Tempo limite de ${timeoutMs} ms", -1, source)
            }
            outThread.join(500)
            errThread.join(500)
            ShellResult(output.toString(), errors.toString(), exitCode, source)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ShellResult("", e.cause?.message ?: e.message ?: "Falha no shell", -1, source)
        } finally {
            local?.let { runCatching { it.destroyForcibly() }; processes.remove(it) }
            remote?.let { runCatching { it.destroy() }; remoteProcesses.remove(it) }
        }
    }

    suspend fun getProp(key: String): String = exec("getprop ${quote(key)}").stdout.trim()
    suspend fun dumpsys(service: String, extra: String = ""): String = exec("dumpsys ${quote(service)}" + if (extra.isBlank()) "" else " ${quote(extra)}", requirePrivilege = true).stdout
    suspend fun screencap(path: String = "/sdcard/punk_screencap.png"): ShellResult = exec("screencap -p ${quote(path)}", requirePrivilege = true)
    suspend fun inputTap(x: Int, y: Int): ShellResult = exec("input tap $x $y", requirePrivilege = true)
    suspend fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300): ShellResult = exec("input swipe $x1 $y1 $x2 $y2 $dur", requirePrivilege = true)
    suspend fun inputText(text: String): ShellResult = exec("input text ${quote(text.replace(" ", "%s"))}", requirePrivilege = true)
    suspend fun keyEvent(code: Int): ShellResult = exec("input keyevent $code", requirePrivilege = true)
    suspend fun listPackages(): String = exec("pm list packages", requirePrivilege = true).stdout
    suspend fun forceStop(pkg: String): ShellResult = exec("am force-stop ${quote(pkg)}", requirePrivilege = true)
}
