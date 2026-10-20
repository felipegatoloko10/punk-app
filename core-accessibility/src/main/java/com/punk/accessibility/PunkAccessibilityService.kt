package com.punk.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class PunkAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PunkAccessibilityService? = null
            private set
        fun isEnabled(): Boolean = instance != null
        fun hasPendingGestures(): Boolean = instance?.activeGestures?.isNotEmpty() == true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private val activeGestures = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val gestureSequence = java.util.concurrent.atomic.AtomicLong()
    private var lastVolumeDownTime = 0L

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownTime < 450) {
                lastVolumeDownTime = 0L
                val intent = android.content.Intent(this, Class.forName("com.punk.app.VoiceActivity")).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                return true
            }
            lastVolumeDownTime = now
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun snapshotCompact(maxNodes: Int = 120): String {
        val root = rootInActiveWindow ?: return "{\"error\":\"sem janela ativa\"}"
        val sb = StringBuilder()
        var count = 0
        fun dfs(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            val text = if (n.isPassword) "[protegido]" else n.text?.toString()?.take(80) ?: ""
            val desc = if (n.isPassword) "" else n.contentDescription?.toString()?.take(80) ?: ""
            val id = n.viewIdResourceName?.substringAfterLast("/") ?: ""
            val cls = n.className?.toString()?.substringAfterLast(".") ?: ""
            val clickable = n.isClickable
            if (text.isNotBlank() || desc.isNotBlank() || clickable || id.isNotBlank()) {
                sb.append("  ".repeat(depth.coerceAtMost(8)))
                sb.append(cls.ifBlank { "Node" })
                if (id.isNotBlank()) sb.append(" id=$id")
                if (text.isNotBlank()) sb.append(" text=\"$text\"")
                if (desc.isNotBlank()) sb.append(" desc=\"$desc\"")
                if (clickable) sb.append(" [clickable]")
                val b = Rect(); n.getBoundsInScreen(b)
                sb.append(" bounds=[${b.left},${b.top},${b.right},${b.bottom}]")
                sb.appendLine()
                count++
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { dfs(it, depth+1) }
            }
        }
        dfs(root, 0)
        return sb.toString().ifBlank { "(tela vazia ou sem nós úteis)" }.take(12000)
    }

    suspend fun performTap(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        if (x !in 0 until metrics.widthPixels || y !in 0 until metrics.heightPixels) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return awaitGesture(gesture, 2000L)
    }

    suspend fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        val metrics = resources.displayMetrics
        if (listOf(x1,x2).any { it !in 0 until metrics.widthPixels } || listOf(y1,y2).any { it !in 0 until metrics.heightPixels }) return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 2000L)))
            .build()
        return awaitGesture(gesture, durationMs.coerceIn(50L, 2000L) + 2000L)
    }

    private suspend fun awaitGesture(gesture: GestureDescription, timeoutMs: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val gestureId=gestureSequence.incrementAndGet()
                    activeGestures.add(gestureId)
                    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            activeGestures.remove(gestureId)
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(g: GestureDescription?) {
                            activeGestures.remove(gestureId)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }, Handler(Looper.getMainLooper()))
                    if (!accepted) { activeGestures.remove(gestureId); if(continuation.isActive)continuation.resume(false) }
                }
            } ?: false
        }

    fun inputText(text: String, clearFirst: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false

        val args = Bundle()
        val finalTxt = if (clearFirst) text else ((focused.text?.toString() ?: "") + text)
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalTxt)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickNode(query: String, byId: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = if (byId) {
            root.findAccessibilityNodeInfosByViewId(query)
        } else {
            root.findAccessibilityNodeInfosByText(query)
        }
        for (n in nodes) {
            var curr: AccessibilityNodeInfo? = n
            while (curr != null) {
                if (curr.isClickable) {
                    val clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                curr = curr.parent
            }
        }
        return false
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.lowercase() == "up" || direction.lowercase() == "backward") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val s = findScrollable(child)
                if (s != null) return s
            }
            return null
        }
        val target = findScrollable(root) ?: root
        return target.performAction(action)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun triggerGlobalAction(name: String): Boolean {
        val actionId = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else -1
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        return if (actionId != -1) performGlobalAction(actionId) else false
    }
}
