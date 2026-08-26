package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.accessibility.TextController
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.UiSelector
import kotlinx.coroutines.delay

/**
 * VERIFIED FLOW (v1.4): every step is checked before the next one —
 *   launch → wait for the WhatsApp package → find the composer → type →
 *   VERIFY the open chat is the intended recipient → only then tap Send.
 * If verification fails at any point the flow STOPS: no blind retries, no
 * sending to an unverified chat.
 */

/**
 * WhatsApp flow (roadmap step 11 — the first confirmed medium-risk flow):
 *
 *   SEND_MESSAGE(whatsapp) → [user already confirmed] → open the chat via a
 *   wa.me deep link when a number is known (most reliable path) or plain
 *   launch otherwise → find the message field → type → tap Send.
 *
 * The message is NEVER sent without the executor's confirmation gate, which
 * happens before this class is reached.
 */
object WhatsAppAutomation {

    private const val MSG_FIELD_ID = "com.whatsapp:id/entry"
    private const val SEND_BUTTON_ID = "com.whatsapp:id/send"
    private const val SEND_BUTTON_DESC = "Send"

    sealed interface Result {
        data object Sent : Result
        data class Failed(val userReason: String) : Result
    }

    suspend fun sendMessage(context: Context, action: NuvaAction.SendMessage): Result {
        if (NuvaAccessibilityService.instance == null) {
            return Result.Failed("Accessibility permission is required.")
        }

        // 1) Open the chat — deep link when we have a number, plain launch otherwise.
        if (!openChat(context, action)) return Result.Failed("WhatsApp chat khulte parini.")

        // 1b) VERIFY we really are inside WhatsApp before touching anything.
        if (!waitForWhatsAppPackage(timeoutMs = 5_000)) {
            return Result.Failed("WhatsApp-er screen paua jay nai — থেমে গেলাম।", "whatsapp package verify timeout")
        }

        // 2) Wait for the chat screen, then find the message field (retry loop).
        var field: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(8) { attempt ->
            val service = NuvaAccessibilityService.instance
            if (service != null) {
                field = service.findNode(UiSelector(resourceId = MSG_FIELD_ID))
                    ?: service.findNode(UiSelector(resourceId = "com.whatsapp:id/edittext_composer_entry"))
            }
            if (field == null && attempt < 7) delay(400)
        }
        if (field == null) return Result.Failed("Message field khuje painai.")

        // 3) Type the message into the focused composer.
        when (val typed = TextController.type(action.message, target = null, submit = false)) {
            is TextController.TypeResult.Success -> Unit
            is TextController.TypeResult.ServiceMissing -> return Result.Failed(typed.reason)
            is TextController.TypeResult.NodeNotFound -> return Result.Failed(typed.reason)
        }

        // 3b) VERIFY the open chat is the intended recipient before sending —
        // a wrong chat must never receive the message.
        if (!verifyRecipient(action.contact)) {
            return Result.Failed(
                "Chat ta ${action.contact}-er bole confirm korte parini — নিরাপত্তার জন্য পাঠাইনি।",
                "recipient verification failed",
            )
        }

        // 4) Tap Send.
        repeat(3) { attempt ->
            val service = NuvaAccessibilityService.instance ?: return Result.Failed("Service bondho hoye geche.")
            val send = service.findNode(
                UiSelector(resourceId = SEND_BUTTON_ID, contentDescription = SEND_BUTTON_DESC),
            )
            if (send != null && service.clickNode(send)) {
                delay(250) // let WhatsApp register the send before we speak "sent"
                return Result.Sent
            }
            if (attempt < 2) delay(350)
        }
        return Result.Failed("Send button khuje painai.")
    }

    /** Polls the accessibility root until WhatsApp is really the foreground app. */
    private suspend fun waitForWhatsAppPackage(timeoutMs: Long): Boolean {
        val service = NuvaAccessibilityService.instance ?: return true // no service → unverifiable, flow already guarded at entry
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.rootInActiveWindow?.packageName?.toString() == "com.whatsapp") return true
            delay(200)
        }
        return false
    }

    /**
     * Best-effort recipient verification: the chat screen must surface the
     * contact's name (or its distinctive words) somewhere on screen. Absence
     * of any matching text ⇒ NOT verified ⇒ the flow aborts before Send.
     */
    private fun verifyRecipient(contact: String): Boolean {
        val service = NuvaAccessibilityService.instance ?: return false
        val expected = contact.trim().lowercase()
        if (expected.isEmpty()) return false
        val tokens = expected.split(" ", "-").filter { it.length >= 3 }
        val screen = service.readVisibleScreen(maxChars = 2000)?.lowercase() ?: return false
        if (screen.contains(expected)) return true
        // Multi-word names: every distinctive token present (e.g. "Rahat Ahmed").
        if (tokens.isNotEmpty() && tokens.all { screen.contains(it) }) return true
        // Phone-number-only recipients appear formatted on the title bar.
        val digits = contact.filter { it.isDigit() }
        if (digits.length >= 7 && screen.contains(digits.takeLast(7))) return true
        return false
    }

    private fun openChat(context: Context, action: NuvaAction.SendMessage): Boolean {
        val number = action.phoneNumber
        if (!number.isNullOrBlank()) {
            val digits = number.filter { it.isDigit() || it == '+' }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return true
            } catch (err: Exception) {
                // fall through to a plain launch
            }
        }
        return when (AppLauncher.openApp(context, "whatsapp", "com.whatsapp")) {
            is AppLauncher.LaunchResult.Success -> true
            is AppLauncher.LaunchResult.NotFound -> false
        }
    }
}
