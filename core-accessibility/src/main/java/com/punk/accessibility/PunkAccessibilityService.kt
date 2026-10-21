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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class PunkAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PunkAccessibilityService? = null
            private set
        @Volatile var eventConsumer: ((JsonObject) -> Unit)? = null
        fun isEnabled(): Boolean = instance != null
        fun hasPendingGestures(): Boolean = instance?.activeGestures?.isNotEmpty() == true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private val activeGestures = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val gestureSequence = AtomicLong()
    private val eventSequence = AtomicLong()
    private val eventBuffer = ConcurrentLinkedDeque<JsonObject>()
    private var lastPushedAt = 0L
    private var lastPushedFingerprint = ""
    private var droppedSincePush = 0
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val password = runCatching { event.source?.isPassword == true }.getOrDefault(false)
        val item = buildJsonObject {
            put("seq", eventSequence.incrementAndGet())
            put("timestamp", System.currentTimeMillis())
            put("eventTime", event.eventTime)
            put("type", AccessibilityEvent.eventTypeToString(event.eventType))
            put("package", event.packageName?.toString().orEmpty())
            put("class", event.className?.toString().orEmpty())
            put("windowId", event.windowId)
            put("contentChangeTypes", event.contentChangeTypes)
            put("text", if (password) "[protegido]" else event.text.joinToString(" ").take(500))
            put("description", if (password) "" else event.contentDescription?.toString().orEmpty().take(500))
        }
        eventBuffer.addFirst(item)
        while (eventBuffer.size > 200) eventBuffer.pollLast()
        val now = System.currentTimeMillis()
        val important = event.eventType in setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        )
        val fingerprint = "${event.eventType}|${event.packageName}|${event.className}|${event.windowId}|${item["text"]}"
        val duplicate = fingerprint == lastPushedFingerprint && now - lastPushedAt < 750
        val eligible = event.packageName?.toString() != packageName && !duplicate && (important || now - lastPushedAt >= 300)
        if (eligible) {
            val outgoing = buildJsonObject {
                item.forEach { (key, value) -> put(key, value) }
                put("droppedSinceLast", droppedSincePush)
            }
            droppedSincePush = 0
            lastPushedAt = now
            lastPushedFingerprint = fingerprint
            eventConsumer?.invoke(outgoing)
        } else {
            droppedSincePush++
        }
    }

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

    fun snapshotJson(maxNodes: Int = 220, maxDepth: Int = 18): JsonObject {
        val root = rootInActiveWindow ?: return buildJsonObject { put("ok", false); put("error", "sem janela ativa") }
        val nodes = mutableListOf<JsonObject>()
        var visited = 0
        var truncated = false
        fun dfs(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            if (visited >= maxNodes.coerceIn(1, 500)) { truncated = true; return }
            if (depth > maxDepth.coerceIn(1, 30)) { truncated = true; return }
            val index = visited++
            val bounds = Rect().also(node::getBoundsInScreen)
            val password = node.isPassword
            nodes += buildJsonObject {
                put("index", index)
                if (parent != null) put("parent", parent)
                put("depth", depth)
                put("package", node.packageName?.toString().orEmpty())
                put("class", node.className?.toString().orEmpty())
                put("viewId", node.viewIdResourceName.orEmpty())
                put("text", if (password) "[protegido]" else node.text?.toString().orEmpty().take(500))
                put("description", if (password) "" else node.contentDescription?.toString().orEmpty().take(500))
                put("bounds", buildJsonObject {
                    put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
                })
                put("clickable", node.isClickable); put("longClickable", node.isLongClickable)
                put("editable", node.isEditable); put("scrollable", node.isScrollable)
                put("enabled", node.isEnabled); put("focusable", node.isFocusable); put("focused", node.isFocused)
                put("selected", node.isSelected); put("checkable", node.isCheckable); put("checked", node.isChecked)
                put("password", password)
                put("actions", buildJsonArray {
                    node.actionList.take(24).forEach { action ->
                        add(buildJsonObject { put("id", action.id); put("label", action.label?.toString().orEmpty()) })
                    }
                })
            }
            for (childIndex in 0 until node.childCount) {
                if (visited >= maxNodes) { truncated = true; break }
                node.getChild(childIndex)?.let { dfs(it, index, depth + 1) }
            }
        }
        dfs(root, null, 0)
        return buildJsonObject {
            put("ok", true)
            put("timestamp", System.currentTimeMillis())
            put("package", root.packageName?.toString().orEmpty())
            put("windowId", root.windowId)
            put("nodeCount", nodes.size)
            put("truncated", truncated)
            put("nodes", JsonArray(nodes))
        }
    }

    fun recentEvents(sinceSeq: Long = 0L, limit: Int = 50): JsonObject {
        val events = eventBuffer.asSequence()
            .filter { (it["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { seq -> seq > sinceSeq } == true }
            .take(limit.coerceIn(1, 200))
            .toList()
        return buildJsonObject {
            put("ok", true)
            put("latestSeq", eventSequence.get())
            put("count", events.size)
            put("events", JsonArray(events))
        }
    }

    private data class NodeCandidate(val node: AccessibilityNodeInfo, val score: Int, val matchedBy: String)

    private fun candidates(query: String, byId: Boolean?, exact: Boolean): List<NodeCandidate> {
        val root = rootInActiveWindow ?: return emptyList()
        val wanted = query.trim().lowercase()
        if (wanted.isBlank()) return emptyList()
        val found = mutableListOf<NodeCandidate>()
        fun score(node: AccessibilityNodeInfo): NodeCandidate? {
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val shortId = id.substringAfterLast('/')
            val text = if (node.isPassword) "" else node.text?.toString().orEmpty().trim().lowercase()
            val desc = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().trim().lowercase()
            val idMatch = id == wanted || shortId == wanted || (!exact && (id.contains(wanted) || shortId.contains(wanted)))
            val textMatch = text == wanted || (!exact && text.contains(wanted))
            val descMatch = desc == wanted || (!exact && desc.contains(wanted))
            if (
                (byId == true && !idMatch) ||
                (byId == false && !textMatch && !descMatch) ||
                (byId == null && !idMatch && !textMatch && !descMatch)
            ) return null
            val matchedBy = when { idMatch -> "id"; textMatch -> "text"; else -> "description" }
            val matchScore = when {
                id == wanted || shortId == wanted -> 140
                text == wanted -> 130
                desc == wanted -> 120
                idMatch -> 100
                textMatch -> 90
                else -> 80
            } + (if (node.isClickable) 20 else 0) + (if (node.isEnabled) 5 else 0)
            return NodeCandidate(node, matchScore, matchedBy)
        }
        fun walk(node: AccessibilityNodeInfo) {
            score(node)?.let(found::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return found.sortedByDescending(NodeCandidate::score)
    }

    fun findNodes(query: String, byId: Boolean? = null, exact: Boolean = false, limit: Int = 10): JsonObject {
        val matches = candidates(query, byId, exact).take(limit.coerceIn(1, 50))
        return buildJsonObject {
            put("ok", true); put("query", query); put("count", matches.size)
            put("matches", buildJsonArray {
                matches.forEach { candidate ->
                    val node = candidate.node
                    val bounds = Rect().also(node::getBoundsInScreen)
                    add(buildJsonObject {
                        put("matchedBy", candidate.matchedBy); put("score", candidate.score)
                        put("text", if (node.isPassword) "[protegido]" else node.text?.toString().orEmpty().take(300))
                        put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().take(300))
                        put("viewId", node.viewIdResourceName.orEmpty()); put("class", node.className?.toString().orEmpty())
                        put("clickable", node.isClickable); put("enabled", node.isEnabled)
                        put("bounds", buildJsonObject { put("left",bounds.left); put("top",bounds.top); put("right",bounds.right); put("bottom",bounds.bottom) })
                    })
                }
            })
        }
    }

    suspend fun findAndClick(query: String, byId: Boolean? = null, exact: Boolean = false,
                             retries: Int = 2, scrolls: Int = 2, direction: String = "down"): JsonObject {
        var attempts = 0
        var performedScrolls = 0
        repeat(retries.coerceIn(0, 5) + 1) {
            attempts++
            val result = withContext(Dispatchers.Main.immediate) {
                val candidate = candidates(query, byId, exact).firstOrNull()
                if (candidate == null) null else {
                    var target: AccessibilityNodeInfo? = candidate.node
                    while (target != null && !target.isClickable) target = target.parent
                    val clicked = target?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    Triple(clicked, candidate.matchedBy, candidate.score)
                }
            }
            if (result?.first == true) return buildJsonObject {
                put("ok", true); put("query", query); put("attempts", attempts); put("scrolls", performedScrolls)
                put("matchedBy", result.second); put("score", result.third)
            }
            if (performedScrolls < scrolls.coerceIn(0, 8)) {
                val moved = withContext(Dispatchers.Main.immediate) { scroll(direction) }
                if (moved) performedScrolls++
            }
            delay(350)
        }
        return buildJsonObject {
            put("ok", false); put("query", query); put("attempts", attempts); put("scrolls", performedScrolls)
            put("error", "Elemento não encontrado ou não clicável")
        }
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

    suspend fun performLongPress(x: Int, y: Int, durationMs: Long = 800L): Boolean {
        val metrics = resources.displayMetrics
        if (x !in 0 until metrics.widthPixels || y !in 0 until metrics.heightPixels) return false
        val duration = durationMs.coerceIn(400L, 5_000L)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return awaitGesture(gesture, duration + 2_000L)
    }

    suspend fun performPath(points: List<Pair<Int, Int>>, durationMs: Long = 600L): Boolean {
        if (points.size !in 2..32) return false
        val metrics = resources.displayMetrics
        if (points.any { (x, y) -> x !in 0 until metrics.widthPixels || y !in 0 until metrics.heightPixels }) return false
        val duration = durationMs.coerceIn(100L, 5_000L)
        val path = Path().apply {
            moveTo(points.first().first.toFloat(), points.first().second.toFloat())
            points.drop(1).forEach { (x, y) -> lineTo(x.toFloat(), y.toFloat()) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return awaitGesture(gesture, duration + 2_000L)
    }

    suspend fun performPinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int, durationMs: Long = 500L): Boolean {
        if (startSpan !in 20..2_000 || endSpan !in 20..2_000) return false
        val metrics = resources.displayMetrics
        val startHalf = startSpan / 2
        val endHalf = endSpan / 2
        val points = listOf(
            centerX - startHalf to centerY,
            centerX + startHalf to centerY,
            centerX - endHalf to centerY,
            centerX + endHalf to centerY
        )
        if (points.any { (x, y) -> x !in 0 until metrics.widthPixels || y !in 0 until metrics.heightPixels }) return false
        val duration = durationMs.coerceIn(100L, 3_000L)
        val left = Path().apply {
            moveTo((centerX - startHalf).toFloat(), centerY.toFloat())
            lineTo((centerX - endHalf).toFloat(), centerY.toFloat())
        }
        val right = Path().apply {
            moveTo((centerX + startHalf).toFloat(), centerY.toFloat())
            lineTo((centerX + endHalf).toFloat(), centerY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(right, 0, duration))
            .build()
        return awaitGesture(gesture, duration + 2_000L)
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
