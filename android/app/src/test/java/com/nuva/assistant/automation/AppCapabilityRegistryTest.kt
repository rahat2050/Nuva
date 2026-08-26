package com.nuva.assistant.automation

import com.nuva.assistant.command.MessagingApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppCapabilityRegistry (v1.5, Phase 2) — data-driven, no hardcoded commands. */
class AppCapabilityRegistryTest {

    @Test
    fun `known packages resolve to their category`() {
        val wa = AppCapabilityRegistry.capabilitiesFor("com.whatsapp")
        assertEquals("messaging", wa.category?.id)
        assertTrue(wa.known)

        val yt = AppCapabilityRegistry.capabilitiesFor("com.google.android.youtube")
        assertEquals("media", yt.category?.id)

        val bkash = AppCapabilityRegistry.capabilitiesFor("com.bKash.customerapp")
        assertEquals("financial", bkash.category?.id)
    }

    @Test
    fun `unknown apps get an honest generic default`() {
        val unknown = AppCapabilityRegistry.capabilitiesFor("com.somebody.randomapp")
        assertFalse(unknown.known)
        assertEquals(AppCapabilityRegistry.Support.FULL, unknown.capabilities.first { it.action == "launch" }.support)
        // Generic semantic automation declared, nothing assumed beyond it.
        assertTrue(unknown.capabilities.any { it.action.startsWith("generic") })
    }

    @Test
    fun `financial category policy is encoded in capabilities`() {
        val financial = AppCapabilityRegistry.capabilitiesFor("bd.com.dbbl.mobilebanking")
        assertTrue(financial.capabilities.first { it.action == "launch" }.support == AppCapabilityRegistry.Support.FULL)
        assertTrue(financial.capabilities.first { it.action == "transactions" }.support == AppCapabilityRegistry.Support.NONE)
        assertNotNull(financial.capabilities.first { it.action == "transactions" }.reason)
    }

    @Test
    fun `messaging tiers match the registry view`() {
        assertEquals(AppCapabilityRegistry.Support.FULL, AppCapabilityRegistry.messagingTierOf(MessagingApp.WHATSAPP))
        assertEquals(AppCapabilityRegistry.Support.FULL, AppCapabilityRegistry.messagingTierOf(MessagingApp.SMS))
        assertEquals(AppCapabilityRegistry.Support.LIMITED, AppCapabilityRegistry.messagingTierOf(MessagingApp.TELEGRAM))
        assertEquals(AppCapabilityRegistry.Support.LIMITED, AppCapabilityRegistry.messagingTierOf(MessagingApp.MESSENGER))
    }

    @Test
    fun `every capability carries a bangla description`() {
        AppCapabilityRegistry.Support.entries.forEach { support ->
            assertTrue(AppCapabilityRegistry.describe(support).isNotBlank())
        }
    }
}
