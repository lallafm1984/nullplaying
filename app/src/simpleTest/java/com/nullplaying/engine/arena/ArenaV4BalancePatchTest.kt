package com.nullplaying.engine.arena

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaV4BalancePatchTest {
    private val dir = File("src/simpleTest/resources/arena-v4-published22")
    private val codec = Json { ignoreUnknownKeys = true }
    private fun pair(i: Int) = codec.decodeFromString<List<ArenaSupportInput>>(File(dir, "$i.json").readText())

    @Test fun `published version 22 V4 battles replay byte identically`() {
        repeat(18) { i ->
            val p = pair(i)
            val result = ArenaSupportTurnEngine.simulate(p[0], p[1], File(dir, "$i.seed").readText().toLong())
            val hash = MessageDigest.getInstance("SHA-256").digest(result.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(File(dir, "$i.sha256").readText(), hash)
        }
    }

    @Test fun `published version 23 relief preserved every rank increase`() {
        val old = codec.decodeFromString<Map<String, List<Int>>>(File(dir, "costs.json").readText())
        val published = codec.decodeFromString<Map<String, List<Int>>>(
            File("src/simpleTest/resources/arena-v5-published23/costs.json").readText())
        var cheaper = 0
        for (d in ArenaIdentityCatalog.values) {
            val now = published.getValue(d.id)
            val prior = old.getValue(d.id)
            assertTrue(now.all { it > 0 })
            assertTrue(now.zipWithNext().all { (a, b) -> b > a })
            val savings = prior.zip(now).map { (a, b) -> a - b }
            assertEquals("Rank growth changed: ${d.id}", 1, savings.distinct().size)
            assertTrue(savings.all { it >= 0 && it <= prior.first() * .10 })
            if (savings.first() > 0) cheaper++
        }
        println("PATCH_COSTS,discountedSkills=$cheaper,total=${old.size}")
        assertTrue(cheaper >= 100)
    }

    @Test fun `vigilance has weaker evasion but retains duration charges and milestones`() {
        val first = ArenaIdentityCopy.preview("ARENA_SUP_RANGER_06", 1)
        val mastered = ArenaIdentityCopy.preview("ARENA_SUP_RANGER_06", 10)
        assertEquals(15.0, first.magnitude, 0.0)
        assertEquals(22.0, mastered.magnitude, 0.0)
        assertEquals(6, first.duration)
        assertEquals(8, first.cooldown)
        assertEquals(4, first.charges)
        assertEquals(5, mastered.charges)
    }

    @Test fun `paired published fixtures measure mana before and after the patch`() {
        val oldMana = mutableListOf<Double>()
        val newMana = mutableListOf<Double>()
        repeat(18) { i ->
            val old = pair(i)
            val now = old.map { it.copy(identity = null).withIdentityRules() }
            val before = mutableListOf<Double>()
            val after = mutableListOf<Double>()
            repeat(128) { n ->
                val seed = 782910L + i * 1000 + n
                for ((p, values) in listOf(old to before, now to after)) {
                    val result = ArenaSupportTurnEngine.simulate(p[0], p[1], seed, recordEvents = false)
                    assertEquals(ArenaRunStatus.COMPLETED, result.status)
                    val f = result.fighters.getValue(p[0].fighter.id)
                    values += 100.0 * f.mpUnits / f.maxMpUnits
                }
            }
            oldMana += before
            newMana += after
            println("PATCH_MP,$i,${old[0].fighter.heroClass},before=${before.average()},after=${after.average()}")
        }
        println("PATCH_MP_TOTAL,battles=${oldMana.size},before=${oldMana.average()},after=${newMana.average()}")
        assertTrue("MP relief did not create aggregate headroom", newMana.average() > oldMana.average())
    }
}
