package com.nuva.assistant.automation

import android.content.ClipData
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

    fun composeWithAttachment(context: Context, action: NuvaAction.ComposeEmail, attachment: Uri): Result {
        val mime = context.contentResolver.getType(attachment) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            action.recipient?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it.take(200)) }
            action.body?.let { putExtra(Intent.EXTRA_TEXT, it.take(5_000)) }
            putExtra(Intent.EXTRA_STREAM, attachment)
            clipData = ClipData.newUri(context.contentResolver, "NUVA email attachment", attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                Intent.createChooser(intent, "Email with attachment").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.Opened
        } catch (_: Exception) {
            Result.Failed("Attachment-shoho email app khulte parini.")
        }
    }

    fun composeWithAttachments(context: Context, action: NuvaAction.ComposeEmail, attachments: List<Uri>): Result {
        if (attachments.isEmpty()) return Result.Failed("Kono attachment select hoyni.")
        val selected = attachments.distinct().take(10)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            action.recipient?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
            action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it.take(200)) }
            action.body?.let { putExtra(Intent.EXTRA_TEXT, it.take(5_000)) }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected))
            clipData = ClipData.newUri(context.contentResolver, "NUVA email attachments", selected.first()).also { clip ->
                selected.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(
                Intent.createChooser(intent, "Email with attachments").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.Opened
        } catch (_: Exception) {
            Result.Failed("Multiple attachment-shoho email app khulte parini.")
        }
    }

    fun mailtoUri(recipient: String?): String =
        if (recipient.isNullOrBlank()) "mailto:" else "mailto:$recipient"
}
