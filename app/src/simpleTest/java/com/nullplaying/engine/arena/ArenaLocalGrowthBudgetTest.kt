package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroStats
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ArenaLocalGrowthBudgetTest {
    @Test fun `local identities match real stat budgets while preserving class variety and source data`() {
        val requester=HeroStats(11,22,22,16,30,17,186,158)
        val original=requester.copy()
        val signatures=mutableSetOf<List<Long>>()
        for(definition in ArenaLocalReserveMatchmaking.definitions) {
            val raw=ArenaLocalReserveMatchmaking.representativeRawStats(SimpleGameEngine(),definition,15)
            val before=raw.copy()
            val matched=ArenaLocalReserveMatchmaking.matchGrowthBudget(raw,requester,15)
            assertTrue(abs(matched.values().take(6).sum()-118)<=3)
            assertTrue(matched.values().take(6).max()<=46)
            assertEquals(before,raw)
            assertEquals(matched,ArenaLocalReserveMatchmaking.matchGrowthBudget(raw,requester,15))
            signatures+=matched.values().take(6)
        }
        assertEquals(original,requester)
        assertEquals(20,signatures.size)
    }
    @Test fun `actual selection forwards real stat growth and leaves the caller untouched`() {
        val stats=HeroStats(11,22,22,16,30,17,186,158)
        val result=selectArenaOpponent(false,null,"local-growth-qa",15,1000,0,157,requesterStats=stats)
        assertTrue(result is ArenaServerMatchSelectionResult.Ready)
        val ready=result as ArenaServerMatchSelectionResult.Ready
        assertEquals(20,ready.localPoolSize)
        assertEquals(0,ready.serverPoolSize)
        assertEquals(15L,ready.opponent.projection.level)
        assertTrue(ready.opponent.projection.verifiedPower in 142..172)
        assertTrue(ready.opponent.combat.fighter.stats.values().take(6).sum()>=115)
        assertEquals(HeroStats(11,22,22,16,30,17,186,158),stats)
    }
}
