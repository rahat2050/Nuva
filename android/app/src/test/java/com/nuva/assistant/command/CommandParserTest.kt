package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ON-DEVICE PARSER v2 tests — Bangla, Banglish & English, Bangla numerals,
 * plus the strict security refusals that must NEVER depend on the network.
 */
class CommandParserTest {

    // --- App open/close ---------------------------------------------------------

    @Test
    fun `opens youtube with or without the wake word`() {
        val withWake = CommandParser.parse("Nuva YouTube open koro.")
        assertNotNull(withWake)
        assertEquals(NuvaIntent.OPEN_APP, withWake!!.intent)
        assertEquals("youtube", (withWake.action as NuvaAction.OpenApp).app)

        val withoutWake = CommandParser.parse("youtube open koro")
        assertNotNull(withoutWake)
        assertEquals(NuvaIntent.OPEN_APP, withoutWake!!.intent)
    }

    @Test
    fun `bangla open command works`() {
        val decision = CommandParser.parse("নুভা ইউটিউব খোলো")
        assertNotNull(decision)
        assertEquals("youtube", (decision!!.action as NuvaAction.OpenApp).app)
    }

    @Test
    fun `unknown app name is passed through for dynamic resolution`() {
        val decision = CommandParser.parse("nuva pathao khulo")
        assertNotNull(decision)
        val open = decision!!.action as NuvaAction.OpenApp
        assertEquals("pathao", open.app)
        assertNull(open.pkg)
    }

    @Test
    fun `close app command works in three scripts`() {
        assertEquals(NuvaIntent.CLOSE_APP, CommandParser.parse("chrome bondho koro")!!.intent)
        assertEquals(NuvaIntent.CLOSE_APP, CommandParser.parse("facebook বন্ধ করো")!!.intent)
    }

    // --- Navigation -----------------------------------------------------------------

    @Test
    fun `home back and recents work`() {
        assertEquals(NuvaIntent.GO_HOME, CommandParser.parse("Nuva home e jao")!!.intent)
        assertEquals(NuvaIntent.GO_BACK, CommandParser.parse("go back")!!.intent)
        assertEquals(NuvaIntent.GO_BACK, CommandParser.parse("নুভা পিছনে যাও")!!.intent)
        assertEquals(NuvaIntent.SHOW_RECENTS, CommandParser.parse("nuva recent apps dekhao")!!.intent)
    }

    // --- Device status ----------------------------------------------------------------

    @Test
    fun `device status questions map to the right kind`() {
        assertEquals(
            DeviceStatusKind.BATTERY,
            (CommandParser.parse("nuva battery koto ache")!!.action as NuvaAction.DeviceStatusQuery).query,
        )
        assertEquals(
            DeviceStatusKind.TIME,
            (CommandParser.parse("এখন কটা বাজে")!!.action as NuvaAction.DeviceStatusQuery).query,
        )
        assertEquals(
            DeviceStatusKind.DATE,
            (CommandParser.parse("আজ কি বার?")!!.action as NuvaAction.DeviceStatusQuery).query,
        )
        assertEquals(
            DeviceStatusKind.NETWORK,
            (CommandParser.parse("internet ache?")!!.action as NuvaAction.DeviceStatusQuery).query,
        )
        assertEquals(
            DeviceStatusKind.STORAGE,
            (CommandParser.parse("amar phone e koto jayga khali")!!.action as NuvaAction.DeviceStatusQuery).query,
        )
    }

    // --- Settings ----------------------------------------------------------------------

    @Test
    fun `torch and settings screens open`() {
        assertEquals(
            SettingTarget.TORCH,
            (CommandParser.parse("nuva torch jalo")!!.action as NuvaAction.OpenSettingScreen).target,
        )
        assertEquals(
            SettingTarget.TORCH,
            (CommandParser.parse("টর্চ জ্বালাও")!!.action as NuvaAction.OpenSettingScreen).target,
        )
        assertEquals(
            SettingTarget.WIFI,
            (CommandParser.parse("wifi on koro")!!.action as NuvaAction.OpenSettingScreen).target,
        )
        assertEquals(
            SettingTarget.BRIGHTNESS,
            (CommandParser.parse("brightness kom koro")!!.action as NuvaAction.OpenSettingScreen).target,
        )
    }

