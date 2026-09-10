package com.nullplaying.engine.arena

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaIdentityV1ReplayTest {
    @Test fun `published version twenty one battle contracts retain every event and outcome`() {
        val directory=File("src/simpleTest/resources/arena-identity-v1")
        fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        repeat(6) { index ->
            val pair=Json.decodeFromString<List<ArenaSupportInput>>(File(directory,"$index.json").readText())
            val result=ArenaSupportTurnEngine.simulate(pair[0],pair[1],4100L+index)
            assertEquals("arena-stat-identity-v1",result.rulesVersion)
            assertEquals("Published replay changed: $index",File(directory,"$index.sha256").readText(),hash(result.toString()))
        }
    }
}
