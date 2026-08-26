package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.NuvaAction

/** User-reviewed email compose flow. NUVA never presses Send. */
object EmailComposer {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    fun compose(context: Context, action: NuvaAction.ComposeEmail): Result {
        val uri = mailtoUri(action.recipient)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).apply {
            action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it.take(200)) }
            action.body?.let { putExtra(Intent.EXTRA_TEXT, it.take(5_000)) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (_: Exception) {
            Result.Failed("Email app khulte parini.")
        }
    }

    fun mailtoUri(recipient: String?): String =
        if (recipient.isNullOrBlank()) "mailto:" else "mailto:$recipient"
}
