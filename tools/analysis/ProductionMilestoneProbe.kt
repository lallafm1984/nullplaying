package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.TaleKind

object ProductionMilestoneProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        for (heroClass in HeroClass.entries) {
            val engine = SimpleGameEngine()
            val state = engine.newGame("기준 전사", heroClass, engine.rollStats(77L).stats, 88L, 0L)
            var labyrinth = 0; var level100 = 0; var depth100 = 0L
            for (quarter in 1..688) {
                engine.settleOffline(state, quarter * 21_600_000L)
                if (labyrinth == 0 && state.adventureTale.kind == TaleKind.LABYRINTH) labyrinth = quarter
                if (level100 == 0 && state.hero.level >= 100) {
                    level100 = quarter; depth100 = state.labyrinthDepthCompleted
                }
                if (quarter in listOf(216,220,636,640,644,648,660,664)) {
                    println("$heroClass quarter=$quarter level=${state.hero.level} depth=${state.adventureTale.labyrinthDepth}")
                }
            }
            println("MILESTONE $heroClass labyrinthQuarter=$labyrinth level100Quarter=$level100 depth100=$depth100")
        }
    }
}
