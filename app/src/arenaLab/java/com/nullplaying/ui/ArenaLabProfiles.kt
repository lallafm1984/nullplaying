package com.nullplaying.ui

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.model.*
import kotlinx.serialization.Serializable
import java.util.UUID

/** QA-only profiles: no Room writes, server identities, or changes to real saved heroes. */
@Serializable
internal data class ArenaLabProfile(
    val heroClass: HeroClass,
    val level: Int,
    val seed: Long,
    val stats: HeroStats,
    val combatPowerOverride: Long? = null,
) {
    val key: String get() = "${heroClass.name}:$level"
    val identity: String get() = UUID.nameUUIDFromBytes("offline-arena-lab:$key".toByteArray()).toString()
}

internal object ArenaLabProfiles {
    const val MIN_LEVEL = 10
    const val MAX_LEVEL = 100
    private const val BASE_SEED = 902310L
    private const val CREATION_TIME = 1_788_544_800_000L

    fun generate(heroClass: HeroClass, level: Int, seed: Long = BASE_SEED): ArenaLabProfile {
        require(level in MIN_LEVEL..MAX_LEVEL)
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(seed xor heroClass.ordinal.toLong(), heroClass)
        val state = engine.newGame("연습 ${heroClass.labelKo}", heroClass, roll.stats.copy(), roll.nextSeed, CREATION_TIME)
        // Reuses the live per-level HP/MP and class-guided stat growth function, with an
        // independent deterministic RNG sample for every level, just like local projections.
        ArenaSyntheticProfileGrowth.grow(engine, state.hero.stats, heroClass,
            targetLevel = level.toLong(), identitySeed = state.rngState)
        return ArenaLabProfile(heroClass, level, seed, state.hero.stats.copy())
    }

    fun validate(profile: ArenaLabProfile): Boolean =
        profile.level in MIN_LEVEL..MAX_LEVEL &&
            profile.stats.values().take(6).all { it in 1L..10_000L } &&
            profile.stats.values().drop(6).all { it in 1L..10_000_000L } &&
            (profile.combatPowerOverride == null || profile.combatPowerOverride in 1L..1_000_000L)

    fun parseStats(values: List<String>): HeroStats? {
        if (values.size != 8) return null
        val v = values.map { it.toLongOrNull() ?: return null }
        if (v.take(6).any { it !in 1L..10_000L } || v.drop(6).any { it !in 1L..10_000_000L }) return null
        return HeroStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
    }

    fun state(profile: ArenaLabProfile): SimpleGameState {
        require(validate(profile))
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(profile.seed xor profile.heroClass.ordinal.toLong(), profile.heroClass)
        val state = engine.newGame("연습 ${profile.heroClass.labelKo}", profile.heroClass,
            roll.stats.copy(), roll.nextSeed, CREATION_TIME)
        state.hero.level = profile.level.toLong()
        state.hero.stats = profile.stats.copy()
        state.classGuidedLevelGrowths = profile.level - 1L
        state.rankingCharacterId = profile.identity
        // Same fixed acquisition tiers as normal level-ups; no extra high-level attacks.
        state.skills = (1..(profile.level / 5 + 1).coerceAtMost(SimpleGameEngine.MAX_SKILLS)).map { tier ->
            val d = SkillCatalog.select(state.skillCatalogSeed, profile.heroClass, tier)
            LearnedSkill(tier, d.name, if (tier == 1) 1 else (tier - 1) * 5L,
                d.description, d.catalogId)
        }.toMutableList()
        state.offlineAdventureMillis = 0L
        return state
    }

    fun combatPower(profile: ArenaLabProfile): Long =
        profile.combatPowerOverride ?: SimpleGameEngine().displayCombatPower(state(profile))
}
