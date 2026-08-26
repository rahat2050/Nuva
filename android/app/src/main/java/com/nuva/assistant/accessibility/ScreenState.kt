package com.nuva.assistant.accessibility

import com.nuva.assistant.core.security.SensitiveAppPolicy

/**
 * Reusable screen-understanding model (v1.5, Phase 4): the MINIMUM UI
 * information commands need — package, title, bounded visible text, buttons
 * (clickable labels), inputs (editable), scrollables, focused element and
 * the actions those imply.
 *
 * Safety is baked into the model itself, not the collectors:
 *  * password/PIN fields are NEVER captured (isPassword nodes skipped);
 *  * OTP/PIN-like digit codes are redacted from every text it holds;
 *  * nothing is persisted or uploaded — this is a transient snapshot.
 *
 * Pure Kotlin (builder callbacks are injected) → JVM-testable.
 */
object ScreenStateModel {

    /** One interactive element as the user can name it. */
    data class UiElement(
        val label: String,
        val kind: Kind,
        val focused: Boolean = false,
    ) {
        enum class Kind { BUTTON, INPUT, LIST }
    }

    data class ScreenState(
        val packageName: String?,
        val title: String?,
        val visibleText: String,
        val elements: List<UiElement>,
        val sensitiveScreen: Boolean,
    ) {
        val buttons: List<UiElement> get() = elements.filter { it.kind == UiElement.Kind.BUTTON }
        val inputs: List<UiElement> get() = elements.filter { it.kind == UiElement.Kind.INPUT }
        val lists: List<UiElement> get() = elements.filter { it.kind == UiElement.Kind.LIST }
        val focusedElement: UiElement? get() = elements.firstOrNull { it.focused }
    }

    /** Raw facts a collector gathers — model applies all the safety. */
    data class RawNode(
        val text: String?,
        val contentDescription: String?,
        val isPassword: Boolean,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isFocused: Boolean,
    )

    data class RawScreen(
        val packageName: String?,
        val titleCandidates: List<String>,
        val nodes: List<RawNode>,
        val maxElements: Int = 40,
        val maxTextChars: Int = 1200,
    )

    /** Builds a safe snapshot from raw collected facts. */
    fun build(raw: RawScreen): ScreenState {
        val elements = ArrayList<UiElement>(raw.maxElements)
        val textBuffer = StringBuilder()

        for (node in raw.nodes) {
            if (elements.size >= raw.maxElements) break
            // LEVEL 2: password/PIN fields are never captured in any form.
            if (node.isPassword) continue
            val label = (node.contentDescription ?: node.text)?.trim()?.take(80)?.ifBlank { null } ?: continue
            val kind = when {
                node.isEditable -> UiElement.Kind.INPUT
                node.isClickable -> UiElement.Kind.BUTTON
                node.isScrollable -> UiElement.Kind.LIST
                else -> null
            }
            if (kind != null && elements.none { it.label.equals(label, ignoreCase = true) }) {
                elements.add(UiElement(label, kind, node.isFocused))
            }
            if (textBuffer.length < raw.maxTextChars) {
                node.text?.let { textBuffer.appendLine(it.take(200)) }
            }
        }

        val visibleText = SensitiveAppPolicy.redactCodes(textBuffer.toString().trim().take(raw.maxTextChars))
        val title = raw.titleCandidates.firstOrNull { it.isNotBlank() }

        return ScreenState(
            packageName = raw.packageName,
            title = title,
            visibleText = visibleText,
            elements = elements,
            sensitiveScreen = raw.packageName?.let { SensitiveAppPolicy.isSensitivePackage(it) } ?: false,
        )
    }

    /**
     * Finds buttons matching a spoken label ("এটা press করো"-style commands).
     * Returns exact matches first; multiple/zero results are reported so the
     * caller can ASK instead of guessing.
     */
    fun matchButtons(state: ScreenState, label: String): List<UiElement> {
        val wanted = label.trim().lowercase()
        if (wanted.isBlank()) return emptyList()
        val exact = state.buttons.filter { it.label.lowercase() == wanted }
        if (exact.isNotEmpty()) return exact
        val contains = state.buttons.filter { it.label.lowercase().contains(wanted) }
        if (contains.isNotEmpty()) return contains
        return state.buttons.filter { wanted.contains(it.label.lowercase()) && it.label.length >= 3 }
    }

    /** Human/voice summary: "৩টা বাটন: Send, Cancel, …; ১টা লেখার ঘর"। */
    fun summarize(state: ScreenState): String {
        if (state.sensitiveScreen) {
            return "Financial app er screen — বিস্তার করি na."
        }
        val parts = mutableListOf<String>()
        state.title?.let { parts.add("স্ক্রিন: $it") }
        if (state.buttons.isNotEmpty()) {
            parts.add("বাটন (${state.buttons.size}): " + state.buttons.take(6).joinToString(", ") { it.label })
        }
        if (state.inputs.isNotEmpty()) parts.add("লেখার ঘর: ${state.inputs.size}টা")
        if (state.lists.isNotEmpty()) parts.add("লিস্ট: ${state.lists.size}টা")
        state.focusedElement?.let { parts.add("সিলেক্টেড: ${it.label}") }
        if (parts.isEmpty()) return "স্ক্রিনে কিছু পড়ার মতো নেই।"
        return parts.joinToString(" · ")
    }
}
