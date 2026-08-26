package com.nuva.assistant.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen-understanding model (v1.5, Phase 4) — the SAFETY is in the model:
 * passwords never captured, OTP-like codes redacted, financial screens
 * flagged. Pure JVM.
 */
class ScreenStateModelTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        password: Boolean = false,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        focused: Boolean = false,
    ) = ScreenStateModel.RawNode(text, desc, password, clickable, editable, scrollable, focused)

    @Test
    fun `buttons inputs and lists are captured with labels`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "com.example.app",
                titleCandidates = listOf("Checkout"),
                nodes = listOf(
                    node(text = "Checkout"),
                    node(text = "Send", clickable = true),
                    node(text = "Cancel", clickable = true),
                    node(desc = "Search", editable = true, focused = true),
                    node(desc = "Items", scrollable = true),
                ),
            ),
        )
        assertEquals("Checkout", state.title)
        assertEquals(listOf("Send", "Cancel"), state.buttons.map { it.label })
        assertEquals(1, state.inputs.size)
        assertEquals("Search", state.focusedElement?.label)
        assertEquals(1, state.lists.size)
        assertFalse(state.sensitiveScreen)
    }

    @Test
    fun `password fields are never captured in any form`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "com.example.app",
                titleCandidates = emptyList(),
                nodes = listOf(
                    node(text = "••••", editable = true, password = true, desc = "PIN"),
                    node(text = "Login", clickable = true),
                ),
            ),
        )
        assertTrue(state.inputs.isEmpty())
        assertTrue(state.visibleText.isBlank()) // the masked text never leaks either
        assertEquals(listOf("Login"), state.buttons.map { it.label })
    }

    @Test
    fun `otp like codes are redacted from visible text`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "com.example.app",
                titleCandidates = emptyList(),
                nodes = listOf(node(text = "Your OTP is 451239")),
            ),
        )
        assertFalse(state.visibleText.contains("451239"))
        assertTrue(state.visibleText.contains("•"))
    }

    @Test
    fun `financial screens are flagged and emptied`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "com.bKash.customerapp",
                titleCandidates = listOf("Home"),
                nodes = listOf(node(text = "Balance 5000", clickable = true)),
            ),
        )
        assertTrue(state.sensitiveScreen)
        val summary = ScreenStateModel.summarize(state)
        assertTrue(summary.contains("Financial"))
    }

    @Test
    fun `button matching is exact first then contains`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "x",
                titleCandidates = emptyList(),
                nodes = listOf(
                    node(text = "Send", clickable = true),
                    node(text = "Send Money", clickable = true),
                    node(text = "Cancel", clickable = true),
                ),
            ),
        )
        assertEquals(listOf("Send"), ScreenStateModel.matchButtons(state, "send").map { it.label })
        assertEquals(2, ScreenStateModel.matchButtons(state, "send money").size)
        assertTrue(ScreenStateModel.matchButtons(state, "help").isEmpty())
    }

    @Test
    fun `summary is bangla first and bounded`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen(
                packageName = "x",
                titleCandidates = listOf("Inbox"),
                nodes = listOf(
                    node(text = "Inbox"),
                    node(text = "Reply", clickable = true),
                    node(text = "Archive", clickable = true),
                    node(desc = "Message", editable = true),
                ),
            ),
        )
        val summary = ScreenStateModel.summarize(state)
        assertTrue(summary.contains("Inbox"))
        assertTrue(summary.contains("Reply"))
        assertTrue(summary.contains("লেখার ঘর"))
    }

    @Test
    fun `empty screen summary is honest`() {
        val state = ScreenStateModel.build(
            ScreenStateModel.RawScreen("x", emptyList(), emptyList()),
        )
        assertNull(state.title)
        assertTrue(state.elements.isEmpty())
    }
}
