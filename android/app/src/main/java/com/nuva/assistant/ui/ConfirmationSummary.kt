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

            is NuvaAction.UserFile -> {
                title = "Android picker খুলবে"
                lines += Line("কাজ", action.operation.wireName)
                detail = when {
                    action.operation.sharesOutsideDevice ->
                        "আপনি file/media বেছে নেওয়ার পরে Android share sheet-এ final recipient বেছে নেবেন।"
                    action.operation == com.nuva.assistant.command.UserFileOperation.OPEN_FOLDER ->
                        "শুধু আপনার বেছে নেওয়া folder-এর access grant হবে।"
                    else -> "শুধু আপনার বেছে নেওয়া file/media handle করা হবে।"
                }
                if (action.operation.sharesOutsideDevice) confirmLabelOverride = "CONTINUE"
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
