package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaMpReliefTest {
    private val dir = File("src/simpleTest/resources/arena-v5-published23")
    private val codec = Json { ignoreUnknownKeys = true }
    private val costs by lazy {
        codec.decodeFromString<Map<String, List<Int>>>(File(dir, "costs.json").readText())
    }

    @Test fun `all attack and support ranks receive forty percent relief with whole MP rounding`() {
        var before = 0
        var after = 0
        for (d in ArenaIdentityCatalog.values) {
            val now = (1..10).map { rank -> ArenaIdentityCopy.preview(d.id, rank).mp }
            val prior = costs.getValue(d.id)
            now.forEachIndexed { index, mp ->
                assertTrue("${d.id} rank ${index + 1}", mp > 0)
                assertEquals("${d.id} rank ${index + 1}", prior[index] * .6, mp.toDouble(), .5)
                before += prior[index]
                after += mp
            }
            assertTrue(now.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("Rank progression disappeared for ${d.id}", now.last() > now.first())
        }
        println("MP_RELIEF_COSTS,before=$before,after=$after,saved=${1.0 - after.toDouble() / before}")
        assertTrue(after.toDouble() / before in .59.. .61)
    }

    @Test fun `published V5 replays keep their original costs and outcomes`() {
        repeat(18) { i ->
            val pair = codec.decodeFromString<List<ArenaSupportInput>>(File(dir, "$i.json").readText())
            val result = ArenaSupportTurnEngine.simulate(pair[0], pair[1], File(dir, "$i.seed").readText().toLong())
            val hash = MessageDigest.getInstance("SHA-256").digest(result.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(File(dir, "$i.sha256").readText(), hash)
            for (old in pair) {
                val current = old.copy(identity = null).withIdentityRules()
                old.identity!!.skills.zip(current.identity!!.skills).forEach { (prior, now) ->
                    assertEquals("Only MP may change for ${prior.id}", prior, now.copy(mp = prior.mp))
                }
            }
        }
    }

    @Test fun `paired legal builds gain mana headroom across levels without changing stats`() {
        val factory = ArenaIdentityBalanceTest()
        for (level in listOf(10, 15, 20, 25, 30, 40, 50, 75, 100)) {
            val cache = mutableMapOf<Triple<HeroClass, Int, ArenaAutoBuildPreset>, ArenaSupportInput>()
            fun current(c: HeroClass, seed: Int, preset: ArenaAutoBuildPreset) =
                cache.getOrPut(Triple(c, seed, preset)) { factory.profile(c, level, seed, preset) }
            fun published(input: ArenaSupportInput): ArenaSupportInput {
                val identity = requireNotNull(input.identity)
                return input.copy(identity = identity.copy(skills = identity.skills.map {
                    it.copy(mp = costs.getValue(it.id)[it.rank - 1])
                }))
            }
            for (c in HeroClass.entries) {
                var before = 0.0
                var after = 0.0
                var count = 0
                var beforeBasics = 0
                var afterBasics = 0
                for (preset in ArenaAutoBuildPreset.entries) {
                    for (other in HeroClass.entries.filter { it != c }) repeat(4) { seed ->
                        val a = current(c, seed, preset)
                        val b = current(other, seed xor 1, ArenaAutoBuildPreset.entries[(preset.ordinal + seed * 3) % 10])
                        val runSeed = 51930000L + level * 10000 + c.ordinal * 1000 + preset.ordinal * 10 + seed
                        val oldResult = ArenaSupportTurnEngine.simulate(published(a), published(b), runSeed)
                        val newResult = ArenaSupportTurnEngine.simulate(a, b, runSeed)
                        assertEquals(ArenaRunStatus.COMPLETED, oldResult.status)
                        assertEquals(ArenaRunStatus.COMPLETED, newResult.status)
                        val oldFighter = oldResult.fighters.getValue(a.fighter.id)
                        val newFighter = newResult.fighters.getValue(a.fighter.id)
                        assertEquals(oldFighter.maxMpUnits, newFighter.maxMpUnits)
                        before += 100.0 * oldFighter.mpUnits / oldFighter.maxMpUnits
                        after += 100.0 * newFighter.mpUnits / newFighter.maxMpUnits
                        fun basics(r: ArenaSupportResult) = r.events.count {
                            it.actorId == a.fighter.id && it.type == ArenaSupportEventType.CAST_START && it.actionId == "BASIC_ATTACK"
                        }
                        beforeBasics += basics(oldResult)
                        afterBasics += basics(newResult)
                        count++
                    }
                }
                println("MP_RELIEF_PAIRED,$level,$c,n=$count,before=${before / count},after=${after / count},beforeBasics=$beforeBasics,afterBasics=$afterBasics")
                assertTrue("L$level $c has no additional MP headroom", after > before)
            }
        }
    }
}
