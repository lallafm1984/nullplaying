package com.nullplaying.remote

import com.nullplaying.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BattleQaNarrativeClientTest {
    private val requestId = "123e4567-e89b-42d3-a456-426614174000"

    @Test
    fun `bridge endpoint accepts only pinned emulator loopback route`() {
        assertTrue(battleQaBridgeEndpointAllowed("http://10.0.2.2:8787/v1/narratives"))
        assertTrue(battleQaBridgeEndpointAllowed("http://127.0.0.1:8787/v1/narratives"))
        assertFalse(battleQaBridgeEndpointAllowed("https://10.0.2.2:8787/v1/narratives"))
        assertFalse(battleQaBridgeEndpointAllowed("http://example.com:8787/v1/narratives"))
        assertFalse(battleQaBridgeEndpointAllowed("http://10.0.2.2:9999/v1/narratives"))
        assertFalse(battleQaBridgeEndpointAllowed("http://10.0.2.2:8787/other"))
        assertFalse(battleQaBridgeEndpointAllowed("http://10.0.2.2:8787/v1/narratives?target=live"))
    }

    @Test
    fun `validated response must be synthetic and declare no production database access`() {
        val valid = validNarrative()
        assertTrue(validate(valid).isEmpty())

        val unsafe = valid.copy(productionDatabaseTouched = true)
        assertTrue(validate(unsafe).any { it.contains("database") })
    }

    @Test
    fun `validated response rejects unresolved entity references and reordered phases`() {
        val valid = validNarrative()
        val invalid = valid.copy(
            scenes = valid.scenes.mapIndexed { index, scene ->
                when (index) {
                    0 -> scene.copy(text = "A_SKILL_01 used")
                    1 -> scene.copy(phaseId = "P3")
                    else -> scene
                }
            },
        )
        val errors = validate(invalid).joinToString("\n")
        assertTrue(errors.contains("unresolved entity"))
        assertTrue(errors.contains("phase id mismatch"))
    }

    @Test
    fun `dialogue fields and quoted speech are stripped before playback`() {
        val raw = validNarrative().copy(
            scenes = validNarrative().scenes.mapIndexed { index, scene ->
                if (index == 0) {
                    scene.copy(
                        text = "QA20이 \"물러서.\" 검을 들어 공격선을 막았다. " +
                            "루엔은 한 걸음 물러나 다음 공격을 준비했다. " +
                            "팽팽한 간격이 다음 공방의 방향을 갈랐다.",
                        dialogue = "이번에는 끝낸다.",
                    )
                } else {
                    scene
                }
            },
        )

        assertTrue(validate(raw).any { it.contains("dialogue") })
        val sanitized = sanitizeBattleQaNarrative(raw)
        assertTrue(validate(sanitized).isEmpty())
        assertEquals(null, sanitized.scenes.first().dialogue)
        assertEquals(
            "QA20이 검을 들어 공격선을 막았다. 루엔은 한 걸음 물러나 다음 공격을 준비했다. " +
                "팽팽한 간격이 다음 공방의 방향을 갈랐다.",
            sanitized.scenes.first().text,
        )
        assertFalse(sanitized.scenes.first().text.contains("물러서"))
        assertFalse(sanitized.scenes.first().text.contains("다시 간다"))
    }

    @Test
    fun `session token and response request identity must be exact`() {
        BattleQaSessionToken.configure("short-token")
        assertEquals(null, BattleQaSessionToken.current())
        BattleQaSessionToken.configure("a".repeat(64))
        assertEquals("a".repeat(64), BattleQaSessionToken.current())
        BattleQaSessionToken.configure(null)
        assertEquals("a".repeat(64), BattleQaSessionToken.current())

        val errors = validateBattleQaNarrative(
            validNarrative().copy(requestId = "123e4567-e89b-42d3-a456-426614174999"),
            5,
            requestId,
            battleId,
            "QA20",
            "루엔",
            "USER_WIN",
        )
        assertTrue(errors.any { it.contains("request id") })
    }

    @Test
    fun `only the final playback sentence states the exact deterministic outcome`() {
        assertTrue(validate(validNarrative()).isEmpty())

        val wrongWinner = validNarrative().copy(
            scenes = validNarrative().scenes.mapIndexed { index, scene ->
                if (index == 4) {
                    scene.copy(
                        text = "루엔의 결정타가 QA20의 마지막 방어를 무너뜨렸다. " +
                            "마지막 충격이 돌바닥 위로 길게 번졌다. " +
                            "루엔이 승리하고 QA20이 패배했다.",
                    )
                } else {
                    scene
                }
            },
        )
        assertTrue(validate(wrongWinner).any { it.contains("deterministic outcome") })

        val earlyResult = validNarrative().copy(
            scenes = validNarrative().scenes.mapIndexed { index, scene ->
                if (index == 2) {
                    scene.copy(
                        text = "QA20이 승리했다. 루엔은 거리를 다시 잡았다. " +
                            "새로운 공방이 곧바로 이어졌다.",
                    )
                } else {
                    scene
                }
            },
        )
        assertTrue(validate(earlyResult).any { it.contains("before the final narration line") })

        assertTrue(
            battleFinalOutcomeMatches(
                outcome = "DRAW",
                userName = "QA20",
                opponentName = "루엔",
                text = "두 사람의 마지막 일격이 맞부딪치며 승부는 무승부로 끝났다.",
            ),
        )
    }

    @Test
    fun `three to five internal anchors are valid without fixing playback to five lines`() {
        for (anchorCount in 3..5) {
            val narrative = validNarrative(anchorCount)
            val errors = validateBattleQaNarrative(
                narrative = narrative,
                requestedPhaseCount = anchorCount,
                requestedId = requestId,
                requestedBattleId = battleId,
                requestedUserName = "QA20",
                requestedOpponentName = "루엔",
                requestedOutcome = "USER_WIN",
            )
            assertTrue(errors.joinToString(), errors.isEmpty())
        }
    }

    @Test
    fun `battle QA build has no Supabase configuration and no narrative network bridge`() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".battleqa"))

        assertTrue(BuildConfig.DEBUG)
        assertFalse(BuildConfig.REMOTE_SERVICES_ENABLED)
        assertFalse(BuildConfig.SHARED_PLAYER_QA_TRANSPORT_ENABLED)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        assertEquals("", BuildConfig.SUPABASE_QA_URL)
        assertEquals("", BuildConfig.SUPABASE_QA_PUBLISHABLE_KEY)
        assertEquals("", BuildConfig.ADMOB_APP_ID)
        assertEquals("", BuildConfig.BANNER_AD_UNIT_ID)
        assertEquals("", BuildConfig.REWARDED_AD_UNIT_ID)
        assertEquals("", BuildConfig.ARENA_REWARDED_AD_UNIT_ID)
        assertFalse(BuildConfig.BATTLE_QA_BRIDGE_ENABLED)
        assertEquals("", BuildConfig.BATTLE_QA_BRIDGE_URL)
    }

    private fun validNarrative(anchorCount: Int = 5): BattleQaNarrative = BattleQaNarrative(
        schemaVersion = 2,
        requestId = requestId,
        battleId = battleId,
        userName = "QA20",
        opponentName = "루엔",
        phaseCount = anchorCount,
        source = "qwen",
        modelValid = true,
        model = "qwen3.7-flash-2026-07-15",
        syntheticOnly = true,
        productionDatabaseTouched = false,
        attemptCount = 1,
        latencyMs = 500L,
        usage = BattleQaUsage(inputTokens = 100, outputTokens = 50),
        estimatedCostUsd = 0.0001,
        scenes = (1..anchorCount).map { index ->
            BattleQaScene(
                phaseId = "P$index",
                title = "테스트 장면 $index",
                text = if (index == anchorCount) {
                    "QA20의 결정타가 루엔의 마지막 방어를 무너뜨렸다. " +
                        "마지막 충격이 돌바닥 위로 길게 번졌다. " +
                        "QA20이 승리하고 루엔이 패배했다."
                } else {
                    "QA20이 공격선을 좁히며 루엔의 반응을 살폈다. " +
                        "이어진 공방은 다음 움직임으로 자연스럽게 연결됐다. " +
                        "발밑의 먼지가 두 갈래로 흩어지며 선택의 차이를 드러냈다."
                },
                effectKey = if (index == anchorCount) "FINISH" else "CLASH",
            )
        },
    )

    private fun validate(
        narrative: BattleQaNarrative,
    ): List<String> = validateBattleQaNarrative(
        narrative = narrative,
        requestedPhaseCount = 5,
        requestedId = requestId,
        requestedBattleId = battleId,
        requestedUserName = "QA20",
        requestedOpponentName = "루엔",
        requestedOutcome = "USER_WIN",
    )

    companion object {
        private const val battleId = "123e4567-e89b-42d3-a456-426614174111"
    }
}
