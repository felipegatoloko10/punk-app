package com.punk.tools

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.punk.bridge.BridgeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque

data class NotificationEntry(val id: Int, val packageName: String, val title: String, val text: String,
    val bigText: String = "", val category: String = "", val postTime: Long)

class PunkNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: PunkNotificationListener? = null
            private set
        val recentNotifications = ConcurrentLinkedDeque<NotificationEntry>()
        fun isEnabled(): Boolean = instance != null
        fun getRecentSnapshot(limit: Int = 20): List<NotificationEntry> = recentNotifications.take(limit.coerceIn(1, 100))
    }
    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { instance = null; recentNotifications.clear(); super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val pkg = sbn.packageName
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // Never retain the raw OTP/financial payload, even when forwarding was enabled.
        val entry = NotificationEntry(sbn.id, pkg, NotificationPrivacy.protect(title, pkg),
            NotificationPrivacy.protect(text, pkg), category = notification.category.orEmpty(), postTime = sbn.postTime)
        recentNotifications.addFirst(entry)
        while (recentNotifications.size > 80) recentNotifications.pollLast()
        if (NotificationPrivacy.isForwardingEnabled(this)) {
            BridgeService.sendMessage("notification", buildJsonObject {
                put("packageName", entry.packageName); put("id", entry.id)
                put("title", entry.title); put("text", entry.text)
                put("category", entry.category); put("postTime", entry.postTime)
            })
        }
    }
}
