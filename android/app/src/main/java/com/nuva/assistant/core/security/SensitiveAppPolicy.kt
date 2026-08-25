package com.nuva.assistant.core.security

import com.nuva.assistant.command.MessagingApp
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaIntent

/**
 * STRICT SECURITY EXCLUSIONS (blueprint §security + product policy §32–§36).
 *
 * NUVA never automates money: banking apps, mobile wallets (bKash, Nagad,
 * Rocket, Upay, …), payment gateways and any "send money" style request are
 * refused before execution — offline parser, AI decision or not. The same
 * denylist blocks screen reading and tap/type/scroll automation while a
 * sensitive app is in the foreground, so a compromised server cannot use NUVA
 * as a keylogger or transfer drone.
 *
 * This object has NO dependency on Android so it is fully unit-testable.
 */
object SensitiveAppPolicy {

    /**
     * Known sensitive packages + package fragments. Matching is
     * case-insensitive "contains" on the package name, which keeps it robust
     * against app renames (com.bkash.customerapp vs com.bkash.walletapp).
     */
    private val SENSITIVE_PACKAGE_FRAGMENTS = listOf(
        // Mobile wallets (Bangladesh)
        "bkash", "bkash.customerapp", "nagad", "konasl.mobileapp", "rocket", "dbbl",
        "upay", "digipay", "mcash", "trustcloud", "mycash", "tapnpay",
        // Banks / banking apps (generic fragments cover most publisher ids)
        ".bank", "bank.", "banking", "bankapp",
        "citybank", "bracbank", "ebl", "dbblmobile", "primebank", "bankasia",
        "islamibank", "ucash", "sonali", "janatabank", "ruton", "agrani", "pubalibank",
        " easternbank", "meghnabank", "mtb", "sebl", "citybanknp",
        // Card / payment processors & fintech KYC
        "payment", "paywell", "sslcommerz", "portwallet", "aamarpay", "shurjopay",
        "adyen", "stripe", "paypal", "venmo", "cashapp",
    ).map { it.trim().lowercase() }

    /**
     * Sensitive *display names* in every script NUVA understands. Used when
     * matching an OPEN_APP target that has no package hint yet.
     */
    private val SENSITIVE_NAME_KEYWORDS = listOf(
        "bkash", "b kash", "nagad", "rocket", "upay", "ucash", "mycash", "tap and pay",
        "mobile banking", "bank", "banking", "payment", "wallet", "cash",
        "বিকাশ", "বিক্যাশ", "নগদ", "রকেট", "উপায়", "উপাই", "মোবাইল ব্যাংকিং", "ব্যাংক",
        "ব্যাঙ্ক", "পেমেন্ট", "ওয়ালেট", "ক্যাশ", "টাকা পাঠাও",
    )

    /** Requests for money movement — refused no matter which app is named. */
    private val MONEY_TRANSFER_PATTERNS = listOf(
        "send money", "cash out", "send taka", "transfer money", "send tk", "add money",
        "pay the bill", "mobile recharge", "bKash", "bkash", "nagad", "rocket app",
        "send money", "top up",
        "সেন্ড মানি", "ক্যাশ আউট", "টাকা পাঠাও", "টাকা পাঠান", "বিকাশ", "নগদ", "রকেট",
        "রিচার্জ", "বিল পরিশোধ", "টাকা দাও", "পয়সা পাঠাও",
    )

    /** Credentials/authentication material — never read, stored, typed or sent. */
    private val CREDENTIAL_PATTERNS = listOf(
        "otp", "one time password", "pin number", "password", "passcode", "cvv", "cvc",
        "card number", "credit card", "debit card", "biometric", "2fa code", "seed phrase",
        "ওটিপি", "পিন নম্বর", "পিন নাম্বার", "পাসওয়ার্ড", "কার্ড নম্বার", "সিভিভি",
    )

