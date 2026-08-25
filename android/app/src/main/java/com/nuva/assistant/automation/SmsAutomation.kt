package com.nuva.assistant.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * SMS flow (v1.1). The executor's confirmation gate ALWAYS runs before this
 * class is reached — there is no code path that sends an SMS without an
 * explicit user confirmation.
 *
 * Two modes, best available first:
 *  1. SEND (SEND_SMS permission granted) — SmsManager sends directly after
 *     the user confirmed.
 *  2. COMPOSE (no permission / send failed) — the messaging app opens with
 *     recipient + text prefilled; the user taps Send themselves.
 */
object SmsAutomation {

    sealed interface Result {
        data object Sent : Result
        data class ComposeOpened(val reason: String) : Result
        data class Failed(val userReason: String) : Result
    }

    fun canSendDirectly(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun sendOrCompose(context: Context, number: String, message: String): Result {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return Result.Failed("Number ta thik moto paina.")

        if (canSendDirectly(context)) {
            try {
                @Suppress("DEPRECATION") // still the supported route for one-off sends
                val manager = SmsManager.getDefault()
                // Long messages are divided the way the system SMS app does.
                val parts = manager.divideMessage(message.take(1600))
                if (parts.size == 1) {
                    manager.sendTextMessage(digits, null, message.take(1600), null, null)
                } else {
                    manager.sendMultipartTextMessage(digits, null, parts, null, null)
                }
                return Result.Sent
            } catch (err: Exception) {
                // fall through to compose — never fail silently.
            }
        }
        return compose(context, digits, message)
    }

    /** Opens the SMS app with recipient and text prefilled (user taps Send). */
    fun compose(context: Context, number: String, message: String): Result = try {
        val uri = Uri.parse("smsto:${number.filter { it.isDigit() || it == '+' }}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message.take(1600))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Result.ComposeOpened("SMS screen khulchi — Send chapun.")
    } catch (err: Exception) {
        Result.Failed("SMS app khulte parini.")
    }
}
