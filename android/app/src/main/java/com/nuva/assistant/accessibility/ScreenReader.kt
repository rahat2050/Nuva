package com.nuva.assistant.accessibility

/**
 * READ_SCREEN support: collects visible/focused/all text from the active
 * window. Used both for speaking the screen and for command context.
 */
object ScreenReader {

    sealed interface ReadResult {
        data class Success(val text: String) : ReadResult
        data class ServiceMissing(val reason: String = "Accessibility permission is required.") : ReadResult
        data class Empty(val reason: String = "The screen has no readable text.") : ReadResult
    }

    fun read(scope: com.nuva.assistant.command.ReadScreenScope?): ReadResult {
        val service = NuvaAccessibilityService.instance
            ?: return ReadResult.ServiceMissing()

        // VISIBLE (default) and ALL both read the active window; FOCUSED reads
        // the input-focus subtree when there is one.
        val text = when (scope) {
            com.nuva.assistant.command.ReadScreenScope.FOCUSED ->
                service.findFocusedEditable()?.text?.toString()
                    ?: service.readVisibleScreen()
            else -> service.readVisibleScreen()
        }

        return when {
            text.isNullOrBlank() -> ReadResult.Empty()
            else -> ReadResult.Success(text.take(4000))
        }
    }
}
