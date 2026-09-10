package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.SimpleGameState
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit export only; this fixture never ships in an APK or connects to a server. */
class ArenaReportedRangerDeviceFixtureTest {
    @Test fun `export the photographed stats and legal allocation for the isolated emulator`() {
        val directory=System.getenv("ARENA_REPORTED_FIXTURE_DIR")
        assumeTrue(!directory.isNullOrBlank())
        val dir=File(requireNotNull(directory))
        val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
        val state=json.decodeFromString<SimpleGameState>(File(dir,"device-input.json").readText())
        assertEquals(HeroClass.RANGER,state.hero.heroClass)
        state.hero.name="임방치"
        state.hero.level=15
        state.hero.experience=0
        state.hero.stats=HeroStats(11,22,22,16,30,17,186,158)
        state.classGuidedLevelGrowths=14
        state.skills.clear()
        state.skills.addAll(PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(HeroClass.RANGER,15))
        val engine=SimpleGameEngine()
        // Equipment is not in the photograph. Only match its aggregate contribution to the stated CP.
        val equipmentPower=157-engine.characterStatPower(state)
        assertTrue(equipmentPower>0 && state.equipment.isNotEmpty())
        state.equipment=state.equipment.map { it.copy(power=equipmentPower) }.toMutableList()
        assertEquals(157L,engine.displayCombatPower(state))
        val now=System.currentTimeMillis()
        state.lastSettledAt=now
        state.actionStartedAt=now
        state.actionEndsAt=now+3_600_000
        var tree=ArenaSkillTreeRules.initialize(null,HeroClass.RANGER)
        val desired=mapOf("A01" to 10,"A02" to 2,"A03" to 1,"A04" to 1,"S01" to 1)
        for((slot,rank) in desired) {
            val id=ArenaSkillTreeCatalog.forClass(HeroClass.RANGER).single { it.slotKey==slot }.id
            for(step in 1..rank) {
                val update=ArenaSkillTreeRules.allocate(tree,HeroClass.RANGER,15,ArenaTurnInputAdapter.ownedAttackIds(state),id,step,true)
                assertTrue(update.accepted);tree=update.state
            }
        }
        assertEquals(15,ArenaSkillTreeRules.spentPoints(tree))
        File(dir,"device-matched-state.json").writeText(json.encodeToString(state))
        File(dir,"device-matched-tree.json").writeText(json.encodeToString(tree))
        val table=StringBuilder("class\tslot\tid\trank\tko\ten\tja\n")
        for(d in ArenaIdentityCatalog.values) for(rank in listOf(5,10)) {
            val copy=listOf("ko","en","ja").map { ArenaIdentityCopy.milestone(d.id,rank,it).replace('\n',' ') }
            table.appendLine(listOf(d.heroClass,d.slot,d.id,rank).joinToString("\t")+"\t"+copy.joinToString("\t"))
        }
        File(dir,"skill-milestones.tsv").writeText(table.toString())
        println("REPORTED_DEVICE_FIXTURE,L15,CP=${engine.displayCombatPower(state)},stats=${state.hero.stats.values()},equipmentAggregate=$equipmentPower,points=15")
    }
}