    /** True when the package name belongs to a sensitive app. Null-safe. */
    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase().trim()
        return SENSITIVE_PACKAGE_FRAGMENTS.any { pkg.contains(it) }
    }

    /** True when a user-visible app name looks sensitive (any script). */
    fun isSensitiveAppName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase().trim()
        return SENSITIVE_NAME_KEYWORDS.any { normalized.contains(it) }
    }

    /** Convenience for the runtime foreground check. */
    fun isSensitive(packageName: String?, appName: String? = null): Boolean =
        isSensitivePackage(packageName) || isSensitiveAppName(appName)

    /** True when the raw command text asks for a money transfer. */
    fun isMoneyTransferRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return MONEY_TRANSFER_PATTERNS.any { normalized.contains(it) }
    }

    /** True when the command text mentions credential material. */
    fun mentionsCredentials(text: String): Boolean {
        val normalized = text.lowercase()
        return CREDENTIAL_PATTERNS.any { normalized.contains(it) }
    }

    /**
     * OTP-looking codes (4–8 digits, optionally spaced) — redacted from every
     * notification summary, screen read and confirmation text before it is
     * shown or spoken.
     */
    private val CODE_LIKE = Regex("""(?<!\d)(\d[ -]?){3,7}\d(?!\d)""")

    /** Redacts OTP/PIN-like digit runs from [text]. */
    fun redactCodes(text: String): String =
        if (mentionsCredentials(text)) text.replace(CODE_LIKE) { match ->
            if (match.value.count { it.isDigit() } in 4..8) "••••" else match.value
        } else {
            // Also redact when the code appears with verification wording nearby.
            text.replace(Regex("""(?i)(otp|code|pin|verification code|one[- ]time)\D{0,12}((\d[ -]?){3,7}\d)""")) { m ->
                m.value.replace(Regex("""\d"""), "•")
            }
        }

    /** The single refusal sentence, Bangla-first. */
    val REFUSAL_SPEECH: String =
        "Dorje gelam: taka, bank o payment app e NUVA kichu kore na. " +
            "Egulo apni nije hate korun — NUVA kono bhabe automatic taka pathano ba " +
            "banking screen e kaj kore na."

    val REFUSAL_REASON: String = "blocked: sensitive app (banking/payment denylist)"

    /** Screen-automation refusal (spoken when the foreground app is sensitive). */
    val SCREEN_GUARD_SPEECH: String =
        "Ei screen e NUVA kaj kore na — banking o payment app gulo automatic control er baire."

    /**
     * Pre-execution check for a validated action. Returns a non-null refusal
     * when the action targets a sensitive app / money movement.
     */
    fun refusalFor(action: NuvaAction): Refusal? {
        // Money movement is refused regardless of app.
        return when (action) {
            is NuvaAction.OpenApp, is NuvaAction.CloseApp -> {
                val app = if (action is NuvaAction.OpenApp) action.app else (action as NuvaAction.CloseApp).app
                val pkg = if (action is NuvaAction.OpenApp) action.pkg else (action as NuvaAction.CloseApp).pkg
                val hit = isSensitiveAppName(app) || isSensitivePackage(pkg)
                if (hit) Refusal(REFUSAL_REASON) else null
            }

            // Typing into a sensitive screen is blocked at runtime by the
            // accessibility guard; nothing to pre-check here for other actions.
            else -> null
        }
    }

    /** Refusal for the raw command text (checked before any parsing). */
    fun refusalForText(text: String): Refusal? =
        if (isMoneyTransferRequest(text)) Refusal(REFUSAL_REASON) else null

    data class Refusal(val reason: String)

    /** Messaging apps NUVA will never automate beyond the supported set. */
    fun unsupportedMessaging(app: MessagingApp): String? =
        if (app == MessagingApp.WHATSAPP || app == MessagingApp.SMS) null
        else "${app.wireName} automation ekhon support kori na — WhatsApp o SMS kaj kore."
}
