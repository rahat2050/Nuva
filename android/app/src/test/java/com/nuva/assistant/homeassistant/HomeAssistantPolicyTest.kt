package com.nuva.assistant.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantPolicyTest {
    @Test
    fun `config accepts HTTPS origin and rejects insecure or credentialed URLs`() {
        assertEquals(
            "https://home.example.com:8123/base",
            HomeAssistantConfigStore.normalizeAndValidateUrl("https://HOME.example.com:8123/base/"),
        )
        assertNull(HomeAssistantConfigStore.normalizeAndValidateUrl("http://192.168.1.2:8123"))
        assertNull(HomeAssistantConfigStore.normalizeAndValidateUrl("https://user:pass@home.example.com"))
        assertNull(HomeAssistantConfigStore.normalizeAndValidateUrl("https://home.example.com?token=secret"))
    }

    @Test
    fun `entity matching prefers exact then starts with then contains`() {
        val entities = listOf(
            HomeAssistantClient.Entity("light.living_room", "Living Room", "off"),
            HomeAssistantClient.Entity("light.living_room_lamp", "Living Room Lamp", "on"),
            HomeAssistantClient.Entity("light.bedroom", "Bedroom Main", "off"),
        )
        assertEquals(
            listOf("light.living_room"),
            HomeAssistantClient.matchEntities(entities, "living room").map { it.entityId },
        )
        assertEquals(
            listOf("light.living_room_lamp"),
            HomeAssistantClient.matchEntities(entities, "living room lamp").map { it.entityId },
        )
        assertEquals(
            listOf("light.bedroom"),
            HomeAssistantClient.matchEntities(entities, "bedroom").map { it.entityId },
        )
    }
}
