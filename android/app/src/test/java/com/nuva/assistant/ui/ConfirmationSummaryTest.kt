package com.nuva.assistant.ui

import com.nuva.assistant.command.MessagingApp
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaRisk
import com.nuva.assistant.command.UserFileOperation
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
    fun `email and notification reply summaries show exact outbound content`() {
        val email = ConfirmationSummary.build(
            NuvaAction.ComposeEmail("user@example.com", "meeting", "kal 9 tay"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(email.lines.any { it.value == "user@example.com" })
        assertTrue(email.detail.contains("Send"))

        val reply = ConfirmationSummary.build(
            NuvaAction.ReplyNotification(2, "ami ashchi"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(reply.lines.any { it.value.contains("ami ashchi") })
        assertEquals("SEND REPLY", reply.confirmLabel)
    }

    @Test
    fun `form and schedule summaries keep final submit or send user controlled`() {
        val form = ConfirmationSummary.build(
            NuvaAction.PrepareForm(com.nuva.assistant.command.FormKind.PASSPORT, "local details"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(form.detail.contains("local note"))
        assertTrue(form.detail.contains("Submit"))

        val schedule = ConfirmationSummary.build(
            NuvaAction.ScheduleCompose(
                com.nuva.assistant.command.ComposeChannel.EMAIL,
                "user@example.com",
                "meeting",
                "kal ashben",
                1_800_000_000_000L,
            ),
            NuvaRisk.MEDIUM,
        )
        assertTrue(schedule.detail.contains("automatic Send"))
        assertTrue(schedule.lines.any { it.label == "Repeat" && it.value == "once" })
        assertEquals("SCHEDULE", schedule.confirmLabel)

        val cancel = ConfirmationSummary.build(NuvaAction.CancelScheduledDraft(2), NuvaRisk.MEDIUM)
        assertTrue(cancel.lines.any { it.value.contains("2") })
        assertEquals("CANCEL DRAFT", cancel.confirmLabel)
    }

    @Test
    fun `share contact and notification summaries keep final action visible`() {
        val share = ConfirmationSummary.build(NuvaAction.ShareText("ami ashchi"), NuvaRisk.MEDIUM)
        assertTrue(share.lines.any { it.value.contains("ami ashchi") })
        assertTrue(share.detail.contains("share sheet"))

        val contact = ConfirmationSummary.build(
            NuvaAction.CreateContactDraft("Rahim", "01712345678", null),
            NuvaRisk.MEDIUM,
        )
        assertTrue(contact.detail.contains("Save"))

        val contactEdit = ConfirmationSummary.build(
            NuvaAction.ContactHandoff(com.nuva.assistant.command.ContactHandoffOperation.EDIT),
            NuvaRisk.MEDIUM,
        )
        assertTrue(contactEdit.detail.contains("exact contact", ignoreCase = true))

        val uninstall = ConfirmationSummary.build(NuvaAction.UninstallApp("facebook"), NuvaRisk.MEDIUM)
        assertTrue(uninstall.detail.contains("Android system"))

        val notification = ConfirmationSummary.build(
            NuvaAction.ManageNotification(2, com.nuva.assistant.command.NotificationManageOperation.DISMISS),
            NuvaRisk.MEDIUM,
        )
        assertTrue(notification.lines.any { it.value.contains("dismiss") })
    }

    @Test
    fun `clock summary distinguishes active changes`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.ClockControl(com.nuva.assistant.command.ClockOperation.DISMISS_ALARM),
            NuvaRisk.MEDIUM,
        )
        assertTrue(summary.lines.any { it.value == "dismiss_alarm" })
        assertEquals("CONFIRM", summary.confirmLabel)
    }

    @Test
    fun `advanced media summaries expose seek and exact volume`() {
        val seek = ConfirmationSummary.build(
            NuvaAction.MediaControl(com.nuva.assistant.command.MediaCommand.FAST_FORWARD, 30),
            NuvaRisk.LOW,
        )
        assertTrue(seek.lines.any { it.value.contains("30") })
        val volume = ConfirmationSummary.build(
            NuvaAction.VolumeControl(com.nuva.assistant.command.VolumeCommand.SET, 55),
            NuvaRisk.LOW,
        )
        assertTrue(volume.lines.any { it.value == "55%" })
    }

    @Test
    fun `emergency summary says dialer only and shows 999`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.EmergencyDialer(com.nuva.assistant.command.EmergencyService.AMBULANCE),
            NuvaRisk.LOW,
        )
        assertTrue(summary.lines.any { it.value == "999" })
        assertTrue(summary.detail.contains("final Call"))
    }

    @Test
    fun `map summary exposes origin destination and privacy boundary`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.MapNavigation(
                com.nuva.assistant.command.MapRequestType.DIRECTIONS,
                "Dhaka",
                "Sylhet",
                com.nuva.assistant.command.TravelMode.TRANSIT,
            ),
            NuvaRisk.LOW,
        )
        assertTrue(summary.lines.any { it.value == "Dhaka" })
        assertTrue(summary.lines.any { it.value == "Sylhet" })
        assertTrue(summary.detail.contains("location"))
    }

    @Test
    fun `social and mms summaries keep final publish user controlled`() {
        val social = ConfirmationSummary.build(
            NuvaAction.ComposeSocialPost(com.nuva.assistant.command.SocialPlatform.FACEBOOK, "hello"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(social.detail.contains("final Post"))

        val mms = ConfirmationSummary.build(
            NuvaAction.ComposeMms("01712345678", "hello", attachmentRequested = true),
            NuvaRisk.MEDIUM,
        )
        assertTrue(mms.detail.contains("final Send"))
        assertTrue(mms.lines.any { it.label == "Attachment" })
    }

    @Test
    fun `clipboard and calendar summaries expose exact user data`() {
        val clipboard = ConfirmationSummary.build(
            NuvaAction.ClipboardAction(com.nuva.assistant.command.ClipboardOperation.COPY, "hello"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(clipboard.lines.any { it.value.contains("hello") })
        assertTrue(clipboard.detail.contains("monitoring"))

        val event = ConfirmationSummary.build(
            NuvaAction.CreateCalendarEvent("meeting", 1_800_000_000_000L, 1_800_003_600_000L, "Khulna", null, null),
            NuvaRisk.MEDIUM,
        )
        assertTrue(event.lines.any { it.value == "Khulna" })
        assertTrue(event.detail.contains("Save"))
    }

    @Test
    fun `file mutation summary promises exact target second confirmation`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.UserFile(UserFileOperation.RENAME_FILE, "report.pdf"),
            NuvaRisk.MEDIUM,
        )
        assertTrue(summary.lines.any { it.value == "report.pdf" })
        assertTrue(summary.detail.contains("দ্বিতীয় confirmation"))
    }

    @Test
    fun `file share summary keeps picker and final recipient user controlled`() {
        val summary = ConfirmationSummary.build(
            NuvaAction.UserFile(UserFileOperation.SHARE_FILE),
            NuvaRisk.MEDIUM,
        )
        assertTrue(summary.title.contains("picker"))
        assertTrue(summary.detail.contains("share sheet"))
        assertTrue(summary.detail.contains("recipient"))
        assertEquals("CONTINUE", summary.confirmLabel)
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
