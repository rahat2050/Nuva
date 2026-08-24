package com.nuva.assistant.accessibility

import com.nuva.assistant.command.UiSelector

/**
 * Text input via AccessibilityNodeInfo.ACTION_SET_TEXT, with submit (Enter)
 * through an IME action or the node's own send button.
 */
object TextController {

    sealed interface TypeResult {
        data class Success(val submitted: Boolean) : TypeResult
        data class ServiceMissing(val reason: String = "Accessibility permission is required.") : TypeResult
        data class NodeNotFound(val reason: String) : TypeResult
    }

    suspend fun type(text: String, target: UiSelector?, submit: Boolean): TypeResult {
        val service = NuvaAccessibilityService.instance
            ?: return TypeResult.ServiceMissing()

        // No target → type into whatever is focused (keyboard field already open).
        val node = if (target != null) {
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(NuvaAccessibilityService.RETRY_ATTEMPTS) { attempt ->
                found = service.findNode(target)
                if (found == null && attempt < NuvaAccessibilityService.RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(NuvaAccessibilityService.RETRY_DELAY_MS)
                }
            }
            found ?: return TypeResult.NodeNotFound("I couldn't find the text field.")
        } else {
            service.findFocusedEditable() ?: return TypeResult.NodeNotFound("No text field is focused.")
        }

        val typed = service.setText(node, text)
        if (!typed) return TypeResult.NodeNotFound("Couldn't type into that field.")

        var submitted = false
        if (submit) {
            submitted = service.clickNode(findSubmitButton(service, node))
        }
        return TypeResult.Success(submitted)
    }

    private fun findSubmitButton(
        service: NuvaAccessibilityService,
        field: android.view.accessibility.AccessibilityNodeInfo,
    ): android.view.accessibility.AccessibilityNodeInfo {
        val sendCandidates = listOf("Send", "Search", "পাঠান", "খুঁজুন", "Go")
        for (label in sendCandidates) {
            val node = service.findNode(
                UiSelector(text = label, contentDescription = label),
            )
            if (node != null) return node
        }
        return field
    }
}
