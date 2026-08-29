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
    fun `mark read allowlist rejects ambiguous unread or archive labels`() {
        assertEquals(true, NuvaNotificationListener.isAllowedMarkReadTitle("Mark as read"))
        assertEquals(true, NuvaNotificationListener.isAllowedMarkReadTitle("পঠিত"))
        assertEquals(false, NuvaNotificationListener.isAllowedMarkReadTitle("Mark unread"))
        assertEquals(false, NuvaNotificationListener.isAllowedMarkReadTitle("Archive"))
    }

    @Test
    fun `first valid free form action is fallback and empty list is unsupported`() {
        assertEquals(0, NuvaNotificationListener.preferredReplyIndex(listOf("Quick response", "Other")))
        assertNull(NuvaNotificationListener.preferredReplyIndex(emptyList()))
    }
}
