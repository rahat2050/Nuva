package com.nuva.assistant.command

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCAL-ONLY intent guarantees (v1.1): the AI wire path can never produce
 * them, but they validate + round-trip for the on-device pipeline.
 */
class ActionJsonTest {

    @Test
    fun `local-only intents are never resolvable from the wire`() {
        // A malicious/buggy server must not be able to trigger local actions.
        assertNull(NuvaIntent.fromWire("SET_REMINDER"))
        assertNull(NuvaIntent.fromWire("CREATE_NOTE"))
        assertNull(NuvaIntent.fromWire("DEVICE_STATUS"))
        assertNull(NuvaIntent.fromWire("OPEN_SETTING"))
        assertNull(NuvaIntent.fromWire("READ_NOTIFICATIONS"))
        assertNull(NuvaIntent.fromWire("SEARCH_WEB"))
        assertNull(NuvaIntent.fromWire("SHOW_RECENTS"))
        assertNull(NuvaIntent.fromWire("CREATE_TODO"))
        assertNull(NuvaIntent.fromWire("OPEN_CHAT"))
        assertNull(NuvaIntent.fromWire("PRESS"))
        assertNull(NuvaIntent.fromWire("CLEAR_TEXT"))
        assertNull(NuvaIntent.fromWire("OPEN_NOTIFICATIONS"))
        assertNull(NuvaIntent.fromWire("OPEN_NOTIFICATION_APP"))
        assertNull(NuvaIntent.fromWire("DESCRIBE_SCREEN"))
        // …while the frozen 15 still resolve.
        assertEquals(NuvaIntent.OPEN_APP, NuvaIntent.fromWire("OPEN_APP"))
        assertEquals(NuvaIntent.READ_SCREEN, NuvaIntent.fromWire("READ_SCREEN"))
    }

    @Test
    fun `new actions round-trip through action json re-validation`() {
        val cases = listOf(
            NuvaAction.ShowRecents,
            NuvaAction.SearchWeb("dhaka weather"),
            NuvaAction.DeviceStatusQuery(DeviceStatusKind.BATTERY),
            NuvaAction.OpenSettingScreen(SettingTarget.TORCH),
            NuvaAction.ReadNotifications,
            NuvaAction.SetReminder("medicine", 1_770_000_000_000L, "kal"),
            NuvaAction.CreateNote("buy eggs"),
            NuvaAction.CreateTodo("submit report"),
            NuvaAction.OpenChat(MessagingApp.WHATSAPP, "Rohim", "01712345678"),
            NuvaAction.Press("Send"),
            NuvaAction.Press(null),
            NuvaAction.ClearText,
            NuvaAction.OpenNotificationShade,
            NuvaAction.OpenNotificationApp(2),
            NuvaAction.DescribeScreen,
        )
        cases.forEach { action ->
            val encoded = ActionJson.encode(action)
            assertEquals(action, ActionJson.decode(encoded))
        }
    }

    @Test
    fun `invalid local-only payloads are refused`() {
        val bad = CommandValidator.validateAction(
            kotlinx.serialization.json.buildJsonObject {
                put("type", "CREATE_NOTE")
                put("content", "")
            },
        )
        assertTrue(bad is CommandValidator.ValidatedAction.Invalid)

        val badTarget = CommandValidator.validateAction(
            kotlinx.serialization.json.buildJsonObject {
                put("type", "OPEN_SETTING")
                put("target", "airplane_mode")
            },
        )
        assertTrue(badTarget is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `reminder and messaging risk floors are enforced`() {
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SET_REMINDER))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CALL_CONTACT))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SEND_MESSAGE))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.DEVICE_STATUS))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.CREATE_NOTE))
    }
}
