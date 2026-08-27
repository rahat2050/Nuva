package com.nuva.assistant.ui

import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaRisk

/**
 * Human (Bangla-first) summary of what NUVA is about to do — shown inside
 * every confirmation dialog so the user sees the TARGET, the CONTENT, the APP
 * and the RISK before approving (policy §38). Pure Kotlin → unit-testable.
 */
object ConfirmationSummary {

    data class Line(val label: String, val value: String)

    data class Summary(
        val title: String,
        val lines: List<Line>,
        val risk: NuvaRisk,
        val riskLabel: String,
        /** One-sentence "what will happen". */
        val detail: String,
        /** Action-specific confirm button label (SEND for messages, CALL…). */
        val confirmLabel: String = "হ্যাঁ, করো",
        val cancelLabel: String = "বাতিল",
    )

    fun build(action: NuvaAction, risk: NuvaRisk, contactName: String? = null): Summary {
        val lines = mutableListOf<Line>()
        val title: String
        val detail: String
        var confirmLabelOverride: String? = null

        when (action) {
            is NuvaAction.CallContact -> {
                title = "কল করা হবে"
                confirmLabelOverride = "CALL"
                lines += Line("যোগাযোগ", action.contact.ifBlank { contactName ?: "" })
                action.phoneNumber?.let { lines += Line("নম্বর", it) }
                detail = "${action.phoneNumber ?: action.contact} নম্বরে কল যাবে।"
            }

            is NuvaAction.SendMessage -> {
                title = "মেসেজ পাঠানো হবে"
                confirmLabelOverride = "SEND"
                lines += Line("অ্যাপ", action.app.wireName.uppercase())
                lines += Line("প্রাপক", action.contact.ifBlank { contactName ?: "" })
                action.phoneNumber?.let { lines += Line("নম্বর", it) }
                lines += Line("মেসেজ", "“${action.message}”")
                detail = "${action.app.wireName} এ ${action.contact} কে মেসেজ যাবে।"
            }

            is NuvaAction.SetReminder -> {
                title = "ক্যালেন্ডারে রিমাইন্ডার"
                lines += Line("শিরোনাম", action.title)
                action.humanWhen?.let { lines += Line("সময়", it) }
                detail = "ক্যালেন্ডার খুলব, ইভেন্ট রেডি থাকবে — Save আপনি চাপবেন।"
            }

            is NuvaAction.TypeText -> {
                title = "লেখা টাইপ করা হবে"
                lines += Line("লেখা", "“${action.text}”")
                if (action.submit) lines += Line("তারপর", "সাবমিট/এন্টার চাপা হবে")
                detail = "স্ক্রিনের লেখার ঘরে এই লেখা বসবে।"
            }

            is NuvaAction.Tap -> {
                title = if (action.longClick) "লং-প্রেস" else "ট্যাপ"
                action.target?.text?.let { lines += Line("লক্ষ্য", it) }
                action.target?.contentDescription?.let { lines += Line("লক্ষ্য", it) }
                action.target?.resourceId?.let { lines += Line("এলিমেন্ট", it) }
                detail = "স্ক্রিনের নির্দিষ্ট বাটনে চাপ পড়বে।"
            }

            is NuvaAction.OpenUrl -> {
                title = "ওয়েব পেজ খোলা হবে"
                lines += Line("লিংক", action.url)
                detail = "ব্রাউজারে এই লিংক খুলব।"
            }

            is NuvaAction.SetAlarm -> {
                title = "অ্যালার্ম সেট হবে"
                lines += Line("সময়", "%02d:%02d".format(action.hour, action.minute))
                action.label?.let { lines += Line("লেবেল", it) }
                detail = "এই সময়ে অ্যালার্ম বাজবে।"
            }

            is NuvaAction.CreateNote -> {
                title = "নোট সেভ হবে"
                lines += Line("নোট", "“${action.content}”")
                detail = "নোটটি শুধু আপনার ফোনেই থাকবে।"
            }

            is NuvaAction.CreateTodo -> {
                title = "টু-ডু যোগ হবে"
                lines += Line("কাজ", "“${action.content}”")
                detail = "কাজটি শুধু আপনার ফোনেই থাকবে।"
            }

            is NuvaAction.ComposeEmail -> {
                title = "Email composer খুলবে"
                action.recipient?.let { lines += Line("প্রাপক", it) }
                action.subject?.let { lines += Line("বিষয়", it) }
                action.body?.let { lines += Line("লেখা", "“${it.take(500)}”") }
                if (action.attachmentRequested) lines += Line("সংযুক্তি", "Android picker থেকে আপনি বেছে নেবেন")
                detail = "Email app-এ draft খুলবে; final Send আপনি চাপবেন।"
                confirmLabelOverride = "CONTINUE"
            }

            is NuvaAction.PrepareForm -> {
                title = "Form/booking handoff প্রস্তুত হবে"
                lines += Line("ধরন", action.kind.wireName)
                action.details?.let { lines += Line("Local draft", it.take(500)) }
                detail = "Details শুধু local note-এ থাকবে; official portal search খুলবে, final Submit আপনি করবেন।"
                confirmLabelOverride = "PREPARE"
            }

            is NuvaAction.ScheduleCompose -> {
                title = "Compose reminder schedule হবে"
                lines += Line("Channel", action.channel.wireName.uppercase())
                action.recipient?.let { lines += Line("প্রাপক", it) }
                action.subject?.let { lines += Line("বিষয়", it) }
                lines += Line("Draft", action.body.take(500))
                lines += Line("Repeat", action.recurrence.wireName)
                detail = "সময় হলে notification আসবে; tap করলে draft খুলবে, automatic Send হবে না। Reboot/app update-এর পর pending alarm restore হবে।"
                confirmLabelOverride = "SCHEDULE"
            }

            is NuvaAction.CancelScheduledDraft -> {
                title = "Scheduled draft বাতিল হবে"
                lines += Line("Draft", "${action.ordinal} নম্বর")
                detail = "Pending local alarm ও draft status cancel করা হবে।"
                confirmLabelOverride = "CANCEL DRAFT"
            }

            is NuvaAction.ReplyNotification -> {
                title = "Notification reply পাঠানো হবে"
                lines += Line("নোটিফিকেশন", "${action.ordinal} নম্বর")
                lines += Line("রিপ্লাই", "“${action.message}”")
                detail = "শুধু app-এর official RemoteInput Reply action ব্যবহার হবে।"
                confirmLabelOverride = "SEND REPLY"
            }

            is NuvaAction.UserFile -> {
                title = "Android picker খুলবে"
                lines += Line("কাজ", action.operation.wireName)
                action.newName?.let { lines += Line("নতুন নাম", it) }
                detail = when {
                    action.operation.sharesOutsideDevice ->
                        "আপনি file/media বেছে নেওয়ার পরে Android share sheet-এ final recipient বেছে নেবেন।"
                    action.operation == com.nuva.assistant.command.UserFileOperation.OPEN_FOLDER ->
                        "শুধু আপনার বেছে নেওয়া folder-এর access grant হবে।"
                    action.operation == com.nuva.assistant.command.UserFileOperation.EDIT_PHOTO ->
                        "আপনি photo বেছে নেবেন; installed editor-এ final Save আপনি করবেন।"
                    action.operation.changesSelectedContent || action.operation == com.nuva.assistant.command.UserFileOperation.COPY_FILE ->
                        "Picker-এর পরে exact source/destination দেখিয়ে দ্বিতীয় confirmation নেওয়া হবে।"
                    else -> "শুধু আপনার বেছে নেওয়া file/media handle করা হবে।"
                }
                if (action.operation.needsBlockingConfirmation) confirmLabelOverride = "CONTINUE"
            }

            else -> {
                title = "কাজটি নিশ্চিত করুন"
                detail = "এই কাজটি করা হবে।"
            }
        }

        return Summary(
            title = title,
            lines = lines,
            risk = risk,
            riskLabel = riskLabel(risk),
            detail = detail,
            confirmLabel = confirmLabelOverride ?: "হ্যাঁ, করো",
            cancelLabel = "CANCEL",
        )
    }

    fun riskLabel(risk: NuvaRisk): String = when (risk) {
        NuvaRisk.LOW -> "ঝুঁকি: কম"
        NuvaRisk.MEDIUM -> "ঝুঁকি: মাঝারি"
        NuvaRisk.HIGH -> "ঝুঁকি: উচ্চ — সতর্ক হোন"
    }
}
