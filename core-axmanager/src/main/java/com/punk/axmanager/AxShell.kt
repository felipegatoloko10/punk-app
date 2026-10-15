package com.punk.axmanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val source: String = "local_sh"
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object AxShell {

    private const val FALLBACK_BIN = "/data/local/tmp/axmanager"

    sealed interface DaemonState {
        data object Online: DaemonState
        data class Offline(val reason: String): DaemonState
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun shizukuStatus(): Map<String, Any> {
        val ping = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val granted = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val version = try { if (ping) Shizuku.getVersion() else -1 } catch (_: Throwable) { -1 }
        val uid = try { if (ping) Shizuku.getUid() else -1 } catch (_: Throwable) { -1 }
        return mapOf(
            "ping" to ping,
            "granted" to granted,
            "version" to version,
            "uid" to uid
        )
    }

    fun daemonState(): DaemonState {
        return if (isShizukuAvailable()) {
            DaemonState.Online
        } else {
            val s = shizukuStatus()
            val ping = s["ping"] as? Boolean ?: false
            val granted = s["granted"] as? Boolean ?: false
            if (!ping) DaemonState.Offline("shizuku_not_running")
            else if (!granted) DaemonState.Offline("shizuku_permission_not_granted")
            else DaemonState.Offline("local_sh_only")
        }
    }

    private var newProcessMethod: Method? = null

    private fun execShizuku(cmd: String): ShizukuRemoteProcess {
        if (newProcessMethod == null) {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            newProcessMethod = m
        }
        val args = arrayOf("sh", "-c", cmd)
        return newProcessMethod!!.invoke(null, args, null, null) as ShizukuRemoteProcess
    }

    private fun runBlockingExec(cmd: String, timeoutMs: Long): ShellResult {
        // 1. Tenta executar via Shizuku (privilégio ADB UID 2000)
        if (isShizukuAvailable()) {
            try {
                val proc = execShizuku(cmd)
                val out = StringBuilder(); val err = StringBuilder()
                val tOut = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { out.appendLine(it) }
                    } catch (_: Throwable) {}
                }
                val tErr = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { err.appendLine(it) }
                    } catch (_: Throwable) {}
                }
                tOut.start(); tErr.start()
                val finished = proc.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroy()
                    return ShellResult("", "timeout Shizuku ${timeoutMs}ms", -1, "shizuku_adb")
                }
                tOut.join(500); tErr.join(500)
                return ShellResult(out.toString(), err.toString(), proc.exitValue(), "shizuku_adb")
            } catch (e: Throwable) {
                // se falhar por algum motivo, registra no erro e cai no fallback
            }
        }

        // 2. Tenta via binário axmanager ou shell local
        val ax = java.io.File(FALLBACK_BIN)
        val fullCmd = if (ax.exists() && ax.canExecute()) {
            arrayOf(ax.absolutePath, "exec", cmd)
        } else {
            arrayOf("sh", "-c", cmd)
        }
        val pb = ProcessBuilder(*fullCmd)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = StringBuilder(); val err = StringBuilder()
        val tOut = Thread { BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { out.appendLine(it) } }
        val tErr = Thread { BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { err.appendLine(it) } }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ShellResult("", "timeout local ${timeoutMs}ms", -1, "local_sh")
        }
        tOut.join(500); tErr.join(500)
        return ShellResult(out.toString(), err.toString(), proc.exitValue(), "local_sh")
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 15000): ShellResult = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs + 2000) { runBlockingExec(cmd, timeoutMs) }
    }

    suspend fun getProp(key: String): String = exec("getprop $key").stdout.trim()
    suspend fun dumpsys(service: String, extra: String = ""): String = exec("dumpsys $service $extra").stdout
    suspend fun screencap(path: String = "/sdcard/punk_screencap.png"): ShellResult = exec("screencap -p $path && echo OK:$path")
    suspend fun inputTap(x: Int, y: Int): ShellResult = exec("input tap $x $y")
    suspend fun inputSwipe(x1:Int,y1:Int,x2:Int,y2:Int,dur:Int=300): ShellResult = exec("input swipe $x1 $y1 $x2 $y2 $dur")
    suspend fun inputText(text: String): ShellResult = exec("input text '${text.replace("'", "'\\''")}'")
    suspend fun keyEvent(code: Int): ShellResult = exec("input keyevent $code")
    suspend fun listPackages(): String = exec("pm list packages").stdout
    suspend fun forceStop(pkg: String): ShellResult = exec("am force-stop $pkg")
}
