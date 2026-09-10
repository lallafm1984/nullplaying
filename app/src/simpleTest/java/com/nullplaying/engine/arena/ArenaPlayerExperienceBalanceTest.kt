package com.nullplaying.engine.arena

import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroStats
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaPlayerExperienceBalanceTest {
    @Test fun `photographed player uses real selection and feedback across consecutive sessions`() {
        val pair=Json { ignoreUnknownKeys=true }.decodeFromString<List<ArenaSupportInput>>(
            File("src/simpleTest/resources/arena-identity-v2-device/0.json").readText())
        val player=pair[0].copy(identity=null).withIdentityRules()
        val id=player.fighter.id
        val stats=HeroStats(11,22,22,16,30,17,186,158)
        var wins=0;var total=0;var longestLossRun=0;var eased=0
        repeat(32) { session ->
            val history=mutableListOf<ArenaRecentMatch>();var losses=0
            repeat(40) { match ->
                val selected=selectArenaOpponent(false,null,id,15,1000,
                    session*1000L+match+1,157,history,stats) as ArenaServerMatchSelectionResult.Ready
                val enemy=selected.opponent.combat.withIdentityOpponentRules()
                val result=ArenaSupportTurnEngine.simulate(player,enemy,selected.battleSeed,recordEvents=false)
                assertEquals(ArenaRunStatus.COMPLETED,result.status)
                val outcome=when(result.winnerId) {
                    player.fighter.id->BattleOutcome.USER_WIN
                    null->BattleOutcome.DRAW
                    else->BattleOutcome.USER_LOSS
                }
                if(outcome==BattleOutcome.USER_WIN) wins++
                losses=if(outcome==BattleOutcome.USER_LOSS) losses+1 else 0
                longestLossRun=maxOf(longestLossRun,losses)
                val profile=requireNotNull(selected.matchmakingProfile)
                if(profile.adjustmentPermille<0) eased++
                assertTrue(selected.opponent.projection.verifiedPower in 142..172)
                assertEquals(15L,selected.opponent.projection.level)
                if(session==0 && match<10) println("ACTUAL_PATH,$match,${selected.opponent.projection.displayName},$outcome,adjustment=${profile.adjustmentPermille}")
                history.add(0,ArenaRecentMatch("$session:$match",selected.opponent.projection.projectionId,15,outcome,157,profile.adjustmentPermille))
                total++
            }
        }
        println("PLAYER_EXPERIENCE,battles=$total,wins=$wins,rate=${wins.toDouble()/total},longestLossRun=$longestLossRun,easedMatches=$eased")
        assertTrue("Actual selection win rate $wins/$total",wins.toDouble()/total in .45.. .65)
        assertTrue(eased>0)
    }
}
