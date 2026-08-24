package com.nuva.assistant.automation

import android.content.Context
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.accessibility.TextController
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.UiSelector
import kotlinx.coroutines.delay

/**
 * YouTube automation (roadmap step 12): search + play via the app's own search
 * field, using Accessibility node IDs — no fragile coordinates.
 */
object YouTubeAutomation {

    private const val SEARCH_BUTTON_DESC = "Search"
    private const val SEARCH_FIELD_ID = "com.google.android.youtube:id/search_edit_text"
    private const val SEARCH_PLATE_ID = "com.google.android.youtube:id/search_plate"

    sealed interface Result {
        data object Playing : Result
        data object SearchReady : Result
        data class Failed(val userReason: String) : Result
    }

    /**
     * Opens YouTube and runs the query. When [autoplayFirstResult] is true it
     * taps the first video after searching.
     */
    suspend fun searchAndPlay(context: Context, action: NuvaAction.PlayMedia, autoplayFirstResult: Boolean): Result {
        val service = NuvaAccessibilityService.instance
            ?: return Result.Failed("Accessibility permission is required.")

        when (AppLauncher.openApp(context, "youtube", "com.google.android.youtube")) {
            is AppLauncher.LaunchResult.NotFound -> return Result.Failed("YouTube install kora nai.")
            is AppLauncher.LaunchResult.Success -> Unit
        }

        // 1) Open search (magnifier button, described as "Search").
        repeat(5) { attempt ->
            val svc = NuvaAccessibilityService.instance ?: return Result.Failed("Service bondho hoye geche.")
            val searchButton = svc.findNode(UiSelector(contentDescription = SEARCH_BUTTON_DESC))
            if (searchButton != null && svc.clickNode(searchButton)) return@repeat
            delay(400)
        }

        // 2) Type the query into the search field.
        repeat(5) { attempt ->
            val svc = NuvaAccessibilityService.instance ?: return Result.Failed("Service bondho hoye geche.")
            val field = svc.findNode(UiSelector(resourceId = SEARCH_FIELD_ID))
                ?: svc.findNode(UiSelector(resourceId = SEARCH_PLATE_ID))
            if (field != null) {
                when (val typed = TextController.type(action.query, UiSelector(resourceId = SEARCH_FIELD_ID), submit = true)) {
                    is TextController.TypeResult.Success -> Unit
                    else -> return Result.Failed("Search likhte parini.")
                }
                if (!autoplayFirstResult) return Result.SearchReady
                // 3) Tap the first result once the list loads.
                repeat(6) { resultAttempt ->
                    delay(450)
                    val firstVideo = NuvaAccessibilityService.instance?.findNode(
                        UiSelector(className = "android.view.ViewGroup", index = 0),
                    )
                    if (firstVideo != null && NuvaAccessibilityService.instance?.clickNode(firstVideo) == true) {
                        return Result.Playing
                    }
                    if (resultAttempt == 5) return Result.SearchReady
                }
                return Result.SearchReady
            }
            delay(400)
        }
        return Result.Failed("Search field khuje painai.")
    }
}
