package com.nullplaying.ui

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import com.nullplaying.model.SimpleGameState
import com.nullplaying.remote.maximumAcceptedRankingCombatPower
import java.time.Instant
import java.time.ZoneOffset

internal const val ARENA_SERVER_MATCHING_QA_FIXTURE_EXTRA =
    "qa_arena_server_matching_fixture"
internal const val ARENA_SERVER_CANDIDATE_COUNT_QA_EXTRA =
    "qa_arena_server_candidate_count"

/** Explicit offline-only public-roster fixture used to exercise the real arena adapter and UI. */
internal fun arenaServerMatchingQaFixture(
    requested: Boolean,
    debugBuild: Boolean,
    remoteServicesEnabled: Boolean,
    state: SimpleGameState,
    nowEpochMillis: Long,
    candidateCount: Int = 3,
): PublicPlayerRoster? {
    if (!requested || !debugBuild || remoteServicesEnabled || state.hero.level < 10L ||
        candidateCount !in 1..3
    ) return null
    val levels = listOf(
        (state.hero.level - 1L).coerceAtLeast(10L),
        state.hero.level,
        (state.hero.level + 1L).coerceAtMost(10_000L),
    )
    val classes = listOf(HeroClass.RANGER, HeroClass.MAGE, HeroClass.PALADIN)
    val names = listOf("바람의 루엔", "별빛의 세라", "새벽의 아린")
    val projectionIds = listOf(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
    )
    val engine = SimpleGameEngine()
    val snapshots = classes.indices.take(candidateCount).map { index ->
        val heroClass = classes[index]
        val level = levels[index]
        val roll = engine.rollStats(0x51A7E00L + index, heroClass)
        val generated = engine.newGame(names[index], heroClass, roll.stats.copy(), roll.nextSeed,
            nowEpochMillis)
        ArenaSyntheticProfileGrowth.grow(
            engine = engine,
            stats = generated.hero.stats,
            heroClass = heroClass,
            targetLevel = level,
            identitySeed = generated.rngState,
        )
        generated.hero.level = level
        val stats = generated.hero.stats
        PublicPlayerSnapshot(
            projectionId = projectionIds[index],
            displayName = names[index],
            heroClass = heroClass,
            level = level,
            combatPower = engine.displayCombatPower(generated)
                .coerceIn(1L, maximumAcceptedRankingCombatPower(level)),
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
            stats = PublicPlayerStats(
                strength = stats.strength,
                constitution = stats.constitution,
                dexterity = stats.dexterity,
                intelligence = stats.intelligence,
                wisdom = stats.wisdom,
                charisma = stats.charisma,
                maxHealth = stats.maxHealth,
                maxMana = stats.maxMana,
            ),
            adventureTraitIds = emptyList(),
        )
    }
    val receivedAt = nowEpochMillis.coerceAtLeast(1L)
    return PublicPlayerRoster(
        requesterCharacterId = state.rankingCharacterId.ifBlank {
            "99999999-9999-4999-8999-999999999999"
        },
        requesterLevel = state.hero.level,
        rosterId = "qa-arena-server-roster-v1-$candidateCount",
        rosterDateUtc = Instant.ofEpochMilli(receivedAt).atZone(ZoneOffset.UTC).toLocalDate().toString(),
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        receivedAtEpochMillis = receivedAt,
        validUntilEpochMillis = receivedAt + 24L * 60L * 60L * 1_000L,
        snapshots = snapshots,
    )
}
