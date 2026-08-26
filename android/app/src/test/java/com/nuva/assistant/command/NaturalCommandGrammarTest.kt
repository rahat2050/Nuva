package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalCommandGrammarTest {

    @Test
    fun `grammar represents more than ten thousand unique concrete forms`() {
        assertEquals(50, NaturalCommandGrammar.patterns.size)
        assertTrue(NaturalCommandGrammar.patterns.all { it.aliases.size == 5 })
        assertEquals(12_250, NaturalCommandGrammar.supportedStaticFormCount)
        val forms = NaturalCommandGrammar.generatedStaticForms().toList()
        assertEquals(12_250, forms.size)
        assertEquals(12_250, forms.toSet().size)
    }

    @Test
    fun `every generated form canonicalizes to its command family`() {
        NaturalCommandGrammar.patterns.forEach { pattern ->
            pattern.aliases.forEach { alias ->
                val probe = "nuva please $alias taratari"
                assertEquals(pattern.id, pattern.canonical, NaturalCommandGrammar.canonicalStatic(probe))
            }
        }
    }

    @Test
    fun `all fifty canonical families reach a typed parser decision`() {
        NaturalCommandGrammar.patterns.forEach { pattern ->
            assertNotNull(pattern.id, CommandParser.parse(pattern.canonical))
        }
    }

    @Test
    fun `representative polite asr and mixed language forms route correctly`() {
        assertEquals(NuvaIntent.GO_HOME, CommandParser.parse("nuva please go to home taratari")!!.intent)
        assertEquals(NuvaIntent.OPEN_NOTIFICATION_APP, CommandParser.parse("please open notification app please")!!.intent)
        assertEquals(NuvaIntent.DEVICE_STATUS, CommandParser.parse("nuva please battery percentage bolo ekhon")!!.intent)
        assertEquals(NuvaIntent.SEARCH_WEB, CommandParser.parse("hey nuva ektu current aqi bolo bolen")!!.intent)
        assertEquals(NuvaIntent.CAMERA, CommandParser.parse("doya kore picture tolar screen dao ekhoni")!!.intent)
    }

    @Test
    fun `dynamic command rewrites preserve slots`() {
        val app = CommandParser.parse("please youtube open kore dao please")!!.action as NuvaAction.OpenApp
        assertEquals("youtube", app.app)

        val search = CommandParser.parse("doya kore dhaka weather search kore dao")!!.action as NuvaAction.SearchWeb
        assertTrue(search.query.contains("dhaka weather"))

        val call = CommandParser.parse("nuva rahim ke phone lagiye dao")!!.action as NuvaAction.CallContact
        assertEquals("rahim", call.contact.lowercase())
    }

    @Test
    fun `canonicalized security typos are blocked before broad web fallback`() {
        val payment = CommandParser.parse("bkash paymnt koro")
        assertTrue(payment!!.unsupported)
        assertEquals(NuvaRisk.HIGH, payment.risk)

        val password = CommandParser.parse("pasword reset kivabe")
        assertTrue(password!!.unsupported)
        assertNull(password.action)
    }

    @Test
    fun `longer connector vocabulary creates ordered multi action plans`() {
        val plan = CommandParser.parseCompound(
            "please home screen e jao and then previous screen e jao erpor recent screen kholo",
        )
        assertEquals(3, plan!!.size)
        assertEquals(NuvaIntent.GO_HOME, plan[0].intent)
        assertEquals(NuvaIntent.GO_BACK, plan[1].intent)
        assertEquals(NuvaIntent.SHOW_RECENTS, plan[2].intent)
    }
}
