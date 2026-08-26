package com.nuva.assistant.command

/**
 * Conversational context memory (v1.4) — lets a short workflow work without
 * repeating everything:
 *
 *   "WhatsApp kholo"            → lastApp = whatsapp
 *   "Rohim-er chat kholo"       → lastContact = Rohim (inside WhatsApp)
 *   "ওকে বলো আমি কাল আসব না"     → "ওকে" resolves to Rohim, app stays WhatsApp
 *
 * Rules:
 *  * SHORT TTL (default 5 minutes) — context expires safely.
 *  * Only the last app/contact NAME/phone live here — never message bodies,
 *    never credentials, nothing is persisted to disk (RAM only, cleared with
 *    the process).
 *  * The contact is cleared right after a message is sent so a later "ওকে
 *    বলো" cannot silently target a stale recipient.
 *  * Pure Kotlin — fully unit-testable with an injected clock.
 */
object ContextMemory {

    /** Words that mean "the person we were just talking about". */
    val PRONOUN_CONTACTS = listOf(
        "oke", "o ke", "take", "tar ke", "takei", "tarke",
        "ওকে", "ওর কে", "তাকে", "তার কে", "একে",
    )

    fun isContactPronoun(raw: String): Boolean {
        val t = raw.trim().lowercase()
        return PRONOUN_CONTACTS.any { it == t }
    }

    data class ContextState(
        val lastApp: String?,
        val lastMessagingApp: String?,
        val lastContact: String?,
        val lastContactPhone: String?,
        val updatedAt: Long,
    )

    /**
     * One conversation session. NOT thread-safe by design (commands are
     * sequential); @Volatile keeps the UI reader correct.
     */
    class Session(
        private val ttlMs: Long = DEFAULT_TTL_MS,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        @Volatile
        private var state: ContextState? = null

        val active: ContextState?
            get() {
                val current = state ?: return null
                if (clock() - current.updatedAt > ttlMs) {
                    state = null // expired — forget everything
                    return null
                }
                return current
            }

        val lastApp: String? get() = active?.lastApp
        val lastMessagingApp: String? get() = active?.lastMessagingApp
        val lastContact: String? get() = active?.lastContact
        val lastContactPhone: String? get() = active?.lastContactPhone

        fun onAppOpened(app: String, messaging: Boolean) {
            val now = clock()
            val previous = active
            state = ContextState(
                lastApp = app,
                lastMessagingApp = if (messaging) app else previous?.lastMessagingApp,
                lastContact = previous?.lastContact,
                lastContactPhone = previous?.lastContactPhone,
                updatedAt = now,
            )
        }

        fun onChatOpened(app: String, contact: String, phone: String?) {
            state = ContextState(
                lastApp = app,
                lastMessagingApp = app,
                lastContact = contact,
                lastContactPhone = phone ?: state?.lastContactPhone,
                updatedAt = clock(),
            )
        }

        /** After a send the contact is dropped — never reuse it silently. */
        fun onMessageSent() {
            val previous = active ?: return
            state = previous.copy(lastContact = null, lastContactPhone = null, updatedAt = clock())
        }

        /**
         * Resolves a contact reference: pronouns map to the last contact,
         * real names pass through untouched (caller resolves them via
         * Contacts). Returns null when there is nothing to fall back on.
         */
        fun resolveContactReference(raw: String): String? {
            val name = raw.trim()
            if (!isContactPronoun(name)) return name
            return active?.lastContact
        }

        fun clear() {
            state = null
        }

        companion object {
            const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        }
    }
}
