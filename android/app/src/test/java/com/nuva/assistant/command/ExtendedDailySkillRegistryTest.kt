package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedDailySkillRegistryTest {

    @Test
    fun `registry generates exactly five hundred unique skills`() {
        assertEquals(500, ExtendedDailySkillRegistry.EXPECTED_SKILL_COUNT)
        assertEquals(500, ExtendedDailySkillRegistry.skills.size)
        assertEquals(500, ExtendedDailySkillRegistry.skills.map { it.id }.toSet().size)
        assertEquals(
            mapOf("local_service" to 200, "public_service" to 100, "learning" to 100, "product_help" to 100),
            ExtendedDailySkillRegistry.skills.groupingBy { it.category }.eachCount(),
        )
    }

    @Test
    fun `service matrix resolves entity plus task in three languages`() {
        assertEquals(
            "local_service_private_tutor_nearby",
            ExtendedDailySkillRegistry.resolve("nearby private tutor")!!.skill.id,
        )
        assertEquals(
            "local_service_tailor_hours",
            ExtendedDailySkillRegistry.resolve("darji kokhon khole")!!.skill.id,
        )
        assertEquals(
            "local_service_physiotherapist_contact",
            ExtendedDailySkillRegistry.resolve("ফিজিওথেরাপিস্ট ফোন নম্বর")!!.skill.id,
        )
    }

    @Test
    fun `public learning and product matrices resolve precisely`() {
        assertEquals(
            "public_service_passport_documents",
            ExtendedDailySkillRegistry.resolve("passport ki kagoj lagbe")!!.skill.id,
        )
        assertEquals(
            "learning_excel_tutorial",
            ExtendedDailySkillRegistry.resolve("excel tutorial")!!.skill.id,
        )
        assertEquals(
            "product_help_washing_machine_repair",
            ExtendedDailySkillRegistry.resolve("washing machine repair")!!.skill.id,
        )
        assertEquals(
            "product_help_router_manual",
            ExtendedDailySkillRegistry.resolve("রাউটার ব্যবহারের নিয়ম")!!.skill.id,
        )
    }

    @Test
    fun `model location and reference details survive in query`() {
        val repair = ExtendedDailySkillRegistry.resolve("LG F4 washing machine repair error UE")!!
        assertTrue(repair.query.contains("lg f4"))
        assertTrue(repair.query.contains("error ue"))
        val service = ExtendedDailySkillRegistry.resolve("uttara private tutor contact number")!!
        assertTrue(service.query.contains("uttara"))
    }

    @Test
    fun `one slot alone never triggers a generated skill`() {
        assertNull(ExtendedDailySkillRegistry.resolve("passport"))
        assertNull(ExtendedDailySkillRegistry.resolve("required documents"))
        assertNull(ExtendedDailySkillRegistry.resolve("washing machine"))
        assertNull(ExtendedDailySkillRegistry.resolve("repair"))
        assertNull(ExtendedDailySkillRegistry.resolve("youtube khulo"))
    }

    @Test
    fun `all ids and queries are bounded and search safe`() {
        val idPattern = Regex("^[a-z0-9_]{3,100}$")
        ExtendedDailySkillRegistry.skills.forEach { skill ->
            assertTrue(skill.id, idPattern.matches(skill.id))
            assertTrue(skill.defaultQuery.length in 3..200)
        }
        assertNotNull(ExtendedDailySkillRegistry.resolve("laptop current price"))
    }
}
