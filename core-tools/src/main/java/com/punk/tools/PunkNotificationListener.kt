package com.punk.tools

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.punk.bridge.BridgeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque

data class NotificationEntry(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String = "",
    val category: String = "",
    val postTime: Long
)

class PunkNotificationListener : NotificationListenerService() {
    companion object {
        var instance: PunkNotificationListener? = null
            private set
        val recentNotifications = ConcurrentLinkedDeque<NotificationEntry>()
        private const val MAX_RECENT = 80

        fun isEnabled(): Boolean = instance != null

        fun getRecentSnapshot(limit: Int = 20): List<NotificationEntry> =
            recentNotifications.take(limit)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Push a system event to bridge if connected
        tryPushBridge("listener_connected", null)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val notif: Notification = sbn.notification ?: return
        val extras: Bundle = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val category = notif.category ?: ""

        // Fallback: try ticker or subText
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val displayText = when {
            text.isNotBlank() -> text
            bigText.isNotBlank() -> bigText
            subText.isNotBlank() -> subText
            notif.tickerText?.isNotBlank() == true -> notif.tickerText.toString()
            else -> ""
        }

        if (title.isBlank() && displayText.isBlank()) return

        val pkg = sbn.packageName ?: ""
        val entry = NotificationEntry(
            id = sbn.id,
            packageName = pkg,
            title = title.take(200),
            text = displayText.take(500),
            bigText = bigText.take(500),
            category = category,
            postTime = sbn.postTime
        )
        recentNotifications.addFirst(entry)
        while (recentNotifications.size > MAX_RECENT) {
            recentNotifications.pollLast()
        }

        // Forward to Hermes bridge when connected (WhatsApp, banks, etc. all forwarded)
        val isInteresting = pkg.contains("whatsapp", true) ||
            pkg.contains("telegram", true) ||
            pkg.contains("bank", true) ||
            pkg.contains("nubank", true) ||
            pkg.contains("itau", true) ||
            pkg.contains("bradesco", true) ||
            pkg.contains("caixa", true) ||
            pkg.contains("inter", true) ||
            pkg.contains("picpay", true) ||
            pkg.contains("gmail", true) ||
            pkg.contains("outlook", true) ||
            pkg.contains("instagram", true) ||
            true // forward everything — Hermes filters server-side

        if (isInteresting) {
            tryPushBridge(
                pkg = pkg,
                title = title,
                text = displayText,
                bigText = bigText,
                entry = entry
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // no-op, but keep instance alive
    }

    private fun tryPushBridge(pkg: String, title: String, text: String, bigText: String, entry: NotificationEntry) {
        try {
            val payload = buildJsonObject {
                put("packageName", pkg)
                put("id", entry.id)
                put("title", title)
                put("text", text)
                put("bigText", bigText)
                put("category", entry.category)
                put("postTime", entry.postTime)
                put("app", pkg.substringAfterLast("."))
            }
            BridgeService.sendMessage("notification", payload)
        } catch (_: Throwable) {}
    }

    private fun tryPushBridge(type: String, payload: kotlinx.serialization.json.JsonElement?) {
        try { BridgeService.sendMessage(type, payload) } catch (_: Throwable) {}
    }
}
