package com.nuva.assistant.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Contact search + phone-number resolution (v1.1).
 *
 * Requires READ_CONTACTS — requested only when the user first runs a
 * call/message command, never at startup (permission-onboarding policy §28).
 * When several contacts match a spoken name, the UI asks the user to pick
 * one; NUVA never guesses.
 */
class ContactResolver(private val contextProvider: () -> Context) {

    data class ContactMatch(
        val contactId: Long,
        val displayName: String,
        val phone: String,
        /** Extra phone numbers for the same contact (shown when picking). */
        val label: String? = null,
    )

    sealed interface Resolution {
        data class Single(val match: ContactMatch) : Resolution
        data class Ambiguous(val matches: List<ContactMatch>) : Resolution
        data object NotFound : Resolution
        data object NoPermission : Resolution
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(contextProvider(), Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Resolves a spoken/written contact name. Bangla and English names both
     * work — matching is case-insensitive on the display name and phone number.
     */
    fun resolve(name: String): Resolution {
        if (!hasPermission()) return Resolution.NoPermission
        val matches = search(name)
        return when {
            matches.isEmpty() -> Resolution.NotFound
            matches.size == 1 -> Resolution.Single(matches.first())
            else -> {
                // Same person with several numbers? Collapse exact duplicates first.
                val byPerson = matches.distinctBy { it.contactId.toString() + it.phone }
                if (byPerson.size == 1) Resolution.Single(byPerson.first()) else Resolution.Ambiguous(byPerson.take(6))
            }
        }
    }

    /** Free-text search over ContactsContract — used by the pick-a-contact UI. */
    fun search(query: String, limit: Int = 6): List<ContactMatch> {
        if (query.isBlank()) return emptyList()
        val context = contextProvider()
        if (!hasPermission()) return emptyList()
        val results = mutableListOf<ContactMatch>()
        runCatching {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon()
                .appendPath(query)
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit * 2) {
                    val contactId = cursor.getLong(0)
                    val displayName = cursor.getString(1) ?: continue
                    val phone = cursor.getString(2) ?: continue
                    results.add(ContactMatch(contactId, displayName, phone.normalizePhone()))
                }
            }
        }
        // Exact-name hits first, then startsWith, then the provider order.
        val q = query.trim().lowercase()
        return results
            .distinctBy { it.contactId.toString() + it.phone }
            .sortedWith(
                compareByDescending<ContactMatch> { it.displayName.lowercase() == q }
                    .thenByDescending { it.displayName.lowercase().startsWith(q) },
            )
            .take(limit)
    }
}

/** Keeps +88/01-style numbers comparable. */
fun String.normalizePhone(): String {
    val digits = filter { it.isDigit() }
    return when {
        digits.startsWith("8801") && digits.length == 13 -> "0" + digits.substring(3)
        digits.startsWith("880") && digits.length == 12 -> "0" + digits.substring(3)
        else -> this.trim()
    }
}
