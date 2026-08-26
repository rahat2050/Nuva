package com.nuva.assistant.automation

import android.content.Context
import com.nuva.assistant.command.MessagingApp

/**
 * AppCapabilityRegistry (v1.5, Phase 2) — scalable, DATA-DRIVEN description
 * of what NUVA can do in each app. Nothing here hardcodes user commands;
 * categories define capabilities once and every installed app of that
 * category inherits them. Unknown apps get an honest default: launch +
 * generic semantic automation only.
 *
 * Statuses used here feed the in-app support screen and the audit doc:
 *  FULL — reliable dedicated flow exists
 *  GENERIC — works through semantic accessibility actions only
 *  LIMITED — app-level assist (prefill/open), user completes manually
 *  NONE — not possible for third-party apps on Android
 */
object AppCapabilityRegistry {

    enum class Support { FULL, GENERIC, LIMITED, NONE }

    data class Capability(
        val action: String,
        val support: Support,
        /** How the capability is realized. */
        val strategy: String,
        /** Honest reason when support != FULL. */
        val reason: String? = null,
    )

    data class AppCategory(
        val id: String,
        val packages: Set<String>,
        val capabilities: List<Capability>,
    )

    /** Messaging: WhatsApp/SMS full; others prefill-only. */
    val MESSAGING = AppCategory(
        id = "messaging",
        packages = setOf(
            "com.whatsapp",
            "com.android.mms", "com.google.android.apps.messaging",
            "org.telegram.messenger",
            "com.facebook.orca",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
            "com.imo.android.imoim",
        ),
        capabilities = listOf(
            Capability("launch", Support.FULL, "launch intent"),
            Capability("open_chat", Support.GENERIC, "wa.me deep link (WhatsApp); app-open otherwise"),
            Capability("send_message", Support.LIMITED, "wa.me + composer automation", "শুধু WhatsApp ও SMS — বাকিগুলোতে message লেখা বসিয়ে দেয়, Send আপনি চাপবেন"),
            Capability("screen_automation", Support.NONE, "—", "composer screens unstable; wrong-chat risk"),
        ),
    )

    /** Media players: transport control via the active MediaSession. */
    val MEDIA = AppCategory(
        id = "media",
        packages = setOf(
            "com.google.android.youtube",
            "com.spotify.music",
            "com.amazon.mp3",
            "com.google.android.apps.youtube.music",
            "com.mxtech.videoplayer.media",
        ),
        capabilities = listOf(
            Capability("launch", Support.FULL, "launch intent"),
            Capability("search", Support.FULL, "app search intent / deep link"),
            Capability("play/pause/next/previous", Support.GENERIC, "active MediaSession transport"),
            Capability("in-app navigation", Support.GENERIC, "semantic accessibility actions"),
        ),
    )

    /** Browsers: open URL + search; DOM-level automation out of scope. */
    val BROWSER = AppCategory(
        id = "browser",
        packages = setOf(
            "com.android.chrome",
            "com.mozilla.firefox",
            "com.opera.browser",
            "com.brave.browser",
        ),
        capabilities = listOf(
            Capability("launch", Support.FULL, "launch intent"),
            Capability("open_url", Support.FULL, "ACTION_VIEW"),
            Capability("web_search", Support.FULL, "search URL"),
            Capability("page_interaction", Support.GENERIC, "semantic accessibility actions on rendered page"),
        ),
    )

    /** Financial apps (LEVEL 1/2/3 policy). */
    val FINANCIAL = AppCategory(
        id = "financial",
        packages = setOf("com.bKash.customerapp", "com.konasl.mobileapp", "bd.com.dbbl.mobilebanking"),
        capabilities = listOf(
            Capability("launch", Support.FULL, "launch intent (LEVEL 1 allowed)"),
            Capability("scroll/navigation", Support.GENERIC, "accessibility scroll actions"),
            Capability("screen_read", Support.NONE, "—", "LEVEL 2 fail-safe: OTP/PIN/balance risk"),
            Capability("tap/type", Support.NONE, "—", "LEVEL 3: a tap can confirm a transaction"),
            Capability("transactions", Support.NONE, "—", "LEVEL 3: financial automation always refused"),
        ),
    )

    val CATEGORIES = listOf(MESSAGING, MEDIA, BROWSER, FINANCIAL)

    data class AppCapabilities(
        val packageName: String,
        val category: AppCategory?,
        val known: Boolean,
        val capabilities: List<Capability>,
    )

    /** Resolves capabilities for ANY package — known or completely unknown. */
    fun capabilitiesFor(packageName: String?): AppCapabilities {
        val pkg = packageName?.lowercase().orEmpty()
        val category = CATEGORIES.firstOrNull { cat ->
            cat.packages.any { known -> pkg == known || pkg.endsWith(".$known") }
        }
        return when {
            category != null -> AppCapabilities(pkg, category, known = true, capabilities = category.capabilities)
            // Unknown app: launch + honest generic automation, nothing assumed.
            else -> AppCapabilities(
                pkg,
                null,
                known = false,
                capabilities = listOf(
                    Capability("launch", Support.FULL, "launch intent"),
                    Capability("generic_tap/type/scroll", Support.GENERIC, "semantic accessibility actions"),
                    Capability("screen_read", Support.GENERIC, "password fields skipped, OTP redacted"),
                ),
            )
        }
    }

    /** Bangla one-liner per capability support level (support screen / audit). */
    fun describe(support: Support): String = when (support) {
        Support.FULL -> "সম্পূর্ণ কাজ করে"
        Support.GENERIC -> "স্ক্রিনের বাটন/লেখা দেখে নির্দিষ্ট লক্ষ্যে কাজ করে"
        Support.LIMITED -> "আংশিক — বাকিটা আপনি করবেন"
        Support.NONE -> "নীতি/Android-এর কারণে সম্ভব নয়"
    }

    /** Convenience: messaging tier from the registry view. */
    fun messagingTierOf(app: MessagingApp): Support = when (app) {
        MessagingApp.WHATSAPP, MessagingApp.SMS -> Support.FULL
        else -> Support.LIMITED
    }

    /** Catalogue of installed apps with their capabilities (support screen). */
    fun installedCatalogue(context: Context): List<AppCapabilities> =
        AppLauncher.installedLaunchableApps(context).map { capabilitiesFor(it.packageName) }
}
