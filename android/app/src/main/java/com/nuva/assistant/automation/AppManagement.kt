package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.AppManagementPanel
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** User-finalized Android package removal handoff. */
object AppManagement {
    sealed interface Result {
        data class PromptOpened(val label: String) : Result
        data object NotFound : Result
        data object SensitiveBlocked : Result
        data class Failed(val reason: String) : Result
    }

    fun openPanel(context: Context, appName: String, panel: AppManagementPanel): Result {
        val installed = AppLauncher.findInstalledApp(context, appName)
        if (installed == null) {
            return if (panel == AppManagementPanel.PLAY_STORE && AppLauncher.openPlayStoreSearch(context, appName)) {
                Result.PromptOpened(appName)
            } else {
                Result.NotFound
            }
        }
        val opened = when (panel) {
            AppManagementPanel.APP_INFO -> AppLauncher.openAppInfo(context, installed.packageName)
            AppManagementPanel.NOTIFICATIONS -> try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, installed.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: Exception) {
                false
            }
            AppManagementPanel.PLAY_STORE -> try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${installed.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: Exception) {
                AppLauncher.openUrl(context, "https://play.google.com/store/apps/details?id=${installed.packageName}")
            }
        }
        return if (opened) Result.PromptOpened(installed.label) else Result.Failed("App management screen khulte parini.")
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
