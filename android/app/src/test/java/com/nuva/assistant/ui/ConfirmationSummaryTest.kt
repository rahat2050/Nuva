package com.nuva.assistant.ui

import com.nuva.assistant.command.MessagingApp
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirmation dialogs must show target, content, app and risk (§38). */
class ConfirmationSummaryTest {

    @Test
    fun `call summary shows contact number and risk`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.CallContact("Rahim", "01712345678"),
            NuvaRisk.MEDIUM,
        )
        assertEquals("কল করা হবে", summary.title)
        assertTrue(summary.lines.any { it.value == "01712345678" })
        assertTrue(summary.lines.any { it.label == "যোগাযোগ" && it.value == "Rahim" })
        assertTrue(summary.riskLabel.contains("মাঝারি"))
    }

    @Test
    fun `message summary shows app recipient and full content`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.SendMessage(MessagingApp.WHATSAPP, "Karim", "kal class hobe", "01812345678"),
            NuvaRisk.MEDIUM,
        )
        assertEquals("মেসেজ পাঠানো হবে", summary.title)
        assertTrue(summary.lines.any { it.value == "WHATSAPP" })
        assertTrue(summary.lines.any { it.value.contains("kal class hobe") })
        assertTrue(summary.lines.any { it.value == "Karim" })
    }

    @Test
    fun `reminder summary shows title and when`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.SetReminder("medicine", 0L, "kal shokal"),
            NuvaRisk.MEDIUM,
        )
        assertEquals("ক্যালেন্ডারে রিমাইন্ডার", summary.title)
        assertTrue(summary.lines.any { it.value == "medicine" })
        assertTrue(summary.detail.contains("Save"))
    }

    @Test
    fun `note summary says it stays on device`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.CreateNote("buy eggs"),
            NuvaRisk.LOW,
        )
        assertTrue(summary.detail.contains("ফোনে"))
        assertTrue(summary.riskLabel.contains("কম"))
    }

    @Test
    fun `typed text summary shows the exact text`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.TypeText("hello world", null, submit = true),
            NuvaRisk.LOW,
        )
        assertTrue(summary.lines.any { it.value.contains("hello world") })
        assertTrue(summary.lines.any { it.label == "তারপর" })
    }
}
