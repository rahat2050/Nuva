package com.nuva.assistant.resolver

import android.content.Context
import com.nuva.assistant.automation.AppLauncher
import com.nuva.assistant.command.NuvaDateTimeParser
import com.nuva.assistant.contacts.ContactResolver

/**
 * Reusable entity resolution (v1.4) — ONE place that turns raw spoken
 * fragments into resolved entities:
 *
 *   "Rohim-ke"      → CONTACT   (ContactsProvider; multi-match ⇒ ASK, never guess)
 *   "WhatsApp-e"    → APP       (installed apps by label + alias hints)
 *   "nuva.dev"      → WEBSITE/URL
 *   "kal shokal 7"  → DATE/TIME (NuvaDateTimeParser)
 *
 * The pure normalizers are shared with [ContactResolver] and unit-tested;
 * the Android-backed lookups delegate to the existing, proven resolvers.
 */
object EntityNormalizers {

    /** Relationship/filler words people say before a name. */
    val KINSHIP_WORDS: List<String> = listOf(
        "bhai", "vai", "bhaya", "brother", "apa", "apu", "sister", "dada", "didi",
        "chacha", "chachi", "mama", "mami", "kaka", "kaki", "abba", "amma", "baba", "ma",
        "dad", "mom", "friend", "bondhu", "amar", "my", "the", "সাহেব",
        "ভাই", "আপা", "আপু", "বোন", "দাদা", "দিদি", "চাচা", "চাচী", "মামা", "মামী", "বাবা", "মা",
        "আমার", "বন্ধু",
    )

    /**
     * Contact search candidates for ANY spoken name, most specific first:
     * the full phrase → the phrase minus kinship/filler words → the bare
     * last token. Nothing name-specific is ever hard-coded.
     */
    fun buildContactCandidates(name: String): List<String> = buildList {
        val full = name.trim()
        if (full.isNotEmpty()) add(full)
        val withoutKinship = full.lowercase()
            .split(" ", "-")
            .filter { it.isNotBlank() && it.lowercase() !in KINSHIP_WORDS }
            .joinToString(" ")
        if (withoutKinship.isNotBlank() && !contains(withoutKinship)) add(withoutKinship)
        val lastWord = full.split(" ", "-").lastOrNull { it.isNotBlank() }
        if (lastWord != null && !contains(lastWord)) add(lastWord)
    }

    /** " the WhatsApp app " → "whatsapp" for app-label matching. */
    fun normalizeAppName(raw: String): String =
        raw.lowercase().replace(Regex("""\b(the|app|ta|please)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    /** Extracts a bare http(s) URL from mixed text, null when none. */
    fun extractUrl(text: String): String? {
        val m = Regex("""\bhttps?://[^\s]+""").find(text) ?: return null
        return m.value.trimEnd('.', ',', '!', '?')
    }

    /**
     * LOCATION entity (v1.4): any place query becomes a Google Maps search
     * URL — opened in Maps/browser by Android's chooser. No location
     * permission is needed or asked for.
     */
    fun mapsSearchUrl(query: String): String =
        "https://www.google.com/maps/search/?api=1&query=" +
            java.net.URLEncoder.encode(query.trim(), "UTF-8")
}

/**
 * The resolver facade used by command flows. Android-backed; the pure parts
 * live in [EntityNormalizers] so they are JVM-testable without a device.
 */
class EntityResolver(
    private val contextProvider: () -> Context,
    private val contacts: ContactResolver = ContactResolver(contextProvider),
) {

    fun resolveContact(name: String): ContactResolver.Resolution = contacts.resolve(name)

    fun resolveApp(name: String): AppLauncher.InstalledApp? =
        AppLauncher.findInstalledApp(contextProvider(), EntityNormalizers.normalizeAppName(name))

    fun resolveUrl(text: String): String? = EntityNormalizers.extractUrl(text)

    fun resolveWhen(text: String): NuvaDateTimeParser.ParsedWhen = NuvaDateTimeParser.parseWhen(text)
}
