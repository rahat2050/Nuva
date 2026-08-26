package com.nuva.assistant.core.security

import com.nuva.assistant.command.MessagingApp

/**
 * FINANCIAL & SENSITIVE-DATA POLICY — three levels (v1.2 product spec).
 *
 * LEVEL 1 — NORMAL ACCESS (allowed)
 *  Launch financial apps ("bKash kholo"), home/back/recents, and basic
 *  non-financial navigation (scroll) inside them. The USER is never blocked
 *  from using their own apps; only NUVA's own automation is constrained.
 *
 * LEVEL 2 — SENSITIVE INFORMATION (never read/store/type)
 *  OTP, PIN, password, CVV, card number, authentication codes, banking
 *  credentials, biometric data. Password fields are skipped by the screen
 *  reader, OTP-like codes are redacted everywhere, and screen reading is
 *  disabled while a financial app is foreground (fail-safe: we cannot
 *  reliably tell a "public" screen from a PIN/OTP screen inside a wallet).
 *
 * LEVEL 3 — FINANCIAL TRANSACTIONS (automation always refused)
 *  Send money, receive money, cash out, bank transfer, payment, purchase,
 *  card transaction, recharge/payment transaction, payment confirmation,
 *  financial authorization. Tap/long-press/type automation is blocked inside
 *  financial apps (a tap is exactly how a transaction gets confirmed), and
 *  money-movement commands are refused before parsing. No confirmation is
 *  ever offered for these — they are simply not NUVA's to do.
 *
 * This object has NO Android dependency so it is fully unit-testable, and it
 * is enforced LOCALLY — no server/AI response can override it.
 */
object SensitiveAppPolicy {

    /**
     * Known financial packages + package fragments. Matching is
     * case-insensitive "contains" on the package name, which stays robust
     * across app renames (com.bkash.customerapp / com.bkash.walletapp).
     */
    private val FINANCIAL_PACKAGE_FRAGMENTS = listOf(
        // Mobile wallets (Bangladesh)
        "bkash", "nagad", "konasl.mobileapp", "rocket", "dbbl", "upay", "digipay",
        "mcash", "trustcloud", "mycash", "tapnpay", "ucash",
        // Banks / banking apps
        ".bank", "bank.", "banking", "bankapp",
        "citybank", "bracbank", "ebl", "dbblmobile", "primebank", "bankasia",
        "islamibank", "sonali", "janatabank", "ruton", "agrani", "pubalibank",
        "easternbank", "meghnabank", "mtb", "sebl",
        // Card / payment processors & fintech
        "payment", "paywell", "sslcommerz", "portwallet", "aamarpay", "shurjopay",
        "adyen", "stripe", "paypal", "venmo", "cashapp",
    ).map { it.trim().lowercase() }

    /**
     * Financial *display names* in every script NUVA understands. Used for the
     * LEVEL 2/3 foreground guard (and never to block opening the app itself).
     */
    private val FINANCIAL_NAME_KEYWORDS = listOf(
        "bkash", "b kash", "nagad", "rocket", "upay", "ucash", "mycash", "tap and pay",
        "mobile banking", "bank", "banking", "payment", "wallet", "cash",
        "বিকাশ", "বিক্যাশ", "নগদ", "রকেট", "উপায়", "উপাই", "মোবাইল ব্যাংকিং", "ব্যাংক",
        "ব্যাঙ্ক", "পেমেন্ট", "ওয়ালেট", "ক্যাশ",
    )

    /**
     * LEVEL 3 — transaction/money-movement requests. Refused regardless of
     * which app is named, before any parsing or server round-trip.
     */
    private val TRANSACTION_PATTERNS = listOf(
        // ACTION phrases only — never bare app names, so that LEVEL 1 "bkash
        // kholo" is never refused while "bkash diye taka pathao" always is.
        "send money", "cash out", "send taka", "taka pathao", "taka pathan", "tk pathao",
        "taka dao", "taka transfer", "transfer money", "bank transfer", "send tk",
        "add money", "top up", "mobile recharge", "recharge koro",
        "pay the bill", "bill pay", "payment koro", "payment korun", "pay koro",
        "make payment", "purchase koro", "buy koro with card", "card diye",
        "card payment", "confirm payment", "payment confirm", "transaction confirm",
        "authorize payment", "financial authorization",
        "সেন্ড মানি", "ক্যাশ আউট", "টাকা পাঠাও", "টাকা পাঠান", "টাকা দাও", "পয়সা পাঠাও",
        "ব্যাংক ট্রান্সফার", "লেনদেন করো", "লেনদেন নিশ্চিত",
        "পেমেন্ট করো", "পেমেন্ট করুন", "বিল পরিশোধ", "রিচার্জ করো", "কার্ড দিয়ে",
    )

