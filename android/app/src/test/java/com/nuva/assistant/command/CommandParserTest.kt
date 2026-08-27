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

    @Test
    fun `extended device diagnostics map to local status kinds`() {
        val cases = mapOf(
            "phone model ki" to DeviceStatusKind.DEVICE_INFO,
            "ram koto" to DeviceStatusKind.MEMORY,
            "phone uptime koto" to DeviceStatusKind.UPTIME,
            "screen resolution koto" to DeviceStatusKind.DISPLAY,
            "volume koto" to DeviceStatusKind.AUDIO,
            "timezone ki" to DeviceStatusKind.TIMEZONE,
            "phone language ki" to DeviceStatusKind.LOCALE,
            "koyta app installed" to DeviceStatusKind.INSTALLED_APPS,
            "phone e ki sensor ache" to DeviceStatusKind.SENSORS,
        )
        cases.forEach { (phrase, expected) ->
            val query = CommandParser.parse(phrase)!!.action as NuvaAction.DeviceStatusQuery
            assertEquals(phrase, expected, query.query)
        }
    }

    @Test
    fun `common banglish spellings get local realtime date and time`() {
        val date = CommandParser.parse("aj koto tarik")!!.action as NuvaAction.DeviceStatusQuery
        assertEquals(DeviceStatusKind.DATE, date.query)

        val time = CommandParser.parse("akn koyta baje")!!.action as NuvaAction.DeviceStatusQuery
        assertEquals(DeviceStatusKind.TIME, time.query)

        val combined = CommandParser.parse("aj koto tarik akn koyta baje")!!.action as NuvaAction.DeviceStatusQuery
        assertEquals(DeviceStatusKind.DATE_TIME, combined.query)
    }

    @Test
    fun `date and time questions tolerate bangla english and asr variants`() {
        listOf("এখন কয়টা বাজে", "akhon koita baje", "what time is it").forEach { phrase ->
            val query = CommandParser.parse(phrase)!!.action as NuvaAction.DeviceStatusQuery
            assertEquals(phrase, DeviceStatusKind.TIME, query.query)
        }
        listOf("আজকে কত তারিখ", "ajke koto tarikh", "what is the date today").forEach { phrase ->
            val query = CommandParser.parse(phrase)!!.action as NuvaAction.DeviceStatusQuery
            assertEquals(phrase, DeviceStatusKind.DATE, query.query)
        }
    }

    @Test
    fun `fresh external information opens a live web search`() {
        listOf("ajker weather kemon", "latest news ki", "cricket live score koto").forEach { phrase ->
            val decision = CommandParser.parse(phrase)
            assertNotNull(phrase, decision)
            assertEquals(phrase, NuvaIntent.SEARCH_WEB, decision!!.intent)
            assertTrue((decision.action as NuvaAction.SearchWeb).query.isNotBlank())
        }
    }

    // --- Daily-life utility engine ------------------------------------------------------

    @Test
    fun `calculations conversions and daily utilities become local answers`() {
        val probes = listOf(
            "2 + 3 * 4 koto" to "14",
            "500 er 20 percent discount" to "400",
            "5 kilometer mile e koto" to "3.106856",
            "300 km 20 liter mileage koto" to "15",
            "average of 10 20 30" to "20",
            "10000 simple interest 10 percent 2 year" to "2000",
            "120 km 60 kmph travel time koto" to "2 hour",
        )
        probes.forEach { (phrase, expected) ->
            val decision = CommandParser.parse(phrase)
            assertEquals(phrase, NuvaIntent.LOCAL_ANSWER, decision!!.intent)
            assertTrue(phrase, (decision.action as NuvaAction.LocalAnswer).answer.contains(expected))
            assertFalse(decision.requiresConfirmation)
        }
    }

    @Test
    fun `shopping expense help and factual questions have useful routes`() {
        val shopping = CommandParser.parse("shopping list e add koro dim dudh")!!.action as NuvaAction.CreateTodo
        assertTrue(shopping.content.contains("Shopping:"))
        assertTrue(shopping.content.contains("dim dudh"))

        val expense = CommandParser.parse("expense note lunch 250")!!.action as NuvaAction.CreateNote
        assertTrue(expense.content.contains("Expense:"))

        val readShopping = CommandParser.parse("shopping list dekhao")!!.action as NuvaAction.ReadSavedItems
        assertEquals(SavedItemKind.SHOPPING, readShopping.kind)
        val readExpenses = CommandParser.parse("khoroch gulo poro")!!.action as NuvaAction.ReadSavedItems
        assertEquals(SavedItemKind.EXPENSE, readExpenses.kind)

        assertEquals(NuvaIntent.LOCAL_ANSWER, CommandParser.parse("tumi ki ki korte paro")!!.intent)
        assertEquals(NuvaIntent.SEARCH_WEB, CommandParser.parse("chicken biryani recipe")!!.intent)
        assertEquals(NuvaIntent.SEARCH_WEB, CommandParser.parse("photosynthesis ki")!!.intent)
    }

    @Test
    fun `one hundred daily skill shortcuts route to sourced information`() {
        assertEquals(NuvaIntent.MAP_NAVIGATION, CommandParser.parse("kacher pharmacy")!!.intent)
        listOf(
            "parcel tracking ZX123",
            "passport application",
            "current job circular",
            "internet speed test",
            "গাছের যত্ন",
        ).forEach { phrase ->
            val decision = CommandParser.parse(phrase)
            assertNotNull(phrase, decision)
            assertEquals(phrase, NuvaIntent.SEARCH_WEB, decision!!.intent)
        }
    }

    @Test
    fun `five hundred matrix generated skills require entity plus task`() {
        assertEquals(NuvaIntent.MAP_NAVIGATION, CommandParser.parse("nearby private tutor")!!.intent)
        listOf(
            "passport ki kagoj lagbe",
            "excel tutorial",
            "washing machine repair",
            "রাউটার ব্যবহারের নিয়ম",
        ).forEach { phrase ->
            val decision = CommandParser.parse(phrase)
            assertNotNull(phrase, decision)
            assertEquals(phrase, NuvaIntent.SEARCH_WEB, decision!!.intent)
        }
        assertNull(CommandParser.parse("required documents"))
    }

    @Test
    fun `universal parser is connected to the main rule table`() {
        assertEquals(NuvaIntent.PRESS, CommandParser.parse("Send button press koro")!!.intent)
        assertEquals(NuvaIntent.CLEAR_TEXT, CommandParser.parse("lekhata muchhe dao")!!.intent)
        assertEquals(NuvaIntent.DESCRIBE_SCREEN, CommandParser.parse("button gulo dekhao")!!.intent)
    }

    // --- User-present files & gallery --------------------------------------------------

    @Test
    fun `file and media commands always become user picker workflows`() {
        val cases = listOf(
            "file open koro" to UserFileOperation.OPEN_FILE,
            "file share koro" to UserFileOperation.SHARE_FILE,
            "text file pore shonao" to UserFileOperation.READ_TEXT,
            "folder select koro" to UserFileOperation.OPEN_FOLDER,
            "gallery theke photo select koro" to UserFileOperation.PICK_PHOTO,
            "photo share koro" to UserFileOperation.SHARE_PHOTO,
            "gallery theke video select koro" to UserFileOperation.PICK_VIDEO,
            "video share koro" to UserFileOperation.SHARE_VIDEO,
        )
        cases.forEach { (phrase, operation) ->
            val decision = CommandParser.parse(phrase)
            assertEquals(phrase, NuvaIntent.USER_FILE, decision!!.intent)
            assertEquals(phrase, operation, (decision.action as NuvaAction.UserFile).operation)
        }
    }

    @Test
    fun `target aware file mutations and photo editor handoff parse safely`() {
        val rename = CommandParser.parse("file rename koro new name report.pdf")!!
        val renameAction = rename.action as NuvaAction.UserFile
        assertEquals(UserFileOperation.RENAME_FILE, renameAction.operation)
        assertEquals("report.pdf", renameAction.newName)
        assertTrue(rename.requiresConfirmation)

        assertEquals(UserFileOperation.COPY_FILE, (CommandParser.parse("file copy koro")!!.action as NuvaAction.UserFile).operation)
        assertEquals(UserFileOperation.MOVE_FILE, (CommandParser.parse("file move koro")!!.action as NuvaAction.UserFile).operation)
        assertEquals(UserFileOperation.DELETE_FILE, (CommandParser.parse("file delete koro")!!.action as NuvaAction.UserFile).operation)
        assertEquals(UserFileOperation.EDIT_PHOTO, (CommandParser.parse("photo crop koro")!!.action as NuvaAction.UserFile).operation)
        assertTrue(CommandParser.parse("file rename koro")!!.unsupported)
    }

    @Test
    fun `sharing mutations and folder access confirm while read only selection does not`() {
        assertTrue(CommandParser.parse("file share koro")!!.requiresConfirmation)
        assertTrue(CommandParser.parse("folder access dao")!!.requiresConfirmation)
        assertTrue(CommandParser.parse("file delete koro")!!.requiresConfirmation)
        assertTrue(CommandParser.parse("file copy koro")!!.requiresConfirmation)
        assertTrue(CommandParser.parse("photo edit koro")!!.requiresConfirmation)
        assertFalse(CommandParser.parse("file open koro")!!.requiresConfirmation)
        assertFalse(CommandParser.parse("text file poro")!!.requiresConfirmation)
    }

    @Test
    fun `multiple files media and email attachments use bounded multi picker`() {
        assertEquals(
            UserFileOperation.SHARE_MULTIPLE_FILES,
            (CommandParser.parse("multiple file share koro")!!.action as NuvaAction.UserFile).operation,
        )
        assertEquals(
            UserFileOperation.SHARE_MULTIPLE_PHOTOS,
            (CommandParser.parse("onek photo share koro")!!.action as NuvaAction.UserFile).operation,
        )
        assertEquals(
            UserFileOperation.SHARE_MULTIPLE_VIDEOS,
            (CommandParser.parse("multiple video share koro")!!.action as NuvaAction.UserFile).operation,
        )
        val email = CommandParser.parse("email compose koro multiple attachment")!!.action as NuvaAction.ComposeEmail
        assertTrue(email.attachmentRequested)
        assertTrue(email.multipleAttachments)
        assertNull(email.body)
    }

    @Test
    fun `social post mms and voicemail stay visible and user finalized`() {
        val social = CommandParser.parse("facebook post draft je aj meeting ache")!!
        val post = social.action as NuvaAction.ComposeSocialPost
        assertEquals(SocialPlatform.FACEBOOK, post.platform)
        assertEquals("aj meeting ache", post.text)
        assertTrue(social.requiresConfirmation)

        val mms = CommandParser.parse("mms compose 01712345678 photo attachment je ami ashchi")!!
        val mmsAction = mms.action as NuvaAction.ComposeMms
        assertEquals("01712345678", mmsAction.recipient)
        assertEquals("ami ashchi", mmsAction.body)
        assertTrue(mmsAction.attachmentRequested)
        assertTrue(mms.requiresConfirmation)

        assertEquals(NuvaIntent.OPEN_VOICEMAIL, CommandParser.parse("voicemail khulo")!!.intent)
        assertTrue(CommandParser.parse("facebook post draft")!!.unsupported)
    }

    // --- Maps/navigation ---------------------------------------------------------------

    @Test
    fun `directions navigation nearby and street view preserve dynamic places`() {
        val navigation = CommandParser.parse("navigate to dhaka walking")!!.action as NuvaAction.MapNavigation
        assertEquals(MapRequestType.NAVIGATION, navigation.requestType)
        assertEquals("dhaka", navigation.destination)
        assertEquals(TravelMode.WALKING, navigation.travelMode)

        val directions = CommandParser.parse("from sylhet to dhaka public transport")!!.action as NuvaAction.MapNavigation
        assertEquals("sylhet", directions.origin)
        assertEquals("dhaka", directions.destination)
        assertEquals(TravelMode.TRANSIT, directions.travelMode)

        val nearby = CommandParser.parse("nearby pharmacy")!!.action as NuvaAction.MapNavigation
        assertEquals(MapRequestType.NEARBY, nearby.requestType)
        assertEquals("pharmacy", nearby.destination)

        val street = CommandParser.parse("street view 24.8949,91.8687")!!.action as NuvaAction.MapNavigation
        assertEquals(MapRequestType.STREET_VIEW, street.requestType)
        assertEquals("24.8949,91.8687", street.destination)
    }

    // --- Email compose & notification RemoteInput -------------------------------------

    @Test
    fun `email commands create user reviewed compose drafts`() {
        val decision = CommandParser.parse(
            "user@example.com ke email koro subject meeting je kal 9 tay asben",
        )!!
        val email = decision.action as NuvaAction.ComposeEmail
        assertEquals("user@example.com", email.recipient)
        assertEquals("meeting", email.subject)
        assertEquals("kal 9 tay asben", email.body)
        assertTrue(decision.requiresConfirmation)

        val blank = CommandParser.parse("email compose koro")!!.action as NuvaAction.ComposeEmail
        assertNull(blank.recipient)
        assertNull(blank.body)

        val attachment = CommandParser.parse("user@example.com ke email compose koro attachment")!!
            .action as NuvaAction.ComposeEmail
        assertTrue(attachment.attachmentRequested)
        assertNull(attachment.body)
    }

    @Test
    fun `notification reply extracts ordinal and exact message`() {
        val decision = CommandParser.parse("2 number notification e reply dao je ami 10 minute e ashchi")!!
        val reply = decision.action as NuvaAction.ReplyNotification
        assertEquals(2, reply.ordinal)
        assertEquals("ami 10 minute e ashchi", reply.message)
        assertTrue(decision.requiresConfirmation)

        val missing = CommandParser.parse("notification reply koro")
        assertTrue(missing!!.unsupported)

        val credential = CommandParser.parse("notification reply dao je otp 1234")
        assertTrue(credential!!.unsupported)
        assertNull(credential.action)
    }

    // --- Share, contact draft & notification management -------------------------------

    @Test
    fun `text share and contact creation stay user finalized`() {
        val share = CommandParser.parse("text share koro je ami ashchi")!!
        assertEquals("ami ashchi", (share.action as NuvaAction.ShareText).text)
        assertTrue(share.requiresConfirmation)

        val contact = CommandParser.parse(
            "new contact add koro name Rahim number 01712345678 email rahim@example.com",
        )!!
        val draft = contact.action as NuvaAction.CreateContactDraft
        assertEquals("rahim", draft.name.lowercase())
        assertEquals("01712345678", draft.phone)
        assertEquals("rahim@example.com", draft.email)
        assertTrue(contact.requiresConfirmation)
    }

    @Test
    fun `contact picker handoff and uninstall stay system finalized`() {
        val edit = CommandParser.parse("contact edit koro")!!
        assertEquals(ContactHandoffOperation.EDIT, (edit.action as NuvaAction.ContactHandoff).operation)
        assertTrue(edit.requiresConfirmation)

        val uninstall = CommandParser.parse("facebook uninstall koro")!!
        assertEquals("facebook", (uninstall.action as NuvaAction.UninstallApp).app)
        assertTrue(uninstall.requiresConfirmation)

        val financial = CommandParser.parse("bkash uninstall koro")
        assertTrue(financial!!.unsupported)

        val info = CommandParser.parse("facebook app info khulo")!!.action as NuvaAction.OpenAppManagement
        assertEquals(AppManagementPanel.APP_INFO, info.panel)
        val notifications = CommandParser.parse("whatsapp notification settings khulo")!!.action as NuvaAction.OpenAppManagement
        assertEquals("whatsapp", notifications.app)
        assertEquals(AppManagementPanel.NOTIFICATIONS, notifications.panel)
        val store = CommandParser.parse("youtube play store page khulo")!!.action as NuvaAction.OpenAppManagement
        assertEquals(AppManagementPanel.PLAY_STORE, store.panel)
    }

    @Test
    fun `clipboard operations are explicit bounded and confirmation gated`() {
        val copy = CommandParser.parse("clipboard e copy koro je meeting kal 9 tay")!!
        val copyAction = copy.action as NuvaAction.ClipboardAction
        assertEquals(ClipboardOperation.COPY, copyAction.operation)
        assertEquals("meeting kal 9 tay", copyAction.text)
        assertTrue(copy.requiresConfirmation)

        assertEquals(ClipboardOperation.READ, (CommandParser.parse("clipboard poro")!!.action as NuvaAction.ClipboardAction).operation)
        assertEquals(ClipboardOperation.CLEAR, (CommandParser.parse("clipboard clear koro")!!.action as NuvaAction.ClipboardAction).operation)
        assertTrue(CommandParser.parse("copy to clipboard")!!.unsupported)
    }

    @Test
    fun `rich calendar event parses title duration location description and attendee`() {
        val decision = CommandParser.parse(
            "kal 9 tay 2 hour calendar event create title project meeting location khulna description roadmap attendee user@example.com",
        )!!
        val event = decision.action as NuvaAction.CreateCalendarEvent
        assertEquals("project meeting", event.title)
        assertEquals("khulna", event.location)
        assertEquals("roadmap", event.description)
        assertEquals("user@example.com", event.attendeeEmail)
        assertEquals(7_200_000L, event.endAt - event.beginAt)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `one safe notification can be dismissed or marked read after confirmation`() {
        val dismiss = CommandParser.parse("2 number notification dismiss koro")!!
        val dismissAction = dismiss.action as NuvaAction.ManageNotification
        assertEquals(2, dismissAction.ordinal)
        assertEquals(NotificationManageOperation.DISMISS, dismissAction.operation)
        assertTrue(dismiss.requiresConfirmation)

        val markRead = CommandParser.parse("notification mark as read koro")!!
        assertEquals(
            NotificationManageOperation.MARK_READ,
            (markRead.action as NuvaAction.ManageNotification).operation,
        )
    }

    // --- Forms & scheduled compose -----------------------------------------------------

    @Test
    fun `form handoff stores only explicit local details and requires confirmation`() {
        val decision = CommandParser.parse("passport application form kholo details name address draft")!!
        val form = decision.action as NuvaAction.PrepareForm
        assertEquals(FormKind.PASSPORT, form.kind)
        assertEquals("name address draft", form.details)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `scheduled email and sms become reminders not automatic sends`() {
        val emailDecision = CommandParser.parse(
            "kal shokal 9 tay schedule email user@example.com subject meeting je ami ashchi",
        )!!
        val email = emailDecision.action as NuvaAction.ScheduleCompose
        assertEquals(ComposeChannel.EMAIL, email.channel)
        assertEquals("user@example.com", email.recipient)
        assertEquals("meeting", email.subject)
        assertEquals("ami ashchi", email.body)
        assertTrue(emailDecision.requiresConfirmation)
        assertTrue(email.triggerAt > System.currentTimeMillis())

        val sms = CommandParser.parse("kal 8 tay schedule sms 01712345678 message ami ashchi")!!
            .action as NuvaAction.ScheduleCompose
        assertEquals(ComposeChannel.SMS, sms.channel)
        assertEquals("01712345678", sms.recipient)

        val daily = CommandParser.parse("protidin 8 tay schedule sms message standup update")!!
            .action as NuvaAction.ScheduleCompose
        assertEquals(ComposeRecurrence.DAILY, daily.recurrence)
        val weekly = CommandParser.parse("shukrobar 9 tay schedule email je weekly report")!!
            .action as NuvaAction.ScheduleCompose
        assertEquals(ComposeRecurrence.WEEKLY, weekly.recurrence)

        assertEquals(NuvaIntent.LIST_SCHEDULED_DRAFTS, CommandParser.parse("scheduled draft list dekhao")!!.intent)
        val cancel = CommandParser.parse("2 number scheduled draft cancel koro")!!
        assertEquals(2, (cancel.action as NuvaAction.CancelScheduledDraft).ordinal)
        assertTrue(cancel.requiresConfirmation)

        assertTrue(CommandParser.parse("schedule email kal 9 tay")!!.unsupported)
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
        val extended = mapOf(
            "mobile data setting khulo" to SettingTarget.MOBILE_DATA,
            "airplane mode setting khulo" to SettingTarget.AIRPLANE_MODE,
            "location setting khulo" to SettingTarget.LOCATION,
            "hotspot setting khulo" to SettingTarget.HOTSPOT,
            "nfc setting khulo" to SettingTarget.NFC,
            "vpn setting khulo" to SettingTarget.VPN,
            "battery saver setting khulo" to SettingTarget.BATTERY_SAVER,
            "default apps setting khulo" to SettingTarget.DEFAULT_APPS,
            "date time setting khulo" to SettingTarget.DATE_TIME,
            "language setting khulo" to SettingTarget.LANGUAGE,
            "storage setting khulo" to SettingTarget.STORAGE_SETTINGS,
            "privacy setting khulo" to SettingTarget.PRIVACY,
            "security setting khulo" to SettingTarget.SECURITY,
            "cast setting khulo" to SettingTarget.CAST,
            "print setting khulo" to SettingTarget.PRINT,
            "caption setting khulo" to SettingTarget.CAPTIONS,
        )
        extended.forEach { (phrase, target) ->
            assertEquals(phrase, target, (CommandParser.parse(phrase)!!.action as NuvaAction.OpenSettingScreen).target)
        }
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

    // --- v1.5: universal app-agnostic commands (Phase 5) --------------------------------------------

    @Test
    fun `press by button name and bare press parse`() {
        val named = CommandParser.parse("nuva send button press koro")
        assertEquals(NuvaIntent.PRESS, named!!.intent)
        assertEquals("send", (named.action as NuvaAction.Press).label)

        val bare = CommandParser.parse("এটা press করো")
        assertEquals(NuvaIntent.PRESS, bare!!.intent)
        assertNull((bare.action as NuvaAction.Press).label)

        val bangla = CommandParser.parse("লগইন বাটন চাপো")
        assertEquals(NuvaIntent.PRESS, bangla!!.intent)
        assertEquals("লগইন", (bangla.action as NuvaAction.Press).label)
    }

    @Test
    fun `clear text notification shade and describe screen parse`() {
        assertEquals(NuvaIntent.CLEAR_TEXT, CommandParser.parse("nuva lekhata muchho")!!.intent)
        assertEquals(NuvaIntent.CLEAR_TEXT, CommandParser.parse("লেখাটা মুছো")!!.intent)
        assertEquals(NuvaIntent.OPEN_NOTIFICATIONS, CommandParser.parse("notification panel kholo")!!.intent)
        assertEquals(NuvaIntent.DESCRIBE_SCREEN, CommandParser.parse("ki button ache")!!.intent)
        assertEquals(NuvaIntent.DESCRIBE_SCREEN, CommandParser.parse("বাটন দেখাও")!!.intent)
        // plain notification reading still goes to READ_NOTIFICATIONS
        assertEquals(NuvaIntent.READ_NOTIFICATIONS, CommandParser.parse("notification poro")!!.intent)
    }

    @Test
    fun `open notification source app parses`() {
        val first = CommandParser.parse("notification er app khulo")
        assertEquals(NuvaIntent.OPEN_NOTIFICATION_APP, first!!.intent)
        assertEquals(1, (first.action as NuvaAction.OpenNotificationApp).ordinal)

        val third = CommandParser.parse("3 number notification er app khulo")
        assertEquals(3, (third!!.action as NuvaAction.OpenNotificationApp).ordinal)
    }

    @Test
    fun `new settings screens parse`() {
        assertEquals(
            SettingTarget.NOTIFICATION_SETTINGS,
            (CommandParser.parse("notification setting khulo")!!.action as NuvaAction.OpenSettingScreen).target,
        )
        assertEquals(
            SettingTarget.APP_SETTINGS,
            (CommandParser.parse("nuva er setting kholo")!!.action as NuvaAction.OpenSettingScreen).target,
        )
        assertEquals(
            SettingTarget.ACCESSIBILITY_SETTINGS,
            (CommandParser.parse("accessibility settings kholo")!!.action as NuvaAction.OpenSettingScreen).target,
        )
    }

    // --- v1.4b: maps / LOCATION --------------------------------------------------------------------

    @Test
    fun `map queries open a maps search`() {
        val decision = CommandParser.parse("nuva dhaka er map dekhao")
        assertEquals(NuvaIntent.OPEN_URL, decision!!.intent)
        val url = (decision.action as NuvaAction.OpenUrl).url
        assertTrue(url.contains("maps/search"))
        assertTrue(url.contains("dhaka"))

        val banglish = CommandParser.parse("map e cox bazar khujho")
        assertTrue((banglish!!.action as NuvaAction.OpenUrl).url.contains("cox"))
    }

    @Test
    fun `kothay question becomes a maps search`() {
        val decision = CommandParser.parse("rail station kothay")
        val url = (decision!!.action as NuvaAction.OpenUrl).url
        assertTrue(url.contains("maps/search"))
        assertTrue(url.contains("rail"))
    }

    @Test
    fun `opening the maps app still works`() {
        assertEquals(NuvaIntent.OPEN_APP, CommandParser.parse("google maps khulo")!!.intent)
    }

    // --- v1.4: chat open + pronoun follow-ups ------------------------------------------------------

    @Test
    fun `chat open extracts the contact`() {
        val decision = CommandParser.parse("Rohim-er chat kholo")
        assertEquals(NuvaIntent.OPEN_CHAT, decision!!.intent)
        val chat = decision.action as NuvaAction.OpenChat
        assertEquals("rohim", chat.contact.lowercase())
        assertEquals(MessagingApp.WHATSAPP, chat.app)
        assertEquals(NuvaRisk.LOW, decision.risk) // opens, never sends
    }

    @Test
    fun `bangla chat open works`() {
        val decision = CommandParser.parse("নুভা রহিমের চ্যাট খোলো")
        val chat = decision!!.action as NuvaAction.OpenChat
        assertEquals("রহিম", chat.contact)
    }

    @Test
    fun `explicit app in chat open is respected`() {
        val decision = CommandParser.parse("Rohim-er chat Telegram-e kholo")
        val chat = decision!!.action as NuvaAction.OpenChat
        assertEquals(MessagingApp.TELEGRAM, chat.app)
    }

    @Test
    fun `bangla pronoun message parses and requires confirmation`() {
        val decision = CommandParser.parse("ওকে বলো আমি কাল আসব না")
        val send = decision!!.action as NuvaAction.SendMessage
        assertTrue(ContextMemory.isContactPronoun(send.contact))
        assertEquals("আমি কাল আসব না", send.message)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `banglish pronoun message and call work`() {
        val send = CommandParser.parse("oke bolo ami 10 minute e ashi")!!.action as NuvaAction.SendMessage
        assertTrue(ContextMemory.isContactPronoun(send.contact))
        assertEquals("ami 10 minute e ashi", send.message)

        val call = CommandParser.parse("tar ke call koro")!!.action as NuvaAction.CallContact
        assertTrue(ContextMemory.isContactPronoun(call.contact))
        assertEquals(NuvaRisk.MEDIUM, CommandParser.parse("tar ke call koro")!!.risk)
    }

    @Test
    fun `taka send koro is refused as a transaction`() {
        val decision = CommandParser.parse("nuva bkash e 5000 taka send koro")
        assertTrue(decision!!.unsupported)
        assertEquals(NuvaRisk.HIGH, decision.risk)
    }

    // --- v1.3: natural language, hyphens, defaults, compounds ------------------------------------

    @Test
    fun `hyphenated natural sentence parses`() {
        val decision = CommandParser.parse("Hey Nuva, Rohim-ke WhatsApp-e message dau ami agamikal asbona")
        val send = decision!!.action as NuvaAction.SendMessage
        assertEquals(MessagingApp.WHATSAPP, send.app)
        assertEquals("rohim", send.contact.lowercase())
        assertEquals("ami agamikal asbona", send.message)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `message without an app name defaults to whatsapp`() {
        val decision = CommandParser.parse("Hey Nuva, Mim-ke bolo ami 10 minit pore ashtesi")
        val send = decision!!.action as NuvaAction.SendMessage
        assertEquals(MessagingApp.WHATSAPP, send.app)
        assertEquals("mim", send.contact.lowercase())
        assertTrue(send.message.contains("ashtesi"))
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `kinship prefix stays part of the contact phrase`() {
        val decision = CommandParser.parse("Hey Nuva, amar bhai Sakib-ke call koro")
        val call = decision!!.action as NuvaAction.CallContact
        assertTrue(call.contact.lowercase().contains("sakib"))
    }

    @Test
    fun `hyphenated phone numbers are normalized to digits`() {
        val decision = CommandParser.parse("nuva 01712-345678 ke call koro")
        assertEquals("01712345678", (decision!!.action as NuvaAction.CallContact).phoneNumber)
    }

    @Test
    fun `kholo spelling variant opens apps`() {
        assertEquals("whatsapp", (CommandParser.parse("whatsapp kholo")!!.action as NuvaAction.OpenApp).app)
        assertEquals("chrome", (CommandParser.parse("chrome kholo")!!.action as NuvaAction.OpenApp).app)
    }

    @Test
    fun `compound command produces an ordered plan`() {
        val plan = CommandParser.parseCompound("Hey Nuva, WhatsApp kholo ar Rohim-ke message dau ami agamikal asbona")
        assertEquals(2, plan!!.size)
        assertEquals("whatsapp", (plan[0].action as NuvaAction.OpenApp).app)
        val send = plan[1].action as NuvaAction.SendMessage
        assertEquals("rohim", send.contact.lowercase())
        assertEquals("ami agamikal asbona", send.message)
        assertTrue(plan[1].requiresConfirmation)
    }

    @Test
    fun `compound chrome plus search plan works`() {
        val plan = CommandParser.parseCompound("Hey Nuva, Chrome kholo ar Google-e best laptop under 50000 search koro")
        assertEquals(2, plan!!.size)
        assertEquals("chrome", (plan[0].action as NuvaAction.OpenApp).app)
        val search = plan[1].action as NuvaAction.SearchWeb
        assertEquals("best laptop under 50000", search.query)
    }

    @Test
    fun `youtube context converts search into play media`() {
        val plan = CommandParser.parseCompound("Hey Nuva, YouTube kholo ar Rahat Ahmed search koro")
        assertEquals(2, plan!!.size)
        val play = plan[1].action as NuvaAction.PlayMedia
        assertEquals("rahat ahmed", play.query)
        assertEquals(MediaApp.YOUTUBE, play.app)
    }

    @Test
    fun `connector inside message content is never split`() {
        val decision = CommandParser.parse("rohim ke whatsapp e bole dao ami ar ashbo")
        assertEquals("ami ar ashbo", (decision!!.action as NuvaAction.SendMessage).message)
    }

    @Test
    fun `compound containing a transaction is refused as a whole`() {
        val plan = CommandParser.parseCompound("bkash kholo ar rohim ke 500 taka pathao")
        assertTrue(plan!![0].unsupported)
        assertEquals(NuvaRisk.HIGH, plan[0].risk)
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
