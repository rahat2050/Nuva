package com.nuva.assistant.command

/**
 * Smart failure recovery (v1.4): classify WHY an action failed so NUVA can
 * explain in Bangla and decide whether a single safe retry is appropriate —
 * instead of blindly repeating. Pure Kotlin → unit-testable.
 */
object FailureClassifier {

    enum class Kind {
        TARGET_CHANGED,
        PERMISSION_MISSING,
        APP_UNAVAILABLE,
        UI_CHANGED,
        NETWORK,
        TIMEOUT,
        UNSUPPORTED,
        UNKNOWN,
    }

    fun classify(error: String?): Kind {
        val e = error.orEmpty().lowercase()
        return when {
            e.contains("sensitive") || e.contains("financial transaction") -> Kind.UNSUPPORTED
            e.contains("permission") || e.contains("access missing") || e.contains("accessibility missing") ->
                Kind.PERMISSION_MISSING
            e.contains("not found") || e.contains("not installed") || e.contains("app missing") ||
                e.contains("whatsapp missing") || e.contains("painai") ->
                Kind.APP_UNAVAILABLE
            e.contains("node") || e.contains("field") || e.contains("button") || e.contains("verify") ||
                e.contains("recipient") || e.contains("ui") ->
                Kind.UI_CHANGED
            e.contains("network") || e.contains("internet") || e.contains("backend") ->
                Kind.NETWORK
            e.contains("timeout") || e.contains("timed out") -> Kind.TIMEOUT
            e.contains("not supported") || e.contains("support kori na") || e.contains("restriction") ||
                e.contains("sorasori") ->
                Kind.UNSUPPORTED
            e.isBlank() -> Kind.UNKNOWN
            else -> Kind.UNKNOWN
        }
    }

    /** Concise Bangla-first explanation, per product copy. */
    fun userSpeech(kind: Kind): String = when (kind) {
        Kind.TARGET_CHANGED -> "স্ক্রিন বদলে গেছে — আবার চেষ্টা করব?"
        Kind.PERMISSION_MISSING -> "এই কাজটির জন্য permission লাগবে — NUVA-র Settings থেকে দিয়ে নিন।"
        Kind.APP_UNAVAILABLE -> "অ্যাপটি পাওয়া যায়নি — Play Store সাজেশন দেখুন।"
        Kind.UI_CHANGED -> "অ্যাপের স্ক্রিন পরিবর্তন হয়েছে, নিরাপত্তার জন্য থামলাম — এলোমেলো চাপ দিই না।"
        Kind.NETWORK -> "নেটওয়ার্ক সমস্যা — একটু পরে আবার চেষ্টা করুন।"
        Kind.TIMEOUT -> "অপেক্ষা করেও সাড়া পাইনি — আবার বললে আরেকবার চেষ্টা করব।"
        Kind.UNSUPPORTED -> "এই actionটি NUVA করতে পারে না।"
        Kind.UNKNOWN -> "কাজটি করতে সমস্যা হয়েছে।"
    }

    /**
     * Only these get a single automatic retry — transient conditions where
     * repeating the exact same validated action is harmless. Everything else
     * (permission, unsupported, security, network) just stops and explains.
     */
    fun canSafeRetry(kind: Kind): Boolean =
        kind == Kind.TIMEOUT || kind == Kind.UI_CHANGED
}
