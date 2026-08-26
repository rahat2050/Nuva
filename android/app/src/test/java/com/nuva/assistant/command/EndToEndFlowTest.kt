package com.nuva.assistant.command

import com.nuva.assistant.core.security.SensitiveAppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPTANCE FLOW (v1.4, product spec §17) — the whole conversational
 * concept, verified end to end at the pure-command layer (everything below
 * the Android boundary: parse → validate → context → resolve references →
 * confirm-gate decisions). Device-bound execution (intents, accessibility)
 * is covered by the executor + manual test checklist in docs.
 *
 *   "Hey Nuva, WhatsApp kholo ar Rohim-ke bolo ami agamikal asbona."
 *   "Rohim-er chat kholo."          (context: WhatsApp)
 *   "ওকে বলো আমি কাল আসব না।"        ("ওকে" = Rohim)
 */
class EndToEndFlowTest {

    private var now = 0L
    private val context = ContextMemory.Session(clock = { now })

    @Test
    fun `golden compound command becomes a validated two step plan`() {
        val plan = CommandParser.parseCompound("Hey Nuva, WhatsApp kholo ar Rohim-ke bolo ami agamikal asbona")
        assertNotNull(plan)
        assertEquals(2, plan!!.size)

        // Step 1: open WhatsApp (LOW, no confirmation needed).
        val open = plan[0]
        assertEquals(NuvaIntent.OPEN_APP, open.intent)
        assertEquals(NuvaRisk.LOW, open.risk)
        assertTrue(!open.requiresConfirmation)

        // Step 2: message Rohim — MUST require confirmation.
        val send = plan[1]
        assertEquals(NuvaIntent.SEND_MESSAGE, send.intent)
        assertEquals(NuvaRisk.MEDIUM, send.risk)
        assertTrue(send.requiresConfirmation)
        val action = send.action as NuvaAction.SendMessage
        assertEquals("rohim", action.contact.lowercase())
        assertEquals("ami agamikal asbona", action.message)

        // Every plan step survives strict serialization round-trip (the same
        // validation used for parked pending actions).
        plan.forEach { step ->
            if (step.action != null) {
                assertEquals(step.action, ActionJson.decode(ActionJson.encode(step.action!!)))
            }
        }
    }

    @Test
    fun `follow up chat open works without repeating the app`() {
        context.onAppOpened("whatsapp", messaging = true)

        val decision = CommandParser.parse("Rohim-er chat kholo")
        assertNotNull(decision)
        val chat = decision!!.action as NuvaAction.OpenChat
        assertEquals(NuvaIntent.OPEN_CHAT, decision.intent)

        // No explicit app in the utterance → context messaging app wins.
        val mentionsWhatsApp = false
        val app = if (!mentionsWhatsApp) {
            MessagingApp.fromWire(context.lastMessagingApp ?: chat.app.wireName) ?: chat.app
        } else {
            chat.app
        }
        assertEquals(MessagingApp.WHATSAPP, app)
        assertEquals("rohim", chat.contact.lowercase())

        // Simulate the chat opening succeeded → context now knows Rohim.
        context.onChatOpened("whatsapp", "Rohim", "01712345678")
        assertEquals("Rohim", context.lastContact)
    }

    @Test
    fun `pronoun follow up resolves to the context contact and still confirms`() {
        context.onChatOpened("whatsapp", "Rohim", "01712345678")

        val decision = CommandParser.parse("ওকে বলো আমি কাল আসব না")
        assertNotNull(decision)
        val send = decision!!.action as NuvaAction.SendMessage
        assertTrue(decision.requiresConfirmation)

        // Pronoun → context contact (executor's applyContext logic, mirrored).
        val resolved = context.resolveContactReference(send.contact)
        assertEquals("Rohim", resolved)
        assertEquals("আমি কাল আসব না", send.message)

        // Bangla pronoun in Banglish sentence works too.
        val banglish = CommandParser.parse("oke bolo ami 10 minute e ashi")
        val resolvedB = context.resolveContactReference((banglish!!.action as NuvaAction.SendMessage).contact)
        assertEquals("Rohim", resolvedB)
    }

    @Test
    fun `pronoun with no context asks instead of guessing`() {
        val fresh = ContextMemory.Session(clock = { now })
        val decision = CommandParser.parse("ওকে বলো আমি কাল আসব না")
        val send = decision!!.action as NuvaAction.SendMessage
        assertTrue(ContextMemory.isContactPronoun(send.contact))
        assertNull(fresh.resolveContactReference(send.contact)) // executor fails gracefully
    }

    @Test
    fun `financial transaction anywhere in the flow is refused without confirmation`() {
        val plan = CommandParser.parseCompound("WhatsApp kholo ar Rohim-ke 500 taka pathao")
        assertTrue(plan!![0].unsupported || plan[0].risk == NuvaRisk.HIGH)

        val refusal = CommandParser.parse("bkash e 5000 taka send koro")
        assertTrue(refusal!!.unsupported)
        assertEquals(NuvaRisk.HIGH, refusal.risk)
        assertTrue(refusal.speech.contains(SensitiveAppPolicy.TRANSACTION_REFUSAL))
        // Transactions are NEVER offered a confirmation — they are refused.
        assertTrue(!refusal.requiresConfirmation)
    }

    @Test
    fun `mixed bangla english compound with english connector works`() {
        val plan = CommandParser.parseCompound("Hey Nuva, Chrome kholo and search koro Bangladesh weather")
        assertEquals(2, plan!!.size)
        assertEquals("chrome", (plan[0].action as NuvaAction.OpenApp).app)
        assertEquals("bangladesh weather", (plan[1].action as NuvaAction.SearchWeb).query)
    }

    @Test
    fun `long natural search queries survive intact`() {
        val plan = CommandParser.parseCompound(
            "Hey Nuva, YouTube kholo ar latest Samsung phone review search koro",
        )
        assertEquals(2, plan!!.size)
        val play = plan[1].action as NuvaAction.PlayMedia
        assertEquals("latest samsung phone review", play.query)
    }

    @Test
    fun `context expires so stale pronouns never fire`() {
        context.onChatOpened("whatsapp", "Rohim", null)
        now = ContextMemory.Session.DEFAULT_TTL_MS + 1
        assertNull(context.resolveContactReference("oke"))
    }
}
