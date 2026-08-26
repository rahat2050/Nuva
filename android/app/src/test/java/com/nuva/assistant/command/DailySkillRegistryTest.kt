package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySkillRegistryTest {

    @Test
    fun `registry contains exactly one hundred unique practical skills`() {
        assertEquals(100, DailySkillRegistry.skills.size)
        assertEquals(100, DailySkillRegistry.skills.map { it.id }.toSet().size)
        assertEquals(
            setOf("health", "travel", "household", "education", "work", "finance_info", "faith", "civic", "emergency", "digital", "safety", "lifestyle"),
            DailySkillRegistry.skills.map { it.category }.toSet(),
        )
    }

    @Test
    fun `bangla banglish and english aliases resolve`() {
        assertEquals("nearby_pharmacy", DailySkillRegistry.resolve("kacher pharmacy")!!.skill.id)
        assertEquals("train_schedule", DailySkillRegistry.resolve("ট্রেনের সময়সূচি")!!.skill.id)
        assertEquals("courier_tracking", DailySkillRegistry.resolve("parcel tracking ZX123")!!.skill.id)
        assertEquals("passport_info", DailySkillRegistry.resolve("passport application")!!.skill.id)
        assertEquals("fact_check", DailySkillRegistry.resolve("news verification")!!.skill.id)
    }

    @Test
    fun `details in a command are preserved in the live query`() {
        val parcel = DailySkillRegistry.resolve("parcel tracking ZX123 Bangladesh")!!
        assertTrue(parcel.query.contains("zx123"))
        val bare = DailySkillRegistry.resolve("nearby hospital")!!
        assertEquals("nearby hospital", bare.query)
    }

    @Test
    fun `ordinary conversation and executable commands do not match`() {
        assertNull(DailySkillRegistry.resolve("tumi kemon acho"))
        assertNull(DailySkillRegistry.resolve("youtube khulo"))
        assertNull(DailySkillRegistry.resolve("rahim ke call koro"))
    }

    @Test
    fun `financial skills are information only`() {
        val finance = DailySkillRegistry.skills.filter { it.category == "finance_info" }
        assertEquals(10, finance.size)
        val forbidden = listOf("send money", "cash out", "payment koro", "টাকা পাঠাও", "পেমেন্ট করো")
        finance.forEach { skill ->
            val haystack = (skill.defaultQuery + " " + skill.aliases.joinToString(" ")).lowercase()
            forbidden.forEach { term -> assertTrue("${skill.id} contains $term", !haystack.contains(term)) }
        }
        assertNotNull(DailySkillRegistry.resolve("current exchange rate"))
    }
}
