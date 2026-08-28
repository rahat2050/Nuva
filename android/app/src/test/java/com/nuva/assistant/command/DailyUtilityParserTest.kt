package com.nuva.assistant.command

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyUtilityParserTest {

    private fun answer(text: String): DailyUtilityParser.Result =
        DailyUtilityParser.parse(text, today = LocalDate.of(2026, 8, 26), randomInt = { from, _ -> from })!!

    @Test
    fun `data driven converter represents more than one thousand command forms`() {
        assertTrue(DailyUtilityParser.supportedCommandForms() >= 1_000)
    }

    @Test
    fun `arithmetic works offline with precedence parentheses and three languages`() {
        assertEquals("Uttor: 14.", answer("2 + 3 * 4 koto").answer)
        assertEquals("Answer: 20.", answer("calculate (2 + 3) * 4").answer)
        assertEquals("উত্তর: 42।", answer("৬ গুণ ৭ কত").answer)
        assertEquals("Uttor: 300.", answer("500 theke 200 biyog koro").answer)
        assertNull(DailyUtilityParser.parse("10 / 0 koto"))
    }

    @Test
    fun `special math supports roots powers and factorial`() {
        assertEquals("Uttor: 9.", answer("sqrt 81 koto").answer)
        assertEquals("Uttor: 144.", answer("12 squared").answer)
        assertEquals("Uttor: 120.", answer("factorial 5").answer)
    }

    @Test
    fun `percentage discount vat and relationship calculations work`() {
        assertEquals("Uttor: 100.", answer("500 er 20 percent koto").answer)
        assertEquals("Uttor: 400.", answer("500 er 20 percent discount").answer)
        assertEquals("Uttor: 575.", answer("500 er 15 percent vat add").answer)
        assertEquals("Answer: 25%.", answer("50 is what percent of 200").answer)
    }

    @Test
    fun `bill tip and split calculations work`() {
        assertTrue(answer("1000 takar bill 4 jon e vag koro").answer.contains("250"))
        val withTip = answer("1000 bill 10 percent tip 4 jon e split")
        assertTrue(withTip.answer.contains("275"))
        assertEquals("tip", answer("1000 bill e 10 percent tip").category)
    }

    @Test
    fun `common unit conversions cover international and bangladesh units`() {
        assertTrue(answer("5 kilometer mile e koto").answer.contains("3.106856 mile"))
        assertTrue(answer("10 kg pound e convert koro").answer.contains("22.046226 pound"))
        assertTrue(answer("100 fahrenheit to celsius").answer.contains("37.777778 celsius"))
        assertTrue(answer("2 katha square foot e koto").answer.contains("1440"))
        assertTrue(answer("1 gigabyte megabyte e koto").answer.contains("1024 megabyte"))
        assertTrue(answer("1 cup milliliter e koto").answer.contains("236.588237 milliliter"))
    }

    @Test
    fun `bmi emi mileage and bill utilities give bounded honest answers`() {
        val bmi = answer("weight 70 kg height 170 cm bmi koto")
        assertEquals("bmi", bmi.category)
        assertTrue(bmi.answer.contains("24.221453"))
        assertTrue(bmi.answer.contains("medical advice"))

        val emi = answer("100000 loan 12 percent 2 year emi koto")
        assertEquals("emi", emi.category)
        assertTrue(emi.answer.contains("4707.347"))

        assertEquals("Uttor: 15 km/liter.", answer("300 km 20 liter mileage koto").answer)
    }

    @Test
    fun `age weekday days remaining and date differences work`() {
        assertEquals("Uttor: 26 bochor.", answer("born 2000 age koto").answer)
        assertEquals("Uttor: Budhbar.", answer("2026-08-26 ki bar").answer)
        assertEquals("Uttor: 112 din.", answer("2026-12-16 koto din baki").answer)
        assertEquals("Answer: 15 days.", answer("difference between 2026-08-01 and 2026-08-16").answer)
    }

    @Test
    fun `coin dice and bounded random choices work with injectable randomness`() {
        assertEquals("Uttor: Head.", answer("coin toss koro").answer)
        assertEquals("Uttor: 1.", answer("roll dice").answer)
        assertEquals("Uttor: 10.", answer("random number 10 theke 20").answer)
    }

    @Test
    fun `unrelated conversation is not mistaken for a utility`() {
        assertNull(DailyUtilityParser.parse("amar jonno ekta kobita likho"))
        assertNull(DailyUtilityParser.parse("youtube khulo"))
        assertNull(DailyUtilityParser.parse("volume 55 percent"))
        assertNotNull(DailyUtilityParser.parse("10 mile kilometer e koto"))
    }
}
