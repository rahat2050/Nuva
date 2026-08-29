package com.nuva.assistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackControlTest {
    @Test
    fun `exact volume maps percent to bounded stream index`() {
        assertEquals(0, VolumeController.indexForPercent(15, 0))
        assertEquals(8, VolumeController.indexForPercent(15, 50))
        assertEquals(15, VolumeController.indexForPercent(15, 100))
        assertEquals(15, VolumeController.indexForPercent(15, 150))
    }

    @Test
    fun `seek target clamps at zero duration and five minute maximum`() {
        assertEquals(40_000L, MediaPlaybackControl.seekTarget(10_000L, null, 30, forward = true))
        assertEquals(0L, MediaPlaybackControl.seekTarget(10_000L, null, 30, forward = false))
        assertEquals(60_000L, MediaPlaybackControl.seekTarget(55_000L, 60_000L, 30, forward = true))
        assertEquals(310_000L, MediaPlaybackControl.seekTarget(10_000L, null, 999, forward = true))
    }
}
