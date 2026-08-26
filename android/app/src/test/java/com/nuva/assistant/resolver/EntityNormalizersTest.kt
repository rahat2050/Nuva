package com.nuva.assistant.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure entity normalization (v1.4) — no device needed. */
class EntityNormalizersTest {

    @Test
    fun `contact candidates go full phrase, minus kinship, bare name`() {
        assertEquals(
            listOf("Amar bhai Sakib", "bhai sakib", "Sakib"),
            EntityNormalizers.buildContactCandidates("Amar bhai Sakib"),
        )
        // A clean name collapses to a single candidate — no hard-coded names anywhere.
        assertEquals(listOf("Rohim"), EntityNormalizers.buildContactCandidates("Rohim"))
        assertEquals(listOf("রাহাত আহমেদ", "আহমেদ"), EntityNormalizers.buildContactCandidates("রাহাত আহমেদ"))
    }

    @Test
    fun `hyphenated kinship phrases produce clean candidates`() {
        val candidates = EntityNormalizers.buildContactCandidates("amar-bhai Sakib")
        assertEquals("Amar bhai Sakib", candidates.first())
        assertEquals("Sakib", candidates.last())
    }

    @Test
    fun `app names are normalized for label matching`() {
        assertEquals("whatsapp", EntityNormalizers.normalizeAppName("The WhatsApp App"))
        assertEquals("google maps", EntityNormalizers.normalizeAppName("  Google   Maps ta "))
    }

    @Test
    fun `urls are extracted from mixed text`() {
        assertEquals("https://nuva.dev", EntityNormalizers.extractUrl("check https://nuva.dev please"))
        assertEquals("http://example.com/page", EntityNormalizers.extractUrl("http://example.com/page."))
        assertNull(EntityNormalizers.extractUrl("no link here"))
    }
}
