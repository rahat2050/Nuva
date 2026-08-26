package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversational context memory (v1.4) — pure JVM, injected clock. */
class ContextMemoryTest {

    private var now = 0L
    private fun session(ttl: Long = ContextMemory.Session.DEFAULT_TTL_MS) =
        ContextMemory.Session(ttlMs = ttl, clock = { now })

    @Test
    fun `pronouns resolve to the last contact`() {
        val s = session()
        assertNull(s.resolveContactReference("Rohim")) // real names pass through

        s.onChatOpened("whatsapp", "Rohim", "01712345678")
        assertEquals("Rohim", s.resolveContactReference("oke"))
        assertEquals("Rohim", s.resolveContactReference("ওকে"))
        assertEquals("Rohim", s.resolveContactReference("tar ke"))
    }

    @Test
    fun `pronoun without context resolves to nothing`() {
        val s = session()
        assertNull(s.resolveContactReference("oke"))
        assertNull(s.resolveContactReference("তাকে"))
    }

    @Test
    fun `context expires safely after the ttl`() {
        val s = session(ttl = 1000)
        s.onAppOpened("whatsapp", messaging = true)
        now = 500
        assertEquals("whatsapp", s.lastApp)
        now = 1001
        assertNull(s.lastApp)
        assertNull(s.resolveContactReference("oke"))
    }

    @Test
    fun `sending a message clears the contact so it is never reused silently`() {
        val s = session()
        s.onChatOpened("whatsapp", "Rohim", "01712345678")
        s.onMessageSent()
        assertEquals("whatsapp", s.lastMessagingApp) // app stays for follow-ups
        assertNull(s.lastContact)
        assertNull(s.resolveContactReference("oke"))
    }

    @Test
    fun `opening a non messaging app keeps the messaging context`() {
        val s = session()
        s.onChatOpened("whatsapp", "Rohim", null)
        s.onAppOpened("chrome", messaging = false)
        assertEquals("chrome", s.lastApp)
        assertEquals("whatsapp", s.lastMessagingApp)
        assertEquals("Rohim", s.lastContact)
    }

    @Test
    fun `pronoun detection list covers bangla and banglish`() {
        assertTrue(ContextMemory.isContactPronoun("ওকে"))
        assertTrue(ContextMemory.isContactPronoun("Oke"))
        assertTrue(ContextMemory.isContactPronoun("take"))
        assertFalse(ContextMemory.isContactPronoun("Rohim"))
        assertFalse(ContextMemory.isContactPronoun("sakib"))
    }

    @Test
    fun `clear forgets everything`() {
        val s = session()
        s.onChatOpened("whatsapp", "Rohim", null)
        s.clear()
        assertNull(s.active)
    }
}
