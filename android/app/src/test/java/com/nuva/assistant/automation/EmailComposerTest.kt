package com.nuva.assistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class EmailComposerTest {

    @Test
    fun `mailto URI is bounded to validated recipient slot`() {
        assertEquals("mailto:", EmailComposer.mailtoUri(null))
        assertEquals("mailto:user@example.com", EmailComposer.mailtoUri("user@example.com"))
    }
}
