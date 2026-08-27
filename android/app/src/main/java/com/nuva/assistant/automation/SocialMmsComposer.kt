package com.nuva.assistant.automation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** Visible social/MMS compose handoffs; final Post/Send always belongs to the user. */
object SocialMmsComposer {
    sealed interface Result {
        data class Opened(val speech: String) : Result
        data object SensitiveBlocked : Result
        data class Failed(val reason: String) : Result
    }

    fun social(context: Context, action: NuvaAction.ComposeSocialPost): Result {
        if (blocked(action.text)) return Result.SensitiveBlocked
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, action.text.take(5_000))
            setPackage(action.platform.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Opened("${action.platform.wireName} compose screen khulechi — final Post apni korben.")
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, action.text.take(5_000))
            }
            try {
                context.startActivity(
                    Intent.createChooser(fallback, "Choose social app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Result.Opened("App-specific compose paini; share chooser khulechi — final action apnar.")
            } catch (_: Exception) {
                Result.Failed("Social compose/share screen khulte parini.")
            }
        }
    }

    fun mms(context: Context, action: NuvaAction.ComposeMms): Result {
        val text = action.body.orEmpty()
        if (blocked(text)) return Result.SensitiveBlocked
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.recipient.orEmpty()}" )).apply {
            putExtra("sms_body", text.take(2_000))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Opened("Messaging composer khulechi — attachment chara draft; final Send apni chapun.")
        } catch (_: Exception) {
            Result.Failed("Messaging composer khulte parini.")
        }
    }

    fun mmsWithAttachment(context: Context, action: NuvaAction.ComposeMms, attachment: Uri): Result {
        val text = action.body.orEmpty()
        if (blocked(text)) return Result.SensitiveBlocked
        val mime = context.contentResolver.getType(attachment) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, attachment)
            putExtra(Intent.EXTRA_TEXT, text.take(2_000))
            action.recipient?.let { putExtra("address", it) }
            clipData = ClipData.newUri(context.contentResolver, "NUVA MMS attachment", attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                Intent.createChooser(intent, "Send as message").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.Opened("Attachment-shoho message chooser khulechi — final app/recipient/Send apnar.")
        } catch (_: Exception) {
            Result.Failed("Attachment message composer khulte parini.")
        }
    }

    fun openVoicemail(context: Context): Result = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("voicemail:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Result.Opened("Voicemail dialer khulechi — final call apni korben.")
    } catch (_: Exception) {
        Result.Failed("Voicemail dialer available nei.")
    }

    private fun blocked(text: String): Boolean =
        SensitiveAppPolicy.mentionsCredentials(text) || SensitiveAppPolicy.refusalForText(text) != null
}
