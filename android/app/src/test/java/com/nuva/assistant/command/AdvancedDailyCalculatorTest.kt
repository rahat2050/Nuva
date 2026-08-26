package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedDailyCalculatorTest {

    private fun answer(text: String) = AdvancedDailyCalculator.parse(text)!!

    @Test
    fun `statistics ratio and grades work offline`() {
        assertEquals("average", answer("average of 10 20 30").category)
        assertTrue(answer("average of 10 20 30").answer.contains("20"))
        assertTrue(answer("median 9 1 5 3").answer.contains("4"))
        assertTrue(answer("ratio 20 30").answer.contains("2:3"))
        assertTrue(answer("grade percentage 450 out of 500").answer.contains("90%"))
    }

    @Test
    fun `interest profit price and savings formulas work`() {
        assertTrue(answer("10000 simple interest 10 percent 2 year").answer.contains("2000"))
        assertTrue(answer("10000 compound interest 10 percent 2 year").answer.contains("2100"))
        assertTrue(answer("buying price 500 selling price 650 profit").answer.contains("150"))
        assertTrue(answer("total 500 for 10 item unit price").answer.contains("50"))
        assertTrue(answer("savings goal 12000 in 6 months monthly save").answer.contains("2000"))
    }

    @Test
    fun `travel and download estimates work`() {
        assertTrue(answer("120 km 60 kmph travel time koto").answer.contains("2 hour"))
        assertTrue(answer("trip fuel cost 300 km 15 km/l 130 taka per liter").answer.contains("2600"))
        assertTrue(answer("1 GB 100 Mbps download time").answer.contains("1 minute 22 second"))
    }

    @Test
    fun `geometry formulas work`() {
        assertTrue(answer("rectangle area 10 5").answer.contains("50"))
        assertTrue(answer("circle area radius 7").answer.contains("153.938"))
        assertTrue(answer("triangle area base 10 height 8").answer.contains("40"))
        assertTrue(answer("cuboid volume 2 3 4").answer.contains("24"))
    }

    @Test
    fun `health estimates are bounded and carry disclaimers`() {
        val bmr = answer("male 70 kg 175 cm 30 year BMR")
        assertTrue(bmr.answer.contains("1648.75"))
        assertTrue(bmr.answer.contains("not medical advice"))
        val water = answer("70 kg daily water intake")
        assertTrue(water.answer.contains("2.45"))
        assertTrue(water.answer.contains("medical needs vary"))
    }

    @Test
    fun `unrelated text and incomplete formulas do not guess`() {
        assertNull(AdvancedDailyCalculator.parse("youtube khulo"))
        assertNull(AdvancedDailyCalculator.parse("simple interest koto"))
        assertNull(AdvancedDailyCalculator.parse("download time 1 GB"))
    }
}
