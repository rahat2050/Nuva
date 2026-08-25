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
    fun `money transfer requests are refused regardless of wording`() {
        assertTrue(SensitiveAppPolicy.isMoneyTransferRequest("bkash diye 500 taka pathao"))
        assertTrue(SensitiveAppPolicy.isMoneyTransferRequest("৫০০০ টাকা পাঠাও"))
        assertTrue(SensitiveAppPolicy.isMoneyTransferRequest("send money to rahim"))
        assertTrue(SensitiveAppPolicy.isMoneyTransferRequest("cash out 1000"))
        assertFalse(SensitiveAppPolicy.isMoneyTransferRequest("rahim ke call koro"))
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
        // Normal numbers survive when there is no credential context.
        assertTrue(SensitiveAppPolicy.redactCodes("battery 73 percent").contains("73"))
    }

    @Test
    fun `opening a banking app by voice is refused before execution`() {
        val refusal = SensitiveAppPolicy.refusalFor(NuvaAction.OpenApp("bkash", null))
        assertNotNull(refusal)

        val byBanglaName = SensitiveAppPolicy.refusalFor(NuvaAction.OpenApp("নগদ", null))
        assertNotNull(byBanglaName)

        assertNull(SensitiveAppPolicy.refusalFor(NuvaAction.OpenApp("youtube", null)))
    }

    @Test
    fun `money transfer text is refused before parsing`() {
        assertNotNull(SensitiveAppPolicy.refusalForText("bkash e 1000 taka pathao"))
        assertNull(SensitiveAppPolicy.refusalForText("youtube khulo"))
    }

    @Test
    fun `messaging support is honest`() {
        assertNull(SensitiveAppPolicy.unsupportedMessaging(MessagingApp.WHATSAPP))
        assertNull(SensitiveAppPolicy.unsupportedMessaging(MessagingApp.SMS))
        assertTrue(SensitiveAppPolicy.unsupportedMessaging(MessagingApp.TELEGRAM)!!.contains("support kori na"))
    }
}
