package com.nuva.assistant.automation

import com.nuva.assistant.command.MessagingApp

/**
 * Messaging plugin registry (v1.1).
 *
 * NUVA only automates what it can do RELIABLY and SAFELY:
 *  * WhatsApp — wa.me deep link + accessibility composer flow (verified).
 *  * SMS — platform intent / SmsManager (verified).
 *
 * Telegram / Messenger / IMO / Viber / Signal are deliberately NOT automated
 * yet: their composer screens are not stable enough for accessibility flows,
 * and a wrong tap could send a message to the wrong chat. The registry lets a
 * future plugin be added per-app (one object per app implementing the same
 * contract) without touching the executor.
 */
object MessagingRegistry {

    data class Support(
        val app: MessagingApp,
        val supported: Boolean,
        /** Bangla/Banglish reason shown when unsupported. */
        val reason: String?,
    )

    val supportedApps: Set<MessagingApp> = setOf(MessagingApp.WHATSAPP, MessagingApp.SMS)

    fun isSupported(app: MessagingApp): Boolean = app in supportedApps

    fun unsupportedReason(app: MessagingApp): String =
        "${app.wireName} automation ekhon nirdhrosto bhabe support kori na — " +
            "composer screen NUVA r nirdeshoner moto kaj kore na. Ekhon WhatsApp o SMS kaj kore."

    /** Full catalogue for the supported/unsupported feature screen. */
    fun catalogue(): List<Support> = MessagingApp.entries.map { app ->
        if (isSupported(app)) Support(app, true, null) else Support(app, false, unsupportedReason(app))
    }
}
