package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** User-finalized Android package removal handoff. */
object AppManagement {
    sealed interface Result {
        data class PromptOpened(val label: String) : Result
        data object NotFound : Result
        data object SensitiveBlocked : Result
        data class Failed(val reason: String) : Result
    }

    fun requestUninstall(context: Context, appName: String): Result {
        if (SensitiveAppPolicy.isSensitiveAppName(appName)) return Result.SensitiveBlocked
        val installed = AppLauncher.findInstalledApp(context, appName) ?: return Result.NotFound
        if (SensitiveAppPolicy.isSensitivePackage(installed.packageName)) return Result.SensitiveBlocked
        if (installed.packageName == context.packageName) return Result.Failed("NUVA nijeke uninstall initiate korbe na.")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${installed.packageName}"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.PromptOpened(installed.label)
        } catch (_: Exception) {
            Result.Failed("Android uninstall confirmation khulte parini.")
        }
    }
}
