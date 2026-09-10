package com.nullplaying.engine.arena

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaDeviceExperienceProbeTest {
    @Test fun `actual five device battles and repeated seeds are measured separately`() {
        val dir=File("src/simpleTest/resources/arena-identity-v2-device")
        val codec=Json { ignoreUnknownKeys=true }
        repeat(5) { index ->
            val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"$index.json").readText())
            val seed=File(dir,"$index.seed").readText().toLong()
            val original=ArenaSupportTurnEngine.simulate(pair[0],pair[1],seed)
            val hash=MessageDigest.getInstance("SHA-256").digest(original.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val golden=File(dir,"$index.sha256")
            assertTrue(golden.exists())
            assertEquals(golden.readText(),hash)
            // Regenerate only the current identity projection from the exact saved input.
            val user=pair[0].copy(identity=null).withIdentityRules()
            val enemy=pair[1].copy(identity=null).withIdentityRules()
            var wins=0
            repeat(512) { n ->
                val result=ArenaSupportTurnEngine.simulate(user,enemy,940001L+n,recordEvents=false)
                assertEquals(ArenaRunStatus.COMPLETED,result.status)
                if(result.winnerId==user.fighter.id) wins++
            }
            println("DEVICE_MATCH,$index,${pair[1].fighter.heroClass},oldWinner=${original.winnerId},wins=$wins,total=512")
            val now=ArenaSupportTurnEngine.simulate(user,enemy,seed)
            for(f in listOf(user,enemy)) println("DEVICE_ACTIONS,$index,${f.fighter.heroClass},"+
                now.events.filter { it.actorId==f.fighter.id && it.type==ArenaSupportEventType.CAST_START }.groupingBy { it.actionId }.eachCount())
        }
    }
}
