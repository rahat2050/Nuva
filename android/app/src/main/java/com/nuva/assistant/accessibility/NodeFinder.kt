package com.nuva.assistant.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.nuva.assistant.command.UiSelector

/**
 * Finds UI nodes by the §9 priority order:
 *
 *   1. resource-id        (most stable)
 *   2. contentDescription (stable across languages)
 *   3. visible text
 *   4. class name
 *   5. coordinate fallback (handled by the executor, LAST resort)
 */
object NodeFinder {

    fun find(root: AccessibilityNodeInfo, selector: UiSelector): AccessibilityNodeInfo? {
        selector.resourceId?.let { id ->
            findByResourceId(root, id, selector.index)?.let { return it }
        }
        selector.contentDescription?.let { description ->
            findFirst(root) { node ->
                node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
            }?.let { return it }
        }
        selector.text?.let { text ->
            findByText(root, text, selector.index)?.let { return it }
        }
        selector.className?.let { className ->
            findFirst(root) { node -> node.className?.toString() == className }?.let { return it }
        }
        return null
    }

    /** Breadth-first search helper, shared by all strategies. */
    fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited += 1
            if (predicate(node) && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findByResourceId(root: AccessibilityNodeInfo, id: String, index: Int?): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty().filter { it != null }
        return pick(matches, index)
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String, index: Int?): AccessibilityNodeInfo? {
        val exact = root.findAccessibilityNodeInfosByText(text).orEmpty()
            .filter { it != null && it.isVisibleToUser }
        pick(exact, index)?.let { return it }
        return findFirst(root) { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        }
    }

    private fun pick(matches: List<AccessibilityNodeInfo>, index: Int?): AccessibilityNodeInfo? =
        index?.let { matches.getOrNull(it) } ?: matches.firstOrNull()

    private const val MAX_VISITED_NODES = 2_000
}
