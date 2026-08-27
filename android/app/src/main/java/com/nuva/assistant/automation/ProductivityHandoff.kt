package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** Visible, user-finalized share/contact handoffs. */
object ProductivityHandoff {

    sealed interface Result {
        data class Opened(val speech: String) : Result
        data class Failed(val reason: String) : Result
        data object SensitiveBlocked : Result
    }

    fun shareText(context: Context, action: NuvaAction.ShareText): Result {
        if (SensitiveAppPolicy.mentionsCredentials(action.text) || SensitiveAppPolicy.refusalForText(action.text) != null) {
            return Result.SensitiveBlocked
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, action.text.take(5_000))
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Share text with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.Opened("Text share sheet e diyechi — app/recipient o final action apni beche nin.")
        } catch (_: Exception) {
            Result.Failed("Text share sheet khulte parini.")
        }
    }

    fun createContactDraft(context: Context, action: NuvaAction.CreateContactDraft): Result {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, action.name.take(120))
            action.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            action.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Opened("Contact draft khulechi — review kore final Save apni chapun.")
        } catch (_: Exception) {
            Result.Failed("Contacts app-er insert screen khulte parini.")
        }
    }
}
