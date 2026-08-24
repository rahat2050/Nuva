package com.nuva.assistant.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCAL VALIDATOR tests — the client half of the whitelist. Mirrors the
 * backend's validate/risk suites: an invented or malformed action can never
 * become an executable NuvaAction, and risk can only be raised.
 */
class CommandValidatorTest {

    private fun action(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    private fun valid(action: JsonObject): NuvaAction =
        (CommandValidator.validateAction(action) as CommandValidator.ValidatedAction.Valid).action

    private fun invalid(action: JsonObject): List<String> =
        (CommandValidator.validateAction(action) as CommandValidator.ValidatedAction.Invalid).reasons

    // --- Whitelist -------------------------------------------------------------

    @Test
    fun `unknown action types are refused`() {
        val reasons = invalid(action { put("type", "RUN_SHELL") })
        assertTrue(reasons.first().contains("not in the NUVA registry"))
    }

    @Test
    fun `missing action is refused`() {
        assertTrue(CommandValidator.validateAction(null) is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `all 15 registered action types are recognised`() {
        val samples = mapOf(
            "OPEN_APP" to action { put("type", "OPEN_APP"); put("app", "youtube") },
            "CLOSE_APP" to action { put("type", "CLOSE_APP"); put("app", "youtube") },
            "GO_HOME" to action { put("type", "GO_HOME") },
            "GO_BACK" to action { put("type", "GO_BACK") },
            "TAP" to action {
                put("type", "TAP"); putJsonObject("target") { put("text", "OK") }
            },
            "TYPE_TEXT" to action { put("type", "TYPE_TEXT"); put("text", "hello") },
            "SWIPE" to action { put("type", "SWIPE"); put("direction", "up") },
            "SCROLL" to action { put("type", "SCROLL"); put("direction", "down") },
            "CALL_CONTACT" to action { put("type", "CALL_CONTACT"); put("contact", "Rahim") },
            "SEND_MESSAGE" to action {
                put("type", "SEND_MESSAGE"); put("app", "whatsapp"); put("contact", "Rahim"); put("message", "hi")
            },
            "SET_ALARM" to action { put("type", "SET_ALARM"); put("hour", 7); put("minute", 0) },
            "SET_TIMER" to action { put("type", "SET_TIMER"); put("duration_seconds", 600) },
            "OPEN_URL" to action { put("type", "OPEN_URL"); put("url", "https://example.com") },
            "PLAY_MEDIA" to action { put("type", "PLAY_MEDIA"); put("query", "cricket") },
            "READ_SCREEN" to action { put("type", "READ_SCREEN") },
        )
        assertEquals(15, samples.size)
        samples.forEach { (wire, json) ->
            val outcome = CommandValidator.validateAction(json)
            assertTrue("$wire should validate", outcome is CommandValidator.ValidatedAction.Valid)
            assertEquals(wire, valid(json).intent.wireName)
        }
    }

    // --- Field rules -----------------------------------------------------------

    @Test
    fun `OPEN_URL rejects javascript and file schemes`() {
        assertNull(CommandValidator.safeUrl("javascript:alert(1)"))
        assertNull(CommandValidator.safeUrl("file:///data/data/secret"))
        assertNull(CommandValidator.safeUrl("intent://evil"))
        assertEquals("https://example.com", CommandValidator.safeUrl("example.com"))
        assertEquals("http://example.com", CommandValidator.safeUrl("http://example.com"))
    }

    @Test
    fun `OPEN_URL rejects url-less host`() {
        assertFalse(CommandValidator.validateAction(action { put("type", "OPEN_URL"); put("url", "") }) is CommandValidator.ValidatedAction.Valid)
    }

    @Test
    fun `TAP requires a target or a point`() {
        val reasons = invalid(action { put("type", "TAP") })
        assertTrue(reasons.first().contains("target or a point"))
    }

    @Test
    fun `TAP coordinate points must be 0-1 fractions`() {
        val bad = action {
            put("type", "TAP")
            putJsonObject("point") { put("x", 1.5); put("y", 0.5) }
        }
        assertTrue(CommandValidator.validateAction(bad) is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `SWIPE requires direction or both points`() {
        assertTrue(
            CommandValidator.validateAction(action { put("type", "SWIPE") }) is CommandValidator.ValidatedAction.Invalid,
        )
    }

    @Test
    fun `SET_ALARM bounds are enforced`() {
        val badHour = action { put("type", "SET_ALARM"); put("hour", 24); put("minute", 0) }
        assertTrue(CommandValidator.validateAction(badHour) is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `SET_TIMER bounds are enforced`() {
        val tooLong = action { put("type", "SET_TIMER"); put("duration_seconds", 90000) }
        assertTrue(CommandValidator.validateAction(tooLong) is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `SEND_MESSAGE only allows supported apps`() {
        val badApp = action {
            put("type", "SEND_MESSAGE"); put("app", "wechat"); put("contact", "A"); put("message", "hi")
        }
        assertTrue(CommandValidator.validateAction(badApp) is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `package hints must look like android packages`() {
        val bad = action { put("type", "OPEN_APP"); put("app", "youtube"); put("package", "not a package!") }
        val action = valid(bad) as NuvaAction.OpenApp
        assertNull(action.pkg)
        assertEquals("youtube", action.app)
    }

    @Test
    fun `phone numbers are pattern checked`() {
        val bad = action {
            put("type", "CALL_CONTACT"); put("contact", "Rahim"); put("phone_number", "call home please")
        }
        val call = valid(bad) as NuvaAction.CallContact
        assertNull(call.phoneNumber)
    }

    // --- Risk ------------------------------------------------------------------

    @Test
    fun `send_message and call_contact are medium risk by default`() {
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SEND_MESSAGE))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CALL_CONTACT))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.OPEN_APP))
    }

    @Test
    fun `money keywords escalate risk to high`() {
        val risk = CommandValidator.recomputeRisk(
            action = null,
            unsupportedReasonText = "bkash diye taka pathano supported na",
            modelRisk = NuvaRisk.LOW,
        )
        assertEquals(NuvaRisk.HIGH, risk)
    }

    @Test
    fun `model risk can raise but never lower`() {
        val sendMessage = valid(
            action {
                put("type", "SEND_MESSAGE"); put("app", "sms"); put("contact", "Karim"); put("message", "hi")
            },
        )
        // LOW model claim cannot demote a MEDIUM baseline:
        assertEquals(
            NuvaRisk.MEDIUM,
            CommandValidator.recomputeRisk(sendMessage, "", NuvaRisk.LOW),
        )
        // HIGH model claim is kept:
        assertEquals(
            NuvaRisk.HIGH,
            CommandValidator.recomputeRisk(sendMessage, "", NuvaRisk.HIGH),
        )
    }

    @Test
    fun `confirmation is required for anything not low`() {
        assertFalse(CommandValidator.requiresConfirmation(NuvaRisk.LOW, modelAsked = false))
        assertTrue(CommandValidator.requiresConfirmation(NuvaRisk.LOW, modelAsked = true))
        assertTrue(CommandValidator.requiresConfirmation(NuvaRisk.MEDIUM, modelAsked = false))
        assertTrue(CommandValidator.requiresConfirmation(NuvaRisk.HIGH, modelAsked = false))
    }

    // --- ActionJson round-trip -------------------------------------------------

    @Test
    fun `actions survive serialization and re-validate on decode`() {
        val original = valid(
            action {
                put("type", "SET_ALARM"); put("hour", 7); put("minute", 30); put("relative_day", "tomorrow")
            },
        ) as NuvaAction.SetAlarm
        val decoded = ActionJson.decode(ActionJson.encode(NuvaAction.SetAlarm(7, 30, null, RelativeDay.TOMORROW, null)))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `tampered action json is refused on decode`() {
        val tampered = """{"type":"OPEN_APP","app":"youtube","package":"evil package"}"""
        // Valid JSON, invalid package → decodes with the bogus package dropped by the validator.
        val decoded = ActionJson.decode(tampered)
        assertTrue(decoded is NuvaAction.OpenApp)
        assertNull((decoded as NuvaAction.OpenApp).pkg)
    }

    @Test
    fun `json envelope parses without throwing`() {
        // The DTO layer must tolerate unknown keys (server adds fields later).
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(
            com.nuva.assistant.ai.CommandResponseDto.serializer(),
            """{"ok":true,"future_field":123,"result":{"intent":"GO_HOME","action":{"type":"GO_HOME"},"risk":"low","speech":"ok"}}""",
        )
        assertTrue(dto.ok)
    }
}
