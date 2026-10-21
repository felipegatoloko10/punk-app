package com.punk.tools

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.punk.bridge.BridgeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque

data class NotificationEntry(
    val key: String,
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String = "",
    val category: String = "",
    val postTime: Long,
    val canReply: Boolean = false,
    val canDismiss: Boolean = false
)

class PunkNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: PunkNotificationListener? = null
            private set
        val recentNotifications = ConcurrentLinkedDeque<NotificationEntry>()
        fun isEnabled(): Boolean = instance != null
        fun getRecentSnapshot(limit: Int = 20): List<NotificationEntry> = recentNotifications.take(limit.coerceIn(1, 100))
    }

    private fun active(key: String): StatusBarNotification? =
        activeNotifications?.firstOrNull { it.key == key }

    fun reply(key: String, message: String): Result<Unit> = runCatching {
        require(message.isNotBlank()) { "A resposta não pode ficar vazia" }
        val sbn = active(key) ?: error("Notificação não está mais ativa")
        val action = sbn.notification.actions.orEmpty().firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: error("Notificação não permite resposta direta")
        val inputs = action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        val fillInIntent = Intent()
        RemoteInput.addResultsToIntent(inputs, fillInIntent, results)
        action.actionIntent.send(this, 0, fillInIntent)
    }

    fun dismiss(key: String): Result<Unit> = runCatching {
        val sbn = active(key) ?: error("Notificação não está mais ativa")
        require(sbn.isClearable) { "Notificação não pode ser dispensada" }
        cancelNotification(key)
    }

    fun open(key: String): Result<Unit> = runCatching {
        val sbn = active(key) ?: error("Notificação não está mais ativa")
        val intent = sbn.notification.contentIntent ?: error("Notificação não possui ação para abrir")
        intent.send()
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
        val entry = NotificationEntry(
            key = sbn.key,
            id = sbn.id,
            packageName = pkg,
            title = NotificationPrivacy.protect(title, pkg),
            text = NotificationPrivacy.protect(text, pkg),
            category = notification.category.orEmpty(),
            postTime = sbn.postTime,
            canReply = notification.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() },
            canDismiss = sbn.isClearable
        )
        recentNotifications.addFirst(entry)
        while (recentNotifications.size > 80) recentNotifications.pollLast()
        if (NotificationPrivacy.isForwardingEnabled(this)) {
            BridgeService.sendMessage("notification", buildJsonObject {
                put("packageName", entry.packageName); put("id", entry.id); put("key", entry.key)
                put("title", entry.title); put("text", entry.text)
                put("category", entry.category); put("postTime", entry.postTime)
                put("canReply", entry.canReply); put("canDismiss", entry.canDismiss)
            })
        }
    }
}
