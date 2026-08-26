package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import com.nuva.assistant.command.MessagingApp

/**
 * Messaging plugin registry (v1.2) — support TIERS, honestly labelled:
 *
 *  FULL    — WhatsApp (wa.me deep link + composer automation, sends only
 *            after the user confirms) and SMS (platform send after
 *            confirmation, pre-filled compose as fallback).
 *
 *  COMPOSE — Telegram, Messenger, Signal, Viber, IMO: NUVA opens the app with
 *            the message pre-filled via a share intent (reliable on every
 *            version), the user picks the chat and taps Send themselves.
 *            Nothing is ever sent without the user's own tap. Composer-screen
 *            automation in these apps is deliberately NOT attempted — their
 *            layouts are not stable enough, and a wrong tap could message the
 *            wrong chat.
 */
object MessagingRegistry {

    enum class Tier { FULL, COMPOSE }

    data class Support(
        val app: MessagingApp,
        val tier: Tier,
        /** Bangla description for the feature screen. */
        val description: String,
    )

    val fullApps: Set<MessagingApp> = setOf(MessagingApp.WHATSAPP, MessagingApp.SMS)

    fun tierOf(app: MessagingApp): Tier =
        if (app in fullApps) Tier.FULL else Tier.COMPOSE

    /** Package for the COMPOSE-tier share intent. */
    fun packageOf(app: MessagingApp): String? = when (app) {
        MessagingApp.TELEGRAM -> "org.telegram.messenger"
        MessagingApp.MESSENGER -> "com.facebook.orca"
        MessagingApp.SIGNAL -> "org.thoughtcrime.securesms"
        MessagingApp.VIBER -> "com.viber.voip"
        MessagingApp.IMO -> "com.imo.android.imoim"
        else -> null
    }

    /**
     * COMPOSE tier: opens [app] with [message] pre-filled via ACTION_SEND.
     * Returns false when the app is not installed.
     */
    fun openWithPrefilledMessage(context: Context, app: MessagingApp, message: String): Boolean {
        val pkg = packageOf(app) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message.take(2000))
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (err: Exception) {
            // App missing or doesn't accept direct share — just launch it.
            AppLauncher.openApp(context, app.wireName, pkg) is AppLauncher.LaunchResult.Success
        }
    }

    /** Full catalogue for the supported/unsupported feature screen. */
    fun catalogue(): List<Support> = MessagingApp.entries.map { app ->
        Support(
            app = app,
            tier = tierOf(app),
            description = when (tierOf(app)) {
                Tier.FULL -> if (app == MessagingApp.WHATSAPP) {
                    "কনফার্মেশনের পর চ্যাট খুলে মেসেজ টাইপ করে পাঠায়"
                } else {
                    "কনফার্মেশনের পর সরাসরি এসএমএস পাঠায় (পারমিশন না থাকলে লেখা বসিয়ে স্ক্রিন খোলে)"
                }

                Tier.COMPOSE -> "মেসেজ লেখা বসিয়ে অ্যাপ খোলে — Send আপনি চাপবেন"
            },
        )
    }
}
