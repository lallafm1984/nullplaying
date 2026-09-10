package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats

/**
 * Synthetic, offline QA inputs for the twelve-support vertical slice, not saved heroes.
 *
 * The source engine supplies the initial roll and every class-guided level increase.
 * All attacks unlocked at the requested hero level are included at novice mastery.
 * Only the two planned Lv.5/Lv.10 supports are explicitly owned in this fixture; callers may
 * select either executable A01 or A02 trait for an isolated comparison.
 * later support unlocks are deliberately outside this slice, even for a Lv.100 hero.
 * No equipment, adventure rewards, old traits, or production save mutations are applied.
 */
object ArenaSupportQaFixtures {
    private const val CREATION_SEED = 0x2050_905AL
    private const val CREATION_TIME = 1_788_544_800_000L

    fun fighter(
        heroClass: HeroClass,
        heroLevel: Int,
        id: String,
        traitRank: Int = 0,
        enhancement: Int = 0,
        arenaLevel: Int = 1,
        traitIndex: Int = 0,
        ignoreHeroLevelGate: Boolean = false,
    ): ArenaSupportInput {
        require(heroLevel in (if (ignoreHeroLevelGate) 1 else 10)..100) { "Invalid QA hero level" }
        require(traitRank in 0..5) { "Trait rank must be 0..5" }
        require(enhancement in 0..3) { "Enhancement must be 0..3" }
        require(arenaLevel in 1..100) { "Arena level must be 1..100" }
        require(traitIndex in 0..1) { "Executable trait index must be 0 or 1" }

        // ID, arena level and trait allocation never change a class's growth trajectory.
        // Every call owns fresh mutable source stats; returned arena stats are immutable.
        val game = SimpleGameEngine()
        val roll = game.rollStats(CREATION_SEED xor heroClass.ordinal.toLong(), heroClass)
        val state = game.newGame("Arena support QA", heroClass, roll.stats.copy(), roll.nextSeed, CREATION_TIME)
        val stats = state.hero.stats.copy()
        ArenaSyntheticProfileGrowth.grow(
            engine = game,
            stats = stats,
            heroClass = heroClass,
            targetLevel = heroLevel.toLong(),
            identitySeed = state.rngState,
        )

        // Allocation affordability is checked by the caller/engine, not silently clamped.
        // Rank zero means the comparison has no talent; no enhancement-only node is made.
        val traits = if (traitRank == 0) emptyList() else listOf(
            ArenaSupportTraitRank(
                id = ArenaProgressionCatalog.forClass(heroClass)[traitIndex].id,
                rank = traitRank,
                enhancement = enhancement,
            ),
        )
        return ArenaSupportInput(
            fighter = buildArenaFighterInput(heroClass, heroLevel, id, stats),
            supportIds = initialSupports(heroClass),
            traits = traits,
            arenaLevel = arenaLevel,
        )
    }

    /** Full V6 ownership fixture, still using real deterministic hero growth and novice attacks. */
    fun fullFighter(heroClass: HeroClass, heroLevel: Int, id: String,
        traits: List<ArenaSupportTraitRank> = emptyList(), arenaLevel: Int = 100): ArenaSupportInput =
        fighter(heroClass, heroLevel, id, arenaLevel = arenaLevel).copy(
            supportIds = ArenaSupportCatalog.unlockedIds(heroClass, heroLevel.toLong()), traits = traits)

    private fun buildArenaFighterInput(
        heroClass: HeroClass,
        heroLevel: Int,
        id: String,
        stats: HeroStats,
    ): ArenaFighterInput = ArenaFighterInput(
        id = id,
        heroClass = heroClass,
        level = heroLevel.toLong(),
        stats = ArenaCoreStats(
            strength = stats.strength.toDouble(),
            constitution = stats.constitution.toDouble(),
            dexterity = stats.dexterity.toDouble(),
            intelligence = stats.intelligence.toDouble(),
            wisdom = stats.wisdom.toDouble(),
            charisma = stats.charisma.toDouble(),
            rawMaxHealth = stats.maxHealth.toDouble(),
            rawMaxMana = stats.maxMana.toDouble(),
        ),
        attacks = SkillCatalog.forClass(heroClass)
            .filter { it.unlockLevel <= heroLevel }
            .map { definition ->
                ArenaAttackInput(
                    id = definition.catalogId,
                    name = definition.name,
                    tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1,
                    masteryBonusPercent = 0,
                    sourceDamagePercentMin = definition.damagePercentMin,
                    sourceDamagePercentMax = definition.damagePercentMax,
                )
            },
    )

    private fun initialSupports(heroClass: HeroClass): Set<String> = when (heroClass) {
        HeroClass.WARRIOR -> setOf("ARENA_SUP_FIGHTER_01", "ARENA_SUP_FIGHTER_02")
        HeroClass.ROGUE -> setOf("ARENA_SUP_ROGUE_04", "ARENA_SUP_ROGUE_01")
        HeroClass.RANGER -> setOf("ARENA_SUP_RANGER_06", "ARENA_SUP_RANGER_01")
        HeroClass.MAGE -> setOf("ARENA_SUP_MAGE_01", "ARENA_SUP_MAGE_07")
        HeroClass.CLERIC -> setOf("ARENA_SUP_CLERIC_01", "ARENA_SUP_CLERIC_06")
        HeroClass.PALADIN -> setOf("ARENA_SUP_PALADIN_01", "ARENA_SUP_PALADIN_09")
    }
}
