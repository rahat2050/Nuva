package com.nuva.assistant.automation

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import com.nuva.assistant.R
import com.nuva.assistant.service.NuvaQuickSettingsTileService

/** Opens Android's user-owned "Add Quick Settings tile" prompt on Android 13+. */
object QuickSettingsTileController {
    sealed interface Result {
        data object Added : Result
        data object AlreadyAdded : Result
        data object NotAdded : Result
        data object Unsupported : Result
        data class Failed(val reason: String) : Result
    }

    fun requestAdd(activity: Activity, callback: (Result) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(Result.Unsupported)
            return
        }
        val manager = activity.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            callback(Result.Failed("Quick Settings service unavailable"))
            return
        }
        runCatching {
            manager.requestAddTileService(
                ComponentName(activity, NuvaQuickSettingsTileService::class.java),
                activity.getString(R.string.quick_tile_label),
                Icon.createWithResource(activity, R.drawable.ic_nuva_tile),
                activity.mainExecutor,
            ) { response ->
                callback(
                    when (response) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> Result.Added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> Result.AlreadyAdded
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Result.NotAdded
                        else -> Result.Failed("Android returned tile result $response")
                    },
                )
            }
        }.onFailure { error ->
            callback(Result.Failed(error.message ?: "Could not request Quick Settings tile"))
        }
    }

    fun message(result: Result): String = when (result) {
        Result.Added -> "Talk to NUVA Quick Settings tile added ✓"
        Result.AlreadyAdded -> "Talk to NUVA tile is already added."
        Result.NotAdded -> "Tile was not added. You can try again from Settings."
        Result.Unsupported -> "Android 12 বা পুরোনো হলে Quick Settings edit করে NUVA tile manually add করুন।"
        is Result.Failed -> result.reason
    }
}
