package com.nuva.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationReplyPolicyTest {

    @Test
    fun `explicit reply action wins over other free form actions`() {
        assertEquals(
            1,
            NuvaNotificationListener.preferredReplyIndex(listOf("Mark read", "Reply", "Archive")),
        )
        assertEquals(
            2,
            NuvaNotificationListener.preferredReplyIndex(listOf("Open", "Mute", "রিপ্লাই")),
        )
    }

    @Test
    fun `first valid free form action is fallback and empty list is unsupported`() {
        assertEquals(0, NuvaNotificationListener.preferredReplyIndex(listOf("Quick response", "Other")))
        assertNull(NuvaNotificationListener.preferredReplyIndex(emptyList()))
    }
}
