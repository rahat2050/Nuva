package com.nuva.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nuva.assistant.command.ScreenPoint
import com.nuva.assistant.command.SwipeDirection
import com.nuva.assistant.command.UiSelector
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * NUVA's "hands" — the AccessibilityService (blueprint §2.6).
 *
 * Rules baked into this class:
 *  * The service only runs what the CommandExecutor hands it — always one of
 *    the 15 registered, locally re-validated actions.
 *  * The user enables it manually in system settings; NUVA can never enable it.
 *  * Node search prefers semantic selectors over coordinates (§9).
 */
class NuvaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // --- Node operations -------------------------------------------------------

    fun findNode(selector: UiSelector): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return NodeFinder.find(root, selector)
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_CLIMB) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth += 1
        }
        return false
    }

    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_CLIMB) {
            if (current.isLongClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth += 1
        }
        return clickNode(node)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_CLIMB) {
            if (current.isEditable) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
                return current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            current = current.parent
            depth += 1
        }
        // Fallback: focus, then let the IME-less clipboard path handle it.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return false
    }

    fun scrollNode(node: AccessibilityNodeInfo, direction: SwipeDirection): Boolean {
        val action = when (direction) {
            SwipeDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SwipeDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_CLIMB) {
            if (current.performAction(action)) return true
            current = current.parent
            depth += 1
        }
        return false
    }

    // --- Global actions --------------------------------------------------------

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** The currently focused editable node (search field already open), if any. */
    fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return NodeFinder.findFirst(root) { node -> node.isEditable && node.isFocused }
    }

    /** Any scrollable container on screen (list/grid), for SCROLL without a target. */
    fun findScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return NodeFinder.findFirst(root) { node -> node.isScrollable }
    }

    // --- Gestures --------------------------------------------------------------

    /**
     * Dispatches a tap at a 0..1 screen fraction. Waits up to [timeoutMs] for
     * the gesture to complete. API 26+ compatible.
     */
    suspend fun tapAt(point: ScreenPoint, longClick: Boolean = false, timeoutMs: Long = GESTURE_TIMEOUT_MS): Boolean {
        val screen = resources.displayMetrics
        val x = (point.x * screen.widthPixels).coerceIn(0f, screen.widthPixels.toFloat())
        val y = (point.y * screen.heightPixels).coerceIn(0f, screen.heightPixels.toFloat())
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (longClick) 700L else 60L)
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build(), timeoutMs)
    }

    /** Swipes between two 0..1 screen fractions (or by direction across the middle). */
    suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long = 250,
        timeoutMs: Long = GESTURE_TIMEOUT_MS,
    ): Boolean {
        val screen = resources.displayMetrics
        val path = Path().apply {
            moveTo(from.x * screen.widthPixels, from.y * screen.heightPixels)
            lineTo(to.x * screen.widthPixels, to.y * screen.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build(), timeoutMs)
    }

    private suspend fun dispatchGestureSuspend(description: GestureDescription, timeoutMs: Long): Boolean =
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val dispatched = dispatchGesture(
                        description,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                        null,
                    )
                    if (!dispatched && continuation.isActive) continuation.resume(false)
                }
            }
        } catch (err: Exception) {
            false
        }

    // --- Screen reading --------------------------------------------------------

    fun readVisibleScreen(maxChars: Int = 4000): String? {
        val root = rootInActiveWindow ?: return null
        val builder = StringBuilder()
        collectText(root, builder, maxChars)
        val text = builder.toString().trim()
        return text.ifEmpty { null }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, maxChars: Int) {
        if (out.length >= maxChars) return
        node.text?.let { out.appendLine(it) }
        node.contentDescription?.let { out.appendLine(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out, maxChars) }
        }
    }

    /** Best-effort screen bounds of the active window, for diagnostics. */
    @Suppress("unused")
    fun activeWindowBounds(): Rect? = rootInActiveWindow?.let { root ->
        Rect().also(root::getBoundsInScreen)
    }

    companion object {
        @Volatile
        var instance: NuvaAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        const val MAX_PARENT_CLIMB = 6
        const val GESTURE_TIMEOUT_MS = 3_000L
        const val NODE_SEARCH_TIMEOUT_MS = 4_000L

        /** Cooperative retry with small pauses — UIs take a moment to settle. */
        const val RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 350L
    }
}
