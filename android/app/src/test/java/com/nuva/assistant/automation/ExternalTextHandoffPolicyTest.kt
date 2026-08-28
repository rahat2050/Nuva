package com.nuva.assistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTextHandoffPolicyTest {
    @Test
    fun `accepted shared text stays a bounded editable draft`() {
        val result = ExternalTextHandoffPolicy.prepare("  summarize this paragraph  ")
        assertEquals(
            ExternalTextHandoffPolicy.Result.Accepted("summarize this paragraph", truncated = false),
            result,
        )

        val long = ExternalTextHandoffPolicy.prepare("a".repeat(1_200))
        assertTrue(long is ExternalTextHandoffPolicy.Result.Accepted)
        long as ExternalTextHandoffPolicy.Result.Accepted
        assertEquals(ExternalTextHandoffPolicy.MAX_DRAFT_CHARS, long.draft.length)
        assertTrue(long.truncated)
    }

    @Test
    fun `empty credential and transaction text never become drafts`() {
        assertEquals(ExternalTextHandoffPolicy.Result.Empty, ExternalTextHandoffPolicy.prepare("  "))
        assertTrue(ExternalTextHandoffPolicy.prepare("OTP 123456") is ExternalTextHandoffPolicy.Result.Blocked)
        assertTrue(ExternalTextHandoffPolicy.prepare("bkash diye taka pathao") is ExternalTextHandoffPolicy.Result.Blocked)
    }
}
