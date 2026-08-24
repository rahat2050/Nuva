package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OFFLINE PARSER tests — the local mirror of the backend's deterministic
 * fallback parser (§2.21): simple, low-risk commands keep working with no
 * network; everything else honestly refuses.
 */
class CommandParserTest {

    @Test
    fun `opens youtube with or without the wake word`() {
        val withWake = CommandParser.parse("Nuva YouTube open koro.")
        assertNotNull(withWake)
        assertEquals(NuvaIntent.OPEN_APP, withWake!!.intent)
        assertEquals("youtube", (withWake.action as NuvaAction.OpenApp).app)

        val withoutWake = CommandParser.parse("youtube open koro")
        assertNotNull(withoutWake)
        assertEquals(NuvaIntent.OPEN_APP, withoutWake!!.intent)
    }

    @Test
    fun `bangla open command works`() {
        val decision = CommandParser.parse("নুভা ইউটিউব খোলো")
        assertNotNull(decision)
        assertEquals("youtube", (decision!!.action as NuvaAction.OpenApp).app)
    }

    @Test
    fun `home and back work`() {
        assertEquals(NuvaIntent.GO_HOME, CommandParser.parse("Nuva home e jao")!!.intent)
        assertEquals(NuvaIntent.GO_BACK, CommandParser.parse("go back")!!.intent)
        assertEquals(NuvaIntent.GO_BACK, CommandParser.parse("নুভা পিছনে যাও")!!.intent)
    }

    @Test
    fun `timers parse minutes into seconds`() {
        val decision = CommandParser.parse("Nuva 10 minute er timer lagao")
        assertNotNull(decision)
        val timer = decision!!.action as NuvaAction.SetTimer
        assertEquals(600L, timer.durationSeconds)
        assertEquals(NuvaRisk.LOW, decision.risk)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun `offline results are always low risk and never need confirmation`() {
        val decision = CommandParser.parse("Nuva WhatsApp khulo")
        assertNotNull(decision)
        assertEquals(NuvaRisk.LOW, decision!!.risk)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun `complex commands honestly refuse offline`() {
        assertNull(CommandParser.parse("Nuva Rahim ke WhatsApp e message pathao je ami ashchi"))
        assertNull(CommandParser.parse("Nuva bkash diye 5000 taka pathao"))
        assertNull(CommandParser.parse("Nuva amake ekta kobita likhe dao"))
        assertNull(CommandParser.parse(""))
    }

    @Test
    fun `security policy blocks credential memory keys`() {
        assertTrue(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("preferred_language"))
        assertTrue(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("favourite_apps"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("password"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("gmail_api_key"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("otp_code"))
    }

    @Test
    fun `security policy url guard matches validator`() {
        assertTrue(com.nuva.assistant.core.security.SecurityPolicy.isUrlAllowed("https://nuva.dev"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isUrlAllowed("javascript:alert(1)"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isUrlAllowed("file:///etc/hosts"))
    }

    @Test
    fun `confirmation policy has no off switch for risk`() {
        assertTrue(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.MEDIUM, confirmationModeAlways = false),
        )
        assertTrue(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.HIGH, confirmationModeAlways = false),
        )
        assertTrue(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.LOW, confirmationModeAlways = true),
        )
        assertFalse(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.LOW, confirmationModeAlways = false),
        )
    }
}