    /** LEVEL 2 — credential/authentication material, never read/stored/typed. */
    private val CREDENTIAL_PATTERNS = listOf(
        "otp", "one time password", "pin number", "password", "passcode", "cvv", "cvc",
        "card number", "credit card", "debit card", "biometric", "2fa code", "seed phrase",
        "ওটিপি", "পিন নম্বর", "পিন নাম্বার", "পাসওয়ার্ড", "কার্ড নম্বার", "সিভিভি",
        "verification code", "one-time",
    )

    /** True when the package name belongs to a financial app. Null-safe. */
    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase().trim()
        return FINANCIAL_PACKAGE_FRAGMENTS.any { pkg.contains(it) }
    }

    /** True when a user-visible app name looks financial (any script). */
    fun isSensitiveAppName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase().trim()
        return FINANCIAL_NAME_KEYWORDS.any { normalized.contains(it) }
    }

    /** Convenience for the runtime foreground check. */
    fun isSensitive(packageName: String?, appName: String? = null): Boolean =
        isSensitivePackage(packageName) || isSensitiveAppName(appName)

    /** LEVEL 3: True when the raw command text asks for a money movement. */
    fun isTransactionRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return TRANSACTION_PATTERNS.any { normalized.contains(it) }
    }

    /** LEVEL 2: True when the command text mentions credential material. */
    fun mentionsCredentials(text: String): Boolean {
        val normalized = text.lowercase()
        return CREDENTIAL_PATTERNS.any { normalized.contains(it) }
    }

    /**
     * OTP-looking codes (4–8 digits, optionally spaced) — redacted from every
     * notification summary, screen read and confirmation text before it is
     * shown or spoken (LEVEL 2).
     */
    private val CODE_LIKE = Regex("""(?<!\d)(\d[ -]?){3,7}\d(?!\d)""")

    /** Redacts OTP/PIN-like digit runs from [text]. */
    fun redactCodes(text: String): String =
        if (mentionsCredentials(text)) text.replace(CODE_LIKE) { match ->
            if (match.value.count { it.isDigit() } in 4..8) "••••" else match.value
        } else {
            text.replace(Regex("""(?i)(otp|code|pin|verification code|one[- ]time)\D{0,12}((\d[ -]?){3,7}\d)""")) { m ->
                m.value.replace(Regex("""\d"""), "•")
            }
        }

    /**
     * LEVEL 3 refusal — exact product wording. No confirmation dialog is ever
     * shown for transactions: automation is simply refused, and the user is
     * reminded they can do it manually themselves.
     */
    val TRANSACTION_REFUSAL: String =
        "এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।"

    val TRANSACTION_REFUSAL_REASON: String = "blocked: financial transaction automation (level 3)"

    /** LEVEL 2 screen-automation refusal (spoken when reading is blocked). */
    val SCREEN_READ_GUARD_SPEECH: String =
        "Financial app er screen NUVA pore na — OTP, PIN ba balance er risk ache."

    /**
     * LEVEL 1 note spoken when NUVA opens a financial app for the user —
     * transparent about what it will and will not do there.
     */
    val LEVEL1_OPEN_NOTE: String =
        " — khol dicchi. Khoj kora, scroll, back-home sob thik ache; kintu taka pathano ba payment NUVA kore na."

    /** Refusal for the raw command text (checked before any parsing). */
    fun refusalForText(text: String): Refusal? =
        if (isTransactionRequest(text)) Refusal(TRANSACTION_REFUSAL_REASON) else null

    data class Refusal(val reason: String)

    /**
     * Messaging apps NUVA can automate per tier:
     *  FULL   — open chat, type, and send after user confirmation (WhatsApp),
     *           or platform send after confirmation (SMS).
     *  COMPOSE— opens the app with the message pre-filled via the share
     *           intent; the user picks the chat and taps Send (Telegram,
     *           Messenger, Signal, Viber, IMO). Reliable, nothing is sent
     *           without the user's own tap.
     */
    enum class MessagingTier { FULL, COMPOSE }

    fun tierOf(app: MessagingApp): MessagingTier = when (app) {
        MessagingApp.WHATSAPP, MessagingApp.SMS -> MessagingTier.FULL
        else -> MessagingTier.COMPOSE
    }

    fun messagingCatalogue(): List<Triple<MessagingApp, MessagingTier, String>> =
        MessagingApp.entries.map { app ->
            Triple(app, tierOf(app), describeTier(tierOf(app)))
        }

    fun describeTier(tier: MessagingTier): String = when (tier) {
        MessagingTier.FULL -> "নিশ্চিতকরণের পরে সরাসরি পাঠায়"
        MessagingTier.COMPOSE -> "মেসেজ লেখা বসিয়ে অ্যাপ খোলে — Send আপনি চাপবেন"
    }
}