    // --- Alarm / timer --------------------------------------------------------------------

    @Test
    fun `alarm parses banglish morning time`() {
        val decision = CommandParser.parse("nuva shokal 7 tay alarm dao")
        val alarm = decision!!.action as NuvaAction.SetAlarm
        assertEquals(7, alarm.hour)
        assertEquals(0, alarm.minute)
    }

    @Test
    fun `alarm parses bangla numerals and evening times`() {
        val decision = CommandParser.parse("নুভা রাত ৮টায় অ্যালার্ম দাও")
        val alarm = decision!!.action as NuvaAction.SetAlarm
        assertEquals(20, alarm.hour)

        val afternoon = CommandParser.parse("নুভা দুপুর ২টা ৩০ মিনিটে আলার্ম দাও")
        val parsed = afternoon!!.action as NuvaAction.SetAlarm
        assertEquals(14, parsed.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun `alarm without time asks for the time`() {
        val decision = CommandParser.parse("nuva ekta alarm dao")
        assertNotNull(decision)
        assertTrue(decision!!.unsupported)
        assertTrue(decision.speech.contains("Koto tay"))
    }

    @Test
    fun `timers parse minutes hours and bangla fractions`() {
        val decision = CommandParser.parse("Nuva 10 minute er timer lagao")
        val timer = decision!!.action as NuvaAction.SetTimer
        assertEquals(600L, timer.durationSeconds)
        assertEquals(NuvaRisk.LOW, decision.risk)
        assertFalse(decision.requiresConfirmation)

        val hours = CommandParser.parse("nuva 1 ghonta 30 minute timer")!!.action as NuvaAction.SetTimer
        assertEquals(5400L, hours.durationSeconds)

        val bangla = CommandParser.parse("নুভা আধা ঘণ্টার টাইমার দাও")!!.action as NuvaAction.SetTimer
        assertEquals(1800L, bangla.durationSeconds)
    }

    // --- Reminder --------------------------------------------------------------------------

    @Test
    fun `reminder parses title and tomorrow`() {
        val decision = CommandParser.parse("nuva kal shokal 9 tay medicine khawar reminder dao")
        val reminder = decision!!.action as NuvaAction.SetReminder
        assertTrue(reminder.title.contains("medicine"))
        assertEquals("kal", reminder.humanWhen)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = reminder.whenMillis!! }
        assertEquals(9, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
        assertEquals(NuvaRisk.MEDIUM, decision.risk)
        assertTrue(decision.requiresConfirmation)
    }

    // --- Notes / to-dos ----------------------------------------------------------------------

    @Test
    fun `voice notes and todos are captured`() {
        val note = CommandParser.parse("nuva note koro kal bazar e dim kinte hobe")
        assertEquals(NuvaIntent.CREATE_NOTE, note!!.intent)
        assertTrue((note.action as NuvaAction.CreateNote).content.contains("dim"))

        val todo = CommandParser.parse("nuva todo te add koro report submit")
        val created = todo!!.action as NuvaAction.CreateTodo
        assertTrue(created.content.contains("report"))
    }

    // --- Calls ----------------------------------------------------------------------------------

    @Test
    fun `call commands extract contact name and require confirmation`() {
        val decision = CommandParser.parse("nuva rahim ke call koro")
        val call = decision!!.action as NuvaAction.CallContact
        assertEquals("rahim", call.contact.lowercase())
        assertEquals(NuvaRisk.MEDIUM, decision.risk)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `call with raw number keeps the number`() {
        val decision = CommandParser.parse("nuva 01712345678 ke call koro")
        val call = decision!!.action as NuvaAction.CallContact
        assertEquals("01712345678", call.phoneNumber)
    }

    @Test
    fun `bangla call command works`() {
        val decision = CommandParser.parse("নুভা রহিম কে ফোন করো")
        val call = decision!!.action as NuvaAction.CallContact
        assertEquals("রহিম", call.contact)
    }

    // --- Messages ---------------------------------------------------------------------------------

    @Test
    fun `whatsapp message with content parses with medium risk`() {
        val decision = CommandParser.parse("nuva rahim ke whatsapp e bole dao kal class hobe")
        val send = decision!!.action as NuvaAction.SendMessage
        assertEquals(MessagingApp.WHATSAPP, send.app)
        assertEquals("rahim", send.contact.lowercase())
        assertEquals("kal class hobe", send.message)
        assertEquals(NuvaRisk.MEDIUM, decision.risk)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `sms with quoted message parses`() {
        val decision = CommandParser.parse(`nuva karim ke sms pathao "ami 10 minute e aschi"`)
        val send = decision!!.action as NuvaAction.SendMessage
        assertEquals(MessagingApp.SMS, send.app)
        assertEquals("ami 10 minute e aschi", send.message)
    }

    @Test
    fun `message without content asks what to send`() {
        val decision = CommandParser.parse("nuva rahim ke whatsapp e message pathao")
        assertNotNull(decision)
        assertTrue(decision!!.unsupported)
        assertTrue(decision.speech.contains("Ki message"))
    }

    // --- Media / web ---------------------------------------------------------------------------------

    @Test
    fun `youtube playback parses the query`() {
        val decision = CommandParser.parse("nuva youtube e bangla gaan chalao")
        val play = decision!!.action as NuvaAction.PlayMedia
        assertEquals(MediaApp.YOUTUBE, play.app)
        assertEquals("bangla", play.query)
    }

    @Test
    fun `web search strips search words`() {
        val decision = CommandParser.parse("nuva google e dhaka weather khujho")
        val search = decision!!.action as NuvaAction.SearchWeb
        assertEquals("dhaka weather", search.query)
    }

    @Test
    fun `url open command extracts the domain`() {
        val decision = CommandParser.parse("nuva nuva.dev khule dao")
        assertEquals("https://nuva.dev", (decision!!.action as NuvaAction.OpenUrl).url)
    }

    // --- Screen / notifications ------------------------------------------------------------------------

    @Test
    fun `screen reading and notification summaries parse`() {
        assertEquals(NuvaIntent.READ_SCREEN, CommandParser.parse("নুভা এই স্ক্রিনটা পড়ো")!!.intent)
        assertEquals(NuvaIntent.READ_SCREEN, CommandParser.parse("nuva screen poro")!!.intent)
        assertEquals(NuvaIntent.READ_NOTIFICATIONS, CommandParser.parse("নোটিফিকেশন পড়ো")!!.intent)
    }

    // --- SECURITY FIRST ----------------------------------------------------------------------------------

    @Test
    fun `money transfer commands are refused without any network`() {
        val decision = CommandParser.parse("nuva bkash diye 5000 taka pathao")
        assertNotNull(decision)
        assertTrue(decision!!.unsupported)
        assertEquals(NuvaRisk.HIGH, decision.risk)
        assertNull(decision.action)
        // Exact product wording, always (LEVEL 3).
        assertTrue(decision.speech.contains("financial transaction NUVA নিজে করতে পারবে না"))
        assertTrue(decision.reasons.first().contains("financial transaction"))

        val bangla = CommandParser.parse("নুভা বিকাশে ৫০০০ টাকা পাঠাও")
        assertTrue(bangla!!.unsupported)
    }

    @Test
    fun `opening a banking app is allowed at level 1`() {
        val decision = CommandParser.parse("nuva bkash khulo")
        assertNotNull(decision)
        assertEquals(NuvaIntent.OPEN_APP, decision!!.intent)
        assertEquals("bkash", (decision.action as NuvaAction.OpenApp).app)
        assertEquals(NuvaRisk.LOW, decision.risk)

        val bangla = CommandParser.parse("নুভা বিকাশ খোলো")
        assertEquals(NuvaIntent.OPEN_APP, bangla!!.intent)
    }

    @Test
    fun `media playback control parses`() {
        val pause = CommandParser.parse("nuva music pause koro")
        assertEquals(NuvaIntent.MEDIA_CONTROL, pause!!.intent)
        assertEquals(MediaCommand.PAUSE, (pause.action as NuvaAction.MediaControl).command)

        val next = CommandParser.parse("nuva porer gaan chalao na, next")
        // "next" + media word → NEXT even without chalao
        val nextCmd = CommandParser.parse("nuva next track")
        assertEquals(MediaCommand.NEXT, (nextCmd!!.action as NuvaAction.MediaControl).command)

        val bangla = CommandParser.parse("গান থামাও")
        assertEquals(MediaCommand.PAUSE, (bangla!!.action as NuvaAction.MediaControl).command)
    }

    @Test
    fun `volume control parses directly`() {
        val up = CommandParser.parse("nuva volume barao")
        assertEquals(NuvaIntent.VOLUME_CONTROL, up!!.intent)
        assertEquals(VolumeCommand.UP, (up.action as NuvaAction.VolumeControl).command)

        val down = CommandParser.parse("নুভা শব্দ কম করো")
        assertEquals(VolumeCommand.DOWN, (down!!.action as NuvaAction.VolumeControl).command)

        val mute = CommandParser.parse("nuva sound mute koro")
        assertEquals(VolumeCommand.MUTE, (mute!!.action as NuvaAction.VolumeControl).command)

        // "volume setting" still opens the settings screen instead
        val settings = CommandParser.parse("nuva volume setting khulo")
        assertEquals(NuvaIntent.OPEN_SETTING, settings!!.intent)
    }

    @Test
    fun `camera commands parse with explicit capture`() {
        val photo = CommandParser.parse("nuva camera khulo")
        assertEquals(NuvaIntent.CAMERA, photo!!.intent)
        assertEquals(CaptureMode.PHOTO, (photo.action as NuvaAction.CameraOpen).mode)

        val capture = CommandParser.parse("nuva chobi tolo")
        assertEquals(CaptureMode.CAPTURE, (capture!!.action as NuvaAction.CameraOpen).mode)

        val video = CommandParser.parse("nuva video camera khulo")
        assertEquals(CaptureMode.VIDEO, (video!!.action as NuvaAction.CameraOpen).mode)
    }

    @Test
    fun `card payment and bank transfer commands are refused`() {
        for (cmd in listOf(
                "nuva card diye payment koro",
                "nuva bank transfer koro 5000 taka",
                "নুভা বিকাশে টাকা পাঠাও",
                "nuva 100 taka recharge koro",
            )) {
            val decision = CommandParser.parse(cmd)
            assertNotNull("parser must handle: $cmd", decision)
            assertTrue(decision!!.unsupported)
            assertEquals(NuvaRisk.HIGH, decision.risk)
        }
    }

    @Test
    fun `credential requests are refused`() {
        val decision = CommandParser.parse("nuva amar otp ta poro")
        assertNotNull(decision)
        assertTrue(decision!!.unsupported)
    }

    // --- Unknown → null (AI path takes over) ----------------------------------------------------------------

    @Test
    fun `unrecognized commands return null for the ai`() {
        assertNull(CommandParser.parse("nuva amar jonno ekta kobita likho"))
        assertNull(CommandParser.parse(""))
    }

    // --- Legacy security invariants (kept from v1) ------------------------------------------------------------

    @Test
    fun `security policy blocks credential memory keys`() {
        assertTrue(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("preferred_language"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("password"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isMemoryKeyAllowed("otp_code"))
    }

    @Test
    fun `security policy url guard matches validator`() {
        assertTrue(com.nuva.assistant.core.security.SecurityPolicy.isUrlAllowed("https://nuva.dev"))
        assertFalse(com.nuva.assistant.core.security.SecurityPolicy.isUrlAllowed("javascript:alert(1)"))
    }

    @Test
    fun `confirmation policy has no off switch for risk`() {
        assertTrue(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.MEDIUM, confirmationModeAlways = false),
        )
        assertFalse(
            com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(NuvaRisk.LOW, confirmationModeAlways = false),
        )
    }
}
