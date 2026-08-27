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
        assertNull(NuvaIntent.fromWire("LOCAL_ANSWER"))
        assertNull(NuvaIntent.fromWire("READ_SAVED_ITEMS"))
        assertNull(NuvaIntent.fromWire("USER_FILE"))
        assertNull(NuvaIntent.fromWire("COMPOSE_EMAIL"))
        assertNull(NuvaIntent.fromWire("REPLY_NOTIFICATION"))
        assertNull(NuvaIntent.fromWire("PREPARE_FORM"))
        assertNull(NuvaIntent.fromWire("SCHEDULE_COMPOSE"))
        assertNull(NuvaIntent.fromWire("LIST_SCHEDULED_DRAFTS"))
        assertNull(NuvaIntent.fromWire("CANCEL_SCHEDULED_DRAFT"))
        assertNull(NuvaIntent.fromWire("SHARE_TEXT"))
        assertNull(NuvaIntent.fromWire("CREATE_CONTACT_DRAFT"))
        assertNull(NuvaIntent.fromWire("MANAGE_NOTIFICATION"))
        assertNull(NuvaIntent.fromWire("CONTACT_HANDOFF"))
        assertNull(NuvaIntent.fromWire("UNINSTALL_APP"))
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
            NuvaAction.DeviceStatusQuery(DeviceStatusKind.DATE_TIME),
            NuvaAction.LocalAnswer("Uttor: 42.", "calculation"),
            NuvaAction.ReadSavedItems(SavedItemKind.SHOPPING),
            NuvaAction.UserFile(UserFileOperation.OPEN_FILE),
            NuvaAction.UserFile(UserFileOperation.SHARE_PHOTO),
            NuvaAction.UserFile(UserFileOperation.RENAME_FILE, "report.pdf"),
            NuvaAction.UserFile(UserFileOperation.COPY_FILE),
            NuvaAction.UserFile(UserFileOperation.MOVE_FILE),
            NuvaAction.UserFile(UserFileOperation.DELETE_FILE),
            NuvaAction.UserFile(UserFileOperation.EDIT_PHOTO),
            NuvaAction.ComposeEmail("user@example.com", "meeting", "kal 9 tay"),
            NuvaAction.ComposeEmail(null, null, null, attachmentRequested = true),
            NuvaAction.ReplyNotification(2, "ami ashchi"),
            NuvaAction.PrepareForm(FormKind.PASSPORT, "name and address draft"),
            NuvaAction.ScheduleCompose(
                ComposeChannel.EMAIL,
                "user@example.com",
                "meeting",
                "kal ashben",
                1_800_000_000_000L,
                ComposeRecurrence.WEEKLY,
            ),
            NuvaAction.ListScheduledDrafts,
            NuvaAction.CancelScheduledDraft(2),
            NuvaAction.ShareText("ami ashchi"),
            NuvaAction.CreateContactDraft("Rahim", "01712345678", "rahim@example.com"),
            NuvaAction.ManageNotification(2, NotificationManageOperation.DISMISS),
            NuvaAction.ManageNotification(1, NotificationManageOperation.MARK_READ),
            NuvaAction.ContactHandoff(ContactHandoffOperation.EDIT),
            NuvaAction.UninstallApp("facebook"),
            NuvaAction.UserFile(UserFileOperation.SHARE_MULTIPLE_FILES),
            NuvaAction.UserFile(UserFileOperation.SHARE_MULTIPLE_PHOTOS),
            NuvaAction.ComposeEmail("user@example.com", null, null, attachmentRequested = true, multipleAttachments = true),
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

        val unsafeAnswer = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "LOCAL_ANSWER")
                put("answer", "42")
                put("category", "../../bad")
            },
        )
        assertTrue(unsafeAnswer is CommandValidator.ValidatedAction.Invalid)

        val unsafeFileOperation = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "USER_FILE")
                put("operation", "delete_everything")
            },
        )
        assertTrue(unsafeFileOperation is CommandValidator.ValidatedAction.Invalid)

        val renameWithoutName = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "USER_FILE")
                put("operation", "rename_file")
            },
        )
        assertTrue(renameWithoutName is CommandValidator.ValidatedAction.Invalid)

        val internalAttachmentOperation = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "USER_FILE")
                put("operation", "email_attachment")
            },
        )
        assertTrue(internalAttachmentOperation is CommandValidator.ValidatedAction.Invalid)

        val badEmail = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "COMPOSE_EMAIL")
                put("recipient", "not-an-email")
            },
        )
        assertTrue(badEmail is CommandValidator.ValidatedAction.Invalid)

        val emptyReply = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "REPLY_NOTIFICATION")
                put("message", "")
            },
        )
        assertTrue(emptyReply is CommandValidator.ValidatedAction.Invalid)

        val badForm = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "PREPARE_FORM")
                put("kind", "loan")
            },
        )
        assertTrue(badForm is CommandValidator.ValidatedAction.Invalid)

        val badSchedule = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "SCHEDULE_COMPOSE")
                put("channel", "email")
                put("body", "")
                put("trigger_at", 1_800_000_000_000L)
            },
        )
        assertTrue(badSchedule is CommandValidator.ValidatedAction.Invalid)

        val badRecurrence = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "SCHEDULE_COMPOSE")
                put("channel", "email")
                put("body", "hello")
                put("trigger_at", 1_800_000_000_000L)
                put("recurrence", "every_second")
            },
        )
        assertTrue(badRecurrence is CommandValidator.ValidatedAction.Invalid)

        val emptyShare = CommandValidator.validateAction(
            buildJsonObject { put("type", "SHARE_TEXT"); put("text", "") },
        )
        assertTrue(emptyShare is CommandValidator.ValidatedAction.Invalid)

        val badContact = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "CREATE_CONTACT_DRAFT"); put("name", "Rahim"); put("email", "bad")
            },
        )
        assertTrue(badContact is CommandValidator.ValidatedAction.Invalid)

        val badMultipleEmail = CommandValidator.validateAction(
            buildJsonObject {
                put("type", "COMPOSE_EMAIL"); put("multiple_attachments", true)
            },
        )
        assertTrue(badMultipleEmail is CommandValidator.ValidatedAction.Invalid)

        val badHandoff = CommandValidator.validateAction(
            buildJsonObject { put("type", "CONTACT_HANDOFF"); put("operation", "delete") },
        )
        assertTrue(badHandoff is CommandValidator.ValidatedAction.Invalid)
    }

    @Test
    fun `reminder and messaging risk floors are enforced`() {
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SET_REMINDER))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CALL_CONTACT))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SEND_MESSAGE))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.COMPOSE_EMAIL))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.REPLY_NOTIFICATION))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.PREPARE_FORM))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SCHEDULE_COMPOSE))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CANCEL_SCHEDULED_DRAFT))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.SHARE_TEXT))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CREATE_CONTACT_DRAFT))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.MANAGE_NOTIFICATION))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.CONTACT_HANDOFF))
        assertEquals(NuvaRisk.MEDIUM, baselineRisk(NuvaIntent.UNINSTALL_APP))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.LIST_SCHEDULED_DRAFTS))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.DEVICE_STATUS))
        assertEquals(NuvaRisk.LOW, baselineRisk(NuvaIntent.CREATE_NOTE))
    }
}
