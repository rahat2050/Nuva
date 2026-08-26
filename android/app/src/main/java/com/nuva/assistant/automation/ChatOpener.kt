package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.command.MessagingApp
import com.nuva.assistant.command.NuvaAction
import kotlinx.coroutines.delay

/**
 * OPEN_CHAT (v1.4): opens a specific conversation WITHOUT sending anything.
 *
 * WhatsApp: wa.me deep link when a number is known, then the foreground
 * package is VERIFIED via the accessibility service when available (never
 * blindly assumed). Other messaging apps open their main screen — NUVA says
 * so honestly instead of pretending it found the chat.
 */
object ChatOpener {

    sealed interface Result {
        data class Opened(val speech: String, val verified: Boolean) : Result
        data class Failed(val speech: String, val reason: String) : Result
    }

    suspend fun open(context: Context, action: NuvaAction.OpenChat): Result {
        if (action.app == MessagingApp.WHATSAPP) {
            val number = action.phoneNumber
            if (!number.isNullOrBlank()) {
                val digits = number.filter { it.isDigit() || it == '+' }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (!launched) {
                    return openAppFallback(context, action, "whatsapp", "com.whatsapp")
                }
                val verified = waitForPackage("com.whatsapp", timeoutMs = 4_000)
                return Result.Opened(
                    speech = "${action.contact}-এর chat খুলেছি।",
                    verified = verified,
                )
            }
            // No number — open WhatsApp and let the user tap the chat.
            return openAppFallback(context, action, "whatsapp", "com.whatsapp")
        }

        return when (val pkg = MessagingRegistry.packageOf(action.app)) {
            null -> Result.Failed("এই অ্যাপের chat NUVA খুলতে পারে না।", "app not supported for chat")
            else -> openAppFallback(context, action, action.app.wireName, pkg)
        }
    }

    private suspend fun openAppFallback(
        context: Context,
        action: NuvaAction.OpenChat,
        appName: String,
        pkg: String,
    ): Result = when (val r = AppLauncher.openApp(context, appName, pkg)) {
        is AppLauncher.LaunchResult.Success ->
            Result.Opened(
                speech = "$appName খুলেছি — ${action.contact}-এর chat টা বেছে নিন।",
                verified = false,
            )

        is AppLauncher.LaunchResult.NotFound ->
            Result.Failed("$appName install করা নেই।", "app missing")
    }

    /**
     * Verifies the foreground package via the AccessibilityService (the only
     * sanctioned way for a third-party app). No accessibility → unverifiable,
     * reported honestly, never blindly assumed.
     */
    private suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val service = NuvaAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val foreground = service.rootInActiveWindow?.packageName?.toString()
            if (foreground == pkg) return true
            delay(200)
        }
        return false
    }
}
