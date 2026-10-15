package com.punk.axmanager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.Serializable

@Serializable
data class DeviceStatus(
    val batteryPct: Int,
    val batteryTempC: Double?,
    val isCharging: Boolean,
    val ramInfo: String,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val axDaemon: String,
    val foregroundApp: String? = null
)

object DeviceStatusProvider {
    suspend fun collect(ctx: Context): DeviceStatus {
        val bmIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bmIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bmIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >=0 && scale>0) (level*100/scale) else -1
        val tempRaw = bmIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (tempRaw>0) tempRaw/10.0 else null
        val status = bmIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val free = stat.availableBytes / (1024*1024)
        val total = stat.totalBytes / (1024*1024)
        val ramRaw = try { AxShell.dumpsys("meminfo").lineSequence().firstOrNull { it.contains("Total RAM", true) } ?: "" } catch(_:Exception){""}
        val ax = when(val s = AxShell.daemonState()){ is AxShell.DaemonState.Online -> "online"; is AxShell.DaemonState.Offline -> "offline:${s.reason}" }

        // foreground app via dumpsys activity (best-effort)
        val fg = try { AxShell.dumpsys("activity","activities").lineSequence().firstOrNull{ it.contains("mCurrentFocus") || it.contains("mFocusedApp") }?.trim() } catch(_:Exception){ null }

        return DeviceStatus(pct, tempC, charging, ramRaw.trim(), free, total, ax, fg)
    }
}
