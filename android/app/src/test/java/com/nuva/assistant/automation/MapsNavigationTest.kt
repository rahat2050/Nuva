package com.nuva.assistant.automation

import com.nuva.assistant.command.MapRequestType
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsNavigationTest {
    @Test
    fun `directions URL preserves origin destination and travel mode`() {
        val action = NuvaAction.MapNavigation(MapRequestType.DIRECTIONS, "Dhaka Airport", "Sunamganj", TravelMode.TRANSIT)
        val url = MapsNavigation.webUrl(action)
        assertTrue(url.contains("origin=Sunamganj"))
        assertTrue(url.contains("destination=Dhaka%20Airport"))
        assertTrue(url.contains("travelmode=transit"))
    }

    @Test
    fun `navigation URL asks maps to start route`() {
        val action = NuvaAction.MapNavigation(MapRequestType.NAVIGATION, "Sylhet", null, TravelMode.DRIVING)
        assertTrue(MapsNavigation.webUrl(action).contains("dir_action=navigate"))
    }

    @Test
    fun `coordinate parser enforces geographic ranges`() {
        assertEquals(24.8949 to 91.8687, MapsNavigation.coordinatePair("24.8949,91.8687"))
        assertNull(MapsNavigation.coordinatePair("100,91"))
        assertNull(MapsNavigation.coordinatePair("not coordinates"))
    }
}
