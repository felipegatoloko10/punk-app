package com.punk.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

data class NotificationEntry(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long
)

class PunkNotificationListener : NotificationListenerService() {
    companion object {
        var instance: PunkNotificationListener? = null
            private set
        val recentNotifications = ConcurrentLinkedDeque<NotificationEntry>()
        private const val MAX_RECENT = 50
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val entry = NotificationEntry(
            id = sbn.id,
            packageName = sbn.packageName ?: "",
            title = title,
            text = text,
            postTime = sbn.postTime
        )
        recentNotifications.addFirst(entry)
        while (recentNotifications.size > MAX_RECENT) {
            recentNotifications.pollLast()
        }
    }
}
