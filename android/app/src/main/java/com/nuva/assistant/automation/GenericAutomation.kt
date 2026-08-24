package com.nuva.assistant.automation

import com.nuva.assistant.accessibility.ScreenReader
import com.nuva.assistant.accessibility.SwipeController
import com.nuva.assistant.accessibility.TapController
import com.nuva.assistant.accessibility.TextController
import com.nuva.assistant.command.NuvaAction

/**
 * Generic UI automation (roadmap step 10): tap, type, swipe, scroll, read —
 * every operation carries timeouts and retries, and reports a user-sayable
 * reason on failure (§ error handling: never fail silently).
 */
object GenericAutomation {

    sealed interface Outcome {
        data object Success : Outcome
        data class Failure(val userReason: String) : Outcome
    }

    suspend fun execute(action: NuvaAction): Outcome = when (action) {
        is NuvaAction.Tap -> when (val result = TapController.tap(action.target, action.point, action.longClick)) {
            is TapController.TapResult.Success -> Outcome.Success
            is TapController.TapResult.ServiceMissing -> Outcome.Failure(result.reason)
            is TapController.TapResult.NodeNotFound -> Outcome.Failure(result.reason)
        }

        is NuvaAction.TypeText -> when (val result = TextController.type(action.text, action.target, action.submit)) {
            is TextController.TypeResult.Success -> Outcome.Success
            is TextController.TypeResult.ServiceMissing -> Outcome.Failure(result.reason)
            is TextController.TypeResult.NodeNotFound -> Outcome.Failure(result.reason)
        }

        is NuvaAction.Swipe -> when (
            val result = SwipeController.swipe(action.direction, action.distance, action.from, action.to)
        ) {
            is SwipeController.MovementResult.Success -> Outcome.Success
            is SwipeController.MovementResult.ServiceMissing -> Outcome.Failure(result.reason)
            is SwipeController.MovementResult.Failed -> Outcome.Failure(result.reason)
        }

        is NuvaAction.Scroll -> when (
            val result = SwipeController.scroll(action.direction, action.amount, action.target)
        ) {
            is SwipeController.MovementResult.Success -> Outcome.Success
            is SwipeController.MovementResult.ServiceMissing -> Outcome.Failure(result.reason)
            is SwipeController.MovementResult.Failed -> Outcome.Failure(result.reason)
        }

        is NuvaAction.ReadScreen -> when (val result = ScreenReader.read(action.scope)) {
            is ScreenReader.ReadResult.Success -> Outcome.Success // text spoken by the executor
            is ScreenReader.ReadResult.ServiceMissing -> Outcome.Failure(result.reason)
            is ScreenReader.ReadResult.Empty -> Outcome.Failure(result.reason)
        }

        else -> Outcome.Failure("That action is handled elsewhere.")
    }
}
