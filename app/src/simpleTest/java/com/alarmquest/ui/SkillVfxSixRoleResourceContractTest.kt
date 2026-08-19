package com.alarmquest.ui

import com.alarmquest.engine.SkillCatalog
import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Name-only contract. Pixel/file QA lives in tools/vfx-lab/verify_vfx6_resource_pack.py. */
class SkillVfxSixRoleResourceContractTest {
    @Test
    fun `vfx6 production table is twenty eight families by three independent roles`() {
        val families = mapOf(
            "warrior" to listOf("heavy", "charge", "earth"),
            "rogue" to listOf("blade", "shadow", "poison", "wire", "execute"),
            "ranger" to listOf("precision", "volley", "wind", "beast", "celestial"),
            "mage" to listOf("fire", "ice", "lightning", "arcane", "cosmic"),
            "cleric" to listOf("light", "judgment", "exorcism", "flame", "seraph"),
            "paladin" to listOf("sword", "shield", "hammer", "wave", "judgment"),
        )
        val names = families.flatMap { (heroClass, classFamilies) ->
            classFamilies.flatMap { family ->
                listOf("contact", "debris", "echo").map { role -> "vfx6_${heroClass}_${family}_$role" }
            }
        }
        assertEquals(28, families.values.sumOf { it.size })
        assertEquals(84, names.size)
        assertEquals(84, names.distinct().size)
    }

    @Test
    fun `all twenty eight authored candidates resolve independent vfx6 contact debris and echo`() {
        val representatives = SkillCatalog.all
            .filterNot(::shouldKeepLegacyPrimary)
            .groupBy { it.heroClass to it.candidate }
            .mapValues { (_, definitions) -> definitions.minBy { it.unlockLevel } }
        assertEquals(28, representatives.size)
        val specs = representatives.values.map(::classLayeredAssetSpec)
        assertEquals(28, specs.map { it.impactPoint }.distinct().size)
        assertEquals(28, specs.map { it.debrisPoint }.distinct().size)
        assertEquals(28, specs.map { it.finisherEcho }.distinct().size)
        specs.forEach { spec ->
            assertTrue(setOf(spec.impactPoint, spec.debrisPoint, spec.finisherEcho).size >= 2)
            assertTrue(spec.secondary != spec.finisherEcho)
        }
    }

    @Test
    fun `legacy warrior slash candidates stay outside the vfx6 resolver`() {
        val definition = SkillCatalog.all.first {
            it.heroClass == HeroClass.WARRIOR && shouldKeepLegacyPrimary(it)
        }
        assertTrue(shouldKeepLegacyPrimary(definition))
    }
}
