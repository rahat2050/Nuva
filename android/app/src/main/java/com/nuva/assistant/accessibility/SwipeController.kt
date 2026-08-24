package com.nuva.assistant.accessibility

import com.nuva.assistant.command.ScreenPoint
import com.nuva.assistant.command.SwipeDirection
import com.nuva.assistant.command.SwipeDistance
import com.nuva.assistant.command.UiSelector

/**
 * Swipe and scroll. Prefers semantic node scrolling (ACCESSIBILITY ACTION_SCROLL_*)
 * over raw gestures; gestures are the fallback.
 */
object SwipeController {

    sealed interface MovementResult {
        data object Success : MovementResult
        data class ServiceMissing(val reason: String = "Accessibility permission is required.") : MovementResult
        data class Failed(val reason: String) : MovementResult
    }

    suspend fun swipe(
        direction: SwipeDirection?,
        distance: SwipeDistance?,
        from: ScreenPoint?,
        to: ScreenPoint?,
    ): MovementResult {
        val service = NuvaAccessibilityService.instance
            ?: return MovementResult.ServiceMissing()

        val (start, end) = when {
            from != null && to != null -> from to to
            direction != null -> directionEndpoints(direction, distance ?: SwipeDistance.MEDIUM)
            else -> return MovementResult.Failed("Swipe needs a direction or both points.")
        }

        val ok = service.swipe(start, end)
        return if (ok) MovementResult.Success else MovementResult.Failed("Swipe gesture was cancelled.")
    }

    suspend fun scroll(direction: SwipeDirection, amount: Int, target: UiSelector?): MovementResult {
        val service = NuvaAccessibilityService.instance
            ?: return MovementResult.ServiceMissing()

        // 1) Try real scroll actions on a matching scrollable node.
        val node = target?.let { service.findNode(it) } ?: service.findScrollable()
        if (node != null) {
            var scrolled = false
            repeat(amount.coerceIn(1, 20)) {
                if (service.scrollNode(node, direction)) scrolled = true
            }
            if (scrolled) return MovementResult.Success
        }

        // 2) Gesture fallback: full-screen flicks.
        repeat(amount.coerceIn(1, 20)) {
            val (start, end) = directionEndpoints(direction, SwipeDistance.LONG)
            if (!service.swipe(start, end)) return MovementResult.Failed("Scroll gesture was cancelled.")
            kotlinx.coroutines.delay(300)
        }
        return MovementResult.Success
    }

    private fun directionEndpoints(direction: SwipeDirection, distance: SwipeDistance): Pair<ScreenPoint, ScreenPoint> {
        val span = when (distance) {
            SwipeDistance.SHORT -> 0.2f
            SwipeDistance.MEDIUM -> 0.45f
            SwipeDistance.LONG -> 0.75f
        }
        return when (direction) {
            SwipeDirection.UP -> ScreenPoint(0.5f, 0.5f + span / 2) to ScreenPoint(0.5f, 0.5f - span / 2)
            SwipeDirection.DOWN -> ScreenPoint(0.5f, 0.5f - span / 2) to ScreenPoint(0.5f, 0.5f + span / 2)
            SwipeDirection.LEFT -> ScreenPoint(0.5f + span / 2, 0.5f) to ScreenPoint(0.5f - span / 2, 0.5f)
            SwipeDirection.RIGHT -> ScreenPoint(0.5f - span / 2, 0.5f) to ScreenPoint(0.5f + span / 2, 0.5f)
        }
    }
}
