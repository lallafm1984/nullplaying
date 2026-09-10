package com.nullplaying.engine.arena

import com.nullplaying.engine.ProjectionBattleEngine
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleSeasonStanding
import org.junit.Assert.*
import org.junit.Test

class ArenaAdaptiveMatchmakingTest {
    private fun match(i: Int, outcome: BattleOutcome, adjustment: Int = 0, power: Long = 202L) =
        ArenaRecentMatch("battle-$i", "opponent-$i", 21L, outcome, power, adjustment)
    private fun profile(history: List<ArenaRecentMatch>, power: Long = 202L) =
        requireNotNull(ArenaAdaptiveMatchmaking.profile(21L, power, history))

    @Test fun `three samples warmup bounded gradual response and neutral draws`() {
        val losses = mutableListOf<ArenaRecentMatch>()
        val values = (0 until 16).map { i ->
            val p = profile(losses)
            losses.add(0, match(i, BattleOutcome.USER_LOSS, p.adjustmentPermille))
            p.adjustmentPermille
        }
        assertEquals(listOf(0,0,0,-25,-50,-75,-100), values.take(7))
        assertTrue(values.all { it in -100..100 })
        assertEquals(0, profile((0..9).map { match(it, BattleOutcome.DRAW) }).adjustmentPermille)
        val wins = mutableListOf<ArenaRecentMatch>()
        repeat(12) { i -> val p=profile(wins); wins.add(0,match(i,BattleOutcome.USER_WIN,p.adjustmentPermille)) }
        assertEquals(100, profile(wins).adjustmentPermille)
        assertEquals(10, profile(losses).sampleCount)
    }

    @Test fun `duplicate invalid distant-level and legacy samples never fabricate experience`() {
        val one = match(0, BattleOutcome.USER_LOSS)
        assertEquals(1, profile(List(10) { one }).sampleCount)
        assertEquals(0, profile(listOf(one.copy(battleId=""),one.copy(heroLevel=40),one.copy(adjustmentPermille=-101),one.copy(heroPower=Long.MAX_VALUE))).sampleCount)
        assertEquals(-25, profile((0..9).map { match(it,BattleOutcome.USER_LOSS,power=0) }).adjustmentPermille)
        assertNull(ArenaAdaptiveMatchmaking.profile(9,20,emptyList()))
        assertNull(ArenaAdaptiveMatchmaking.profile(21,0,emptyList()))
    }

    @Test fun `recent captured equipment power limits abrupt downgrade without blocking legitimate growth`() {
        val prior = (0..9).map { match(it,BattleOutcome.DRAW,power=240) }
        assertEquals(216L,profile(prior,125).referencePower)
        assertEquals(245L,profile(prior,245).referencePower)
        assertEquals(125L,profile(prior,125).targetPower)
        assertEquals(profile(emptyList(),125).referencePower,profile(prior.map { it.copy(heroLevel=20) },125).referencePower)
    }

    @Test fun `difficulty adjusts existing Elo inside live aggregate bounds and preserves score floor`() {
        val p=profile(emptyList())
        for (score in listOf(0,10,1000,25_000_000)) for (level in 19L..23L) for (power in listOf(120L,182L,202L,222L,300L)) {
            val ref=ArenaAdaptiveMatchmaking.referenceScore(p,score,level,power)
            for (outcome in BattleOutcome.entries) {
                val result=ProjectionBattleEngine.settleUserStanding(BattleSeasonStanding(score=score),ref,outcome)
                assertTrue(result.after.score>=0)
                assertTrue(result.scoreDelta in -24..24)
                assertEquals(1,result.after.completedBattles)
            }
        }
        fun win(power:Long)=ProjectionBattleEngine.settleUserStanding(BattleSeasonStanding(),
            ArenaAdaptiveMatchmaking.referenceScore(p,1000,21,power),BattleOutcome.USER_WIN).scoreDelta
        assertTrue(win(182)<win(202));assertTrue(win(222)>win(202))
        var standing=BattleSeasonStanding()
        val history=mutableListOf<ArenaRecentMatch>()
        // Losing on purpose to obtain a gentler band costs more than the subsequent equal win count restores.
        repeat(20) { i ->
            val current=profile(history)
            val result=if(i<10) BattleOutcome.USER_LOSS else BattleOutcome.USER_WIN
            standing=ProjectionBattleEngine.settleUserStanding(standing,
                ArenaAdaptiveMatchmaking.referenceScore(current,standing.score,21,current.targetPower),result).after
            history.add(0,match(i,result,current.adjustmentPermille))
        }
        assertTrue("tank then win score=${standing.score}",standing.score<=1000)
    }

