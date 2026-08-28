package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.EmergencyService

/** Bangladesh emergency dialer handoff. It opens the dialer and never places the call. */
object EmergencyHandoff {
    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    fun openDialer(context: Context, service: EmergencyService): Result = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${service.dialNumber}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Result.Opened
    } catch (_: Exception) {
        Result.Failed("Emergency dialer khulte parini.")
    }
}
