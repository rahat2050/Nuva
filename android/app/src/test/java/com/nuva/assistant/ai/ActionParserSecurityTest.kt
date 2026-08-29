package com.nuva.assistant.ai

import com.nuva.assistant.core.security.SensitiveAppPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserSecurityTest {

    @Test
    fun `credential-bearing model speech becomes unsupported`() {
        val decision = ActionParser.parse(
            CommandResponseDto(
                ok = true,
                result = CommandResultDto(
                    intent = "OPEN_APP",
                    action = buildJsonObject {
                        put("type", "OPEN_APP")
                        put("app", "youtube")
                    },
                    speech = "Your OTP is 4321",
                ),
            ),
        )

        assertTrue(decision.unsupported)
        assertNull(decision.action)
        assertEquals(SensitiveAppPolicy.CREDENTIAL_REFUSAL, decision.speech)
    }

    @Test
    fun `financial model speech becomes unsupported`() {
        val decision = ActionParser.parse(
            CommandResponseDto(
                ok = true,
                result = CommandResultDto(
                    intent = "OPEN_APP",
                    action = buildJsonObject {
                        put("type", "OPEN_APP")
                        put("app", "youtube")
                    },
                    speech = "send money now",
                ),
            ),
        )

        assertTrue(decision.unsupported)
        assertNull(decision.action)
        assertEquals(SensitiveAppPolicy.TRANSACTION_REFUSAL, decision.speech)
    }

    @Test
    fun `credential-bearing structured message becomes unsupported`() {
        val decision = ActionParser.parse(
            CommandResponseDto(
                ok = true,
                result = CommandResultDto(
                    intent = "SEND_MESSAGE",
                    action = buildJsonObject {
                        put("type", "SEND_MESSAGE")
                        put("app", "whatsapp")
                        put("contact", "Rahim")
                        put("message", "amar password hunter2")
                    },
                    speech = "Message pathabo?",
                ),
            ),
        )

        assertTrue(decision.unsupported)
        assertNull(decision.action)
        assertEquals(SensitiveAppPolicy.CREDENTIAL_REFUSAL_REASON, decision.reasons.single())
    }
}
