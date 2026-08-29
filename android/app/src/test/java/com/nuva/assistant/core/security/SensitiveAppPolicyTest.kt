package com.nuva.assistant.core.security

import com.nuva.assistant.command.MessagingApp
import com.nuva.assistant.command.NuvaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STRICT SECURITY EXCLUSIONS tests (§32–§36): the denylist that can never
 * depend on a server decision.
 */
class SensitiveAppPolicyTest {

    @Test
    fun `known wallet and banking packages are denied`() {
        assertTrue(SensitiveAppPolicy.isSensitivePackage("com.bKash.customerapp"))
        assertTrue(SensitiveAppPolicy.isSensitivePackage("com.bkash.walletapp"))
        assertTrue(SensitiveAppPolicy.isSensitivePackage("com.konasl.mobileapp")) // Nagad
        assertTrue(SensitiveAppPolicy.isSensitivePackage("bd.com.dbbl.mobilebanking"))
        assertTrue(SensitiveAppPolicy.isSensitivePackage("com.citybank.bbl"))
        assertTrue(SensitiveAppPolicy.isSensitivePackage("com.sslcommerz.payment"))
    }

    @Test
    fun `regular packages are not denied`() {
        assertFalse(SensitiveAppPolicy.isSensitivePackage("com.google.android.youtube"))
        assertFalse(SensitiveAppPolicy.isSensitivePackage("com.whatsapp"))
        assertFalse(SensitiveAppPolicy.isSensitivePackage("com.nuva.assistant"))
        assertFalse(SensitiveAppPolicy.isSensitivePackage(null))
        assertFalse(SensitiveAppPolicy.isSensitivePackage(""))
    }

    @Test
    fun `sensitive app names in every script are denied`() {
        assertTrue(SensitiveAppPolicy.isSensitiveAppName("bKash"))
        assertTrue(SensitiveAppPolicy.isSensitiveAppName("Nagad"))
        assertTrue(SensitiveAppPolicy.isSensitiveAppName("বিকাশ"))
        assertTrue(SensitiveAppPolicy.isSensitiveAppName("নগদ"))
        assertTrue(SensitiveAppPolicy.isSensitiveAppName("City Bank"))
        assertFalse(SensitiveAppPolicy.isSensitiveAppName("YouTube"))
        assertFalse(SensitiveAppPolicy.isSensitiveAppName("WhatsApp"))
    }

    @Test
    fun `transaction requests are refused regardless of wording`() {
        assertTrue(SensitiveAppPolicy.isTransactionRequest("bkash diye 500 taka pathao"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("৫০০০ টাকা পাঠাও"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("send money to rahim"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("cash out 1000"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("card diye payment koro"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("bank transfer koro"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("100 taka recharge koro"))
        assertTrue(SensitiveAppPolicy.isTransactionRequest("confirm payment koro"))
        // LEVEL 1 stays allowed: merely opening the app is not a transaction.
        assertFalse(SensitiveAppPolicy.isTransactionRequest("bkash kholo"))
        assertFalse(SensitiveAppPolicy.isTransactionRequest("rahim ke call koro"))
    }

    @Test
    fun `level 3 refusal uses the exact product wording and never asks confirmation`() {
        assertNotNull(SensitiveAppPolicy.refusalForText("bkash e 1000 taka pathao"))
        assertTrue(
            SensitiveAppPolicy.TRANSACTION_REFUSAL.contains(
                "এই financial transaction NUVA নিজে করতে পারবে না। আপনি চাইলে নিজে manually করতে পারবেন।",
            ),
        )
        assertNull(SensitiveAppPolicy.refusalForText("youtube khulo"))
    }

    @Test
    fun `messaging tiers are honest`() {
        assertEquals(SensitiveAppPolicy.MessagingTier.FULL, SensitiveAppPolicy.tierOf(MessagingApp.WHATSAPP))
        assertEquals(SensitiveAppPolicy.MessagingTier.FULL, SensitiveAppPolicy.tierOf(MessagingApp.SMS))
        assertEquals(SensitiveAppPolicy.MessagingTier.COMPOSE, SensitiveAppPolicy.tierOf(MessagingApp.TELEGRAM))
        assertEquals(SensitiveAppPolicy.MessagingTier.COMPOSE, SensitiveAppPolicy.tierOf(MessagingApp.MESSENGER))
    }

    @Test
    fun `credential mentions are detected`() {
        assertTrue(SensitiveAppPolicy.mentionsCredentials("amar otp ta poro"))
        assertTrue(SensitiveAppPolicy.mentionsCredentials("CVV ta bole dao"))
        assertTrue(SensitiveAppPolicy.mentionsCredentials("পাসওয়ার্ড টাইপ করো"))
        assertFalse(SensitiveAppPolicy.mentionsCredentials("battery koto"))
    }

    @Test
    fun `otp-like codes are redacted`() {
        assertEquals(
            "Your OTP is ••••",
            SensitiveAppPolicy.redactCodes("Your OTP is 4321"),
        )
        val redacted = SensitiveAppPolicy.redactCodes("verification code 987654")
        assertFalse(redacted.contains("987654"))
        assertEquals(
            "Sensitive content hidden",
            SensitiveAppPolicy.safeTextForDisplay("Password: hunter2"),
        )
        assertFalse(SensitiveAppPolicy.safeTextForDisplay("OTP 1234").contains("1234"))
        // Normal numbers survive when there is no credential context.
        assertTrue(SensitiveAppPolicy.redactCodes("battery 73 percent").contains("73"))
    }

    @Test
    fun `structured actions cannot inject credentials or transactions`() {
        val credential = NuvaAction.SendMessage(
            app = MessagingApp.WHATSAPP,
            contact = "Rahim",
            message = "amar OTP 4321",
            phoneNumber = "+8801712345678",
        )
        val transaction = NuvaAction.TypeText("please send money now", target = null, submit = false)
        val safe = NuvaAction.SendMessage(
            app = MessagingApp.SMS,
            contact = "Rahim",
            message = "ami 10 minute pore ashchi",
            phoneNumber = "+8801712345678",
        )

        assertEquals(
            SensitiveAppPolicy.CREDENTIAL_REFUSAL_REASON,
            SensitiveAppPolicy.refusalForAction(credential)?.reason,
        )
        assertEquals(
            SensitiveAppPolicy.TRANSACTION_REFUSAL_REASON,
            SensitiveAppPolicy.refusalForAction(transaction)?.reason,
        )
        assertNull(SensitiveAppPolicy.refusalForAction(safe))
    }

    @Test
    fun `opening a financial app is level 1 allowed and never blocked by this policy`() {
        // (v1.2) Launching is allowed; only transactions are refused. The
        // runtime accessibility guard is what blocks in-app taps/typing.
        assertNull(SensitiveAppPolicy.refusalForText("bkash kholo"))
        assertNull(SensitiveAppPolicy.refusalForText("nagad open koro"))
        assertNull(SensitiveAppPolicy.refusalForAction(NuvaAction.OpenApp("bKash", "com.bkash.walletapp")))
    }
}
