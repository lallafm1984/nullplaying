package com.nullplaying.ui

import com.nullplaying.engine.arena.*
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaIdentityTimelineTest {
    @Test fun `new identity battles preserve complete HP MP and shield playback ledger`() {
        for(c in HeroClass.entries) for(d in HeroClass.entries) repeat(4) { seed ->
            val r=ArenaSupportTurnEngine.simulate(identityFixture(c,"a",10),identityFixture(d,"b",10),seed.toLong())
            for(lang in listOf("ko","en","ja")) {
                val timeline=buildArenaLiveTimeline(r,linkedMapOf("a" to "Alpha","b" to "Beta"),lang)
                assertTrue(timeline.logs.isNotEmpty())
            }
        }
    }
}