    @Test fun `power-matched reserve stays varied bounded reproducible and avoids last three opponents`() {
        val history=mutableListOf<ArenaRecentMatch>()
        repeat(25) { i ->
            val selected=select(125L,history,i.toLong())
            val again=select(125L,history,i.toLong())
            assertEquals(selected,again)
            assertEquals(20,selected.poolSize)
            assertEquals(20,selected.localPoolSize)
            assertEquals(21L,selected.opponent.projection.level)
            assertTrue(selected.opponent.projection.verifiedPower in 113L..137L)
            assertEquals(selected.opponent.projection.level.toInt(),selected.opponent.combat.arenaLevel)
            assertFalse(history.take(3).any { it.opponentId==selected.opponent.projection.projectionId })
            val p=selected.matchmakingProfile!!
            history.add(0,ArenaRecentMatch("$i",selected.opponent.projection.projectionId,21,
                BattleOutcome.USER_LOSS,125,p.adjustmentPermille))
        }
        val fallback=select(125,history,26)
        assertEquals(21L,fallback.opponent.projection.level)
        assertTrue(fallback.opponent.projection.verifiedPower in 113L..125L)
        val reserves=ArenaLocalReserveMatchmaking.build(requesterLevel=21,count=20,matchmakingProfile=profile(emptyList(),180))!!
        assertEquals(20,reserves.map { it.combat.fighter.stats.values() }.distinct().size)
        assertEquals(6,reserves.map { it.projection.heroClass }.distinct().size)
    }

    @Test fun `win and loss streaks never change local level or escape the player's ten percent band`() {
        for(level in listOf(10L,11L,20L,21L,25L,30L,100L)) for(power in listOf(50L,125L,205L,400L,800L)) {
            for(sign in listOf(-1,1)) {
                val history=(0..9).map { match(it,if(sign<0) BattleOutcome.USER_LOSS else BattleOutcome.USER_WIN,sign*100,power)
                    .copy(heroLevel=level) }
                val selected=selectArenaOpponent(false,null,"entry-boundary",level,123000,10,power,history)
                    as ArenaServerMatchSelectionResult.Ready
                assertEquals(level,selected.opponent.projection.level)
                assertEquals(2,selected.matchmakingProfile!!.rulesVersion)
                assertEquals(1,selected.matchmakingProfile!!.levelSearchRadius)
                assertTrue(selected.opponent.projection.verifiedPower*10 in power*9..power*11)
            }
        }
    }

    @Test fun `authored spread shifts monotonically within the band and remains diverse`() {
        for(power in listOf(125L,205L,400L,800L)) {
            var previous=Double.NEGATIVE_INFINITY
            for(adjustment in listOf(-100,-75,-25,0,25,75,100)) {
                val p=profile(emptyList(),power).copy(adjustmentPermille=adjustment)
                val values=ArenaLocalReserveMatchmaking.build(requesterLevel=21,count=20,matchmakingProfile=p)!!
                val powers=values.map { it.projection.verifiedPower }
                assertTrue(powers.all { it*10 in power*9..power*11 })
                assertTrue(powers.distinct().size>=8)
                assertTrue(powers.average()>previous)
                previous=powers.average()
                if(adjustment==-100) assertTrue(powers.all { it<=power })
                if(adjustment==100) assertTrue(powers.all { it>=power })
            }
        }
        val p=profile(emptyList(),205)
        assertEquals(185L..225L,ArenaAdaptiveMatchmaking.localPowerBounds(p))
        assertNull(ArenaAdaptiveMatchmaking.profile(21,Long.MAX_VALUE,emptyList()))
        for(power in listOf(1L, ArenaAdaptiveMatchmaking.MAX_LOCAL_REFERENCE_POWER)) {
            val edge=profile(emptyList(),power)
            val values=ArenaLocalReserveMatchmaking.definitions.map { ArenaAdaptiveMatchmaking.localPower(edge,it.powerPermille) }
            assertTrue(values.all { it*10 in power*9..power*11 })
        }
    }

    private fun select(power:Long,history:List<ArenaRecentMatch>,sequence:Long) = selectArenaOpponent(
        serverRosterEnabled=false,roster=null,requesterCharacterId="adaptive-qa",requesterLevel=21,
        nowEpochMillis=123000,completedMatchSequence=sequence,requesterCombatPower=power,recentMatches=history,
    ) as ArenaServerMatchSelectionResult.Ready
}
