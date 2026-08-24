package com.nuva.assistant.accessibility

import com.nuva.assistant.command.ScreenPoint
import com.nuva.assistant.command.UiSelector

/**
 * High-level tap operations with timeouts and retries (roadmap step 10).
 * Semantic selectors first; coordinate taps are the explicit last resort and
 * are always escalated to medium risk by the validator layer.
 */
object TapController {

    sealed interface TapResult {
        data object Success : TapResult
        data class ServiceMissing(val reason: String = "Accessibility permission is required.") : TapResult
        data class NodeNotFound(val reason: String) : TapResult
    }

    suspend fun tap(selector: UiSelector?, point: ScreenPoint?, longClick: Boolean = false): TapResult {
        val service = NuvaAccessibilityService.instance
            ?: return TapResult.ServiceMissing()

        // 1) Semantic selector, preferred (§9).
        if (selector != null) {
            repeat(NuvaAccessibilityService.RETRY_ATTEMPTS) { attempt ->
                val node = service.findNode(selector)
                if (node != null) {
                    val clicked = if (longClick) service.longClickNode(node) else service.clickNode(node)
                    if (clicked) return TapResult.Success
                }
                if (attempt < NuvaAccessibilityService.RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(NuvaAccessibilityService.RETRY_DELAY_MS)
                }
            }
            if (point == null) {
                return TapResult.NodeNotFound("I couldn't find the required button.")
            }
        }

        // 2) Coordinate fallback.
        if (point != null) {
            val ok = service.tapAt(point, longClick)
            return if (ok) TapResult.Success else TapResult.NodeNotFound("Tap gesture was cancelled.")
        }

        return TapResult.NodeNotFound("Nothing to tap: no target and no point.")
    }
}
