package com.alarmquest.engine

import com.alarmquest.model.AdventurePhase
import com.alarmquest.model.AdventureTaleState
import com.alarmquest.model.CombatPhase
import com.alarmquest.model.CompletedTaleRecord
import com.alarmquest.model.EquipmentSlot
import com.alarmquest.model.EquippedItem
import com.alarmquest.model.HeroClass
import com.alarmquest.model.HeroState
import com.alarmquest.model.HeroStats
import com.alarmquest.model.InventoryItem
import com.alarmquest.model.LearnedSkill
import com.alarmquest.model.MonsterState
import com.alarmquest.model.MonsterGrade
import com.alarmquest.model.SettlementDelta
import com.alarmquest.model.SIMPLE_GAME_SCHEMA_VERSION
import com.alarmquest.model.ShopEquipmentOffer
import com.alarmquest.model.SimpleGameState
import com.alarmquest.model.StatRoll
import com.alarmquest.model.TaleKind
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class LegacyAutoHuntSnapshot(
    val schemaVersion: Int,
    val chargeMillis: Long,
    val activeUntil: Long,
)

private data class CombatLoot(
    val name: String,
    val rarity: String,
    val kind: String,
    val equipped: Boolean = false,
    val equipmentSlot: EquipmentSlot? = null,
    val equipmentPower: Long? = null,
    val previousPower: Long? = null,
)

class SimpleGameEngine {
    fun rollStats(seed: Long): StatRoll {
        val rng = StableRng(seed)
        fun threeDice(): Long = 3L + rng.nextInt(6) + rng.nextInt(6) + rng.nextInt(6)

        val strength = threeDice()
        val constitution = threeDice()
        val dexterity = threeDice()
        val intelligence = threeDice()
        val wisdom = threeDice()
        val charisma = threeDice()
        return StatRoll(
            stats = HeroStats(
                strength = strength,
                constitution = constitution,
                dexterity = dexterity,
                intelligence = intelligence,
                wisdom = wisdom,
                charisma = charisma,
                maxHealth = rng.nextInt(8).toLong() + constitution / 6L,
                maxMana = rng.nextInt(8).toLong() +
                    manaBaseAttribute(intelligence, wisdom) / 6L,
            ),
            nextSeed = rng.state,
        )
    }

    fun newGame(
        name: String,
        heroClass: HeroClass,
        rolledStats: HeroStats,
        seed: Long,
        now: Long,
    ): SimpleGameState {
        val rng = StableRng(seed)
        val taleRng = StableRng(seed xor TALE_SEED_SALT)
        val hero = HeroState(
            name = name.trim().ifBlank { "이름 없는 모험가" }.take(16),
            heroClass = heroClass,
            stats = rolledStats.copyMutable(),
        )
        val state = SimpleGameState(
            hero = hero,
            equipment = starterEquipment(heroClass),
            adventureTale = createAdventureTale(
                definition = AdventureTaleCatalog.firstMain,
                sequence = 1L,
                level = 1L,
                heroName = hero.name,
                rng = taleRng,
            ),
            monster = MonsterState(0L, "", 1L, 1L),
            actionStartedAt = now,
            actionEndsAt = safeAdd(now, ENCOUNTER_REVEAL_MILLIS),
            lastSettledAt = now,
            rngState = rng.state,
            presentationRngState = seed xor PRESENTATION_SEED_SALT,
            taleRngState = taleRng.state,
            skillCatalogSeed = SkillCatalog.deriveSeed(seed, heroClass),
        )
        state.offlineAdventureMillis = OFFLINE_ADVENTURE_CAPACITY_MILLIS
        state.monster = createMonster(state, rng)
        state.rngState = rng.state
        return state
    }

    fun settle(state: SimpleGameState, now: Long): SettlementDelta {
        if (now <= state.lastSettledAt) return emptyDelta()
        val savedSchema = state.schemaVersion
        val startedAt = state.lastSettledAt
        val beforeKills = state.totalKills
        val beforeLevel = state.hero.level
        val beforeActs = state.totalActs
        val beforeTales = state.totalTales
        val beforeItems = state.totalItemsFound
        val gameplayRng = StableRng(state.rngState)
        val presentationRng = StableRng(state.presentationRngState)
        normalizeLegacyCombat(state, gameplayRng, savedSchema)

        while (state.actionEndsAt <= now) {
            val eventAt = state.actionEndsAt
            when (state.adventurePhase) {
                AdventurePhase.COMBAT -> when (state.combatPhase) {
                    CombatPhase.REVEAL,
                    CombatPhase.ATTACKING,
                    -> performAttack(state, presentationRng, eventAt)

                    CombatPhase.VICTORY -> finishVictory(state, gameplayRng, eventAt)
                }

                else -> finishTownAction(state, gameplayRng, eventAt)
            }
        }
        state.lastSettledAt = now
        state.rngState = gameplayRng.state
        state.presentationRngState = presentationRng.state
        return settlementDelta(
            state = state,
            startedAt = startedAt,
            beforeKills = beforeKills,
            beforeLevel = beforeLevel,
            beforeActs = beforeActs,
            beforeTales = beforeTales,
            beforeItems = beforeItems,
        )
    }

    /**
     * Resolves elapsed background time at combat boundaries. Attack names, damage rolls and
     * animation events are presentation-only, so they are not replayed while the app is away.
     */
    fun settleOffline(state: SimpleGameState, now: Long): SettlementDelta {
        if (now <= state.lastSettledAt) return emptyDelta()
        val savedSchema = state.schemaVersion
        val startedAt = state.lastSettledAt
        val beforeKills = state.totalKills
        val beforeLevel = state.hero.level
        val beforeActs = state.totalActs
        val beforeTales = state.totalTales
        val beforeItems = state.totalItemsFound
        val gameplayRng = StableRng(state.rngState)
        normalizeLegacyCombat(state, gameplayRng, savedSchema)

        while (state.actionEndsAt <= now) {
            if (state.adventurePhase != AdventurePhase.COMBAT) {
                finishTownAction(state, gameplayRng, state.actionEndsAt)
                continue
            }

            val completionAt = combatCompletionAt(state)
            if (completionAt > now) {
                fastForwardCurrentCombat(state, now)
                break
            }

            val skippedAttacks = remainingAttacks(state)
            state.actionSequence = safeAdd(state.actionSequence, skippedAttacks.toLong())
            state.monster.attacksCompleted = state.monster.expectedAttacks
            state.monster.currentEnergy = 0L
            completeCombat(state, gameplayRng)
            continueAfterVictory(state, completionAt)
        }
        clearAttackPresentation(state)
        state.lastSettledAt = now
        state.rngState = gameplayRng.state
        return settlementDelta(
            state = state,
            startedAt = startedAt,
            beforeKills = beforeKills,
            beforeLevel = beforeLevel,
            beforeActs = beforeActs,
            beforeTales = beforeTales,
            beforeItems = beforeItems,
        )
    }

    /**
     * Consumes the banked offline-adventure time at a one-to-one rate. Any uncovered part of
     * the absence pauses the combat timeline so old attack events cannot run on the next tick.
     */
    internal fun settleOfflineWithOfflineAdventure(
        state: SimpleGameState,
        now: Long,
        legacy: LegacyAutoHuntSnapshot? = null,
    ): SettlementDelta {
        val savedSchema = legacy?.schemaVersion ?: state.schemaVersion
        if (savedSchema < ACTIVE_COMBAT_REBASE_SCHEMA_VERSION) {
            val migrationRng = StableRng(state.rngState)
            normalizeLegacyCombat(state, migrationRng, savedSchema)
            state.rngState = migrationRng.state
        }
        synchronizeCatalogs(state, savedSchema)
        if (savedSchema < OFFLINE_ADVENTURE_SCHEMA_VERSION) {
            return migrateOfflineAdventure(state, now, savedSchema, legacy)
        }
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        state.offlineAdventureMillis = state.offlineAdventureMillis
            .coerceIn(0L, OFFLINE_ADVENTURE_CAPACITY_MILLIS)
        if (now <= state.lastSettledAt) return emptyDelta()

        val elapsed = now - state.lastSettledAt
        val coveredMillis = minOf(elapsed, state.offlineAdventureMillis)
        val coveredUntil = safeAdd(state.lastSettledAt, coveredMillis).coerceAtMost(now)
        val delta = if (coveredMillis > 0L) {
            settleOffline(state, coveredUntil)
        } else {
            emptyDelta()
        }
        state.offlineAdventureMillis = (state.offlineAdventureMillis - coveredMillis)
            .coerceAtLeast(0L)
        if (state.lastSettledAt < now) pauseTimeline(state, now)
        return delta
    }

    fun advanceOfflineAdventureForeground(
        state: SimpleGameState,
        foregroundElapsedMillis: Long,
    ) {
        if (foregroundElapsedMillis <= 0L) return
        val current = state.offlineAdventureMillis
            .coerceIn(0L, OFFLINE_ADVENTURE_CAPACITY_MILLIS)
        if (current >= OFFLINE_ADVENTURE_CAPACITY_MILLIS) {
            state.offlineAdventureMillis = OFFLINE_ADVENTURE_CAPACITY_MILLIS
            return
        }
        val earned = safeMul(foregroundElapsedMillis, OFFLINE_ADVENTURE_EARN_RATE)
        state.offlineAdventureMillis = safeAdd(current, earned)
            .coerceAtMost(OFFLINE_ADVENTURE_CAPACITY_MILLIS)
    }

    fun grantRewardedOfflineAdventure(
        state: SimpleGameState,
        rewardRequestId: String,
    ): Boolean {
        if (rewardRequestId.isBlank() || rewardRequestId == state.lastRewardRequestId) return false
        if (isOfflineAdventureFull(state)) return false
        state.lastRewardRequestId = rewardRequestId
        state.offlineAdventureMillis = OFFLINE_ADVENTURE_CAPACITY_MILLIS
        return true
    }

    fun offlineAdventureFraction(state: SimpleGameState): Float =
        (state.offlineAdventureMillis.toDouble() / OFFLINE_ADVENTURE_CAPACITY_MILLIS.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()

    fun isOfflineAdventureFull(state: SimpleGameState): Boolean =
        state.offlineAdventureMillis >= OFFLINE_ADVENTURE_CAPACITY_MILLIS

    private fun migrateOfflineAdventure(
        state: SimpleGameState,
        now: Long,
        savedSchema: Int,
        legacy: LegacyAutoHuntSnapshot?,
    ): SettlementDelta {
        if (savedSchema < LEGACY_AUTO_HUNT_SCHEMA_VERSION) {
            val delta = settleOffline(state, now)
            state.schemaVersion = CURRENT_SCHEMA_VERSION
            state.offlineAdventureMillis = OFFLINE_ADVENTURE_CAPACITY_MILLIS
            return delta
        }

        if (now <= state.lastSettledAt) {
            state.schemaVersion = CURRENT_SCHEMA_VERSION
            state.offlineAdventureMillis = legacyOfflineAdventureBalance(state, now, legacy)
            return emptyDelta()
        }
        val activeUntil = legacy?.activeUntil ?: 0L
        val coveredUntil = minOf(now, activeUntil).coerceAtLeast(state.lastSettledAt)
        val delta = if (coveredUntil > state.lastSettledAt) {
            settleOffline(state, coveredUntil)
        } else {
            emptyDelta()
        }
        if (state.lastSettledAt < now) pauseTimeline(state, now)
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        state.offlineAdventureMillis = legacyOfflineAdventureBalance(state, now, legacy)
        return delta
    }

    private fun legacyOfflineAdventureBalance(
        state: SimpleGameState,
        now: Long,
        legacy: LegacyAutoHuntSnapshot?,
    ): Long {
        val activeUntil = legacy?.activeUntil ?: 0L
        if (activeUntil > 0L) {
            return (activeUntil - now).coerceIn(0L, OFFLINE_ADVENTURE_CAPACITY_MILLIS)
        }
        val oldCharge = legacy?.chargeMillis ?: state.offlineAdventureMillis
        return safeMul(oldCharge.coerceAtLeast(0L), OFFLINE_ADVENTURE_EARN_RATE)
            .coerceAtMost(OFFLINE_ADVENTURE_CAPACITY_MILLIS)
    }

    private fun settlementDelta(
        state: SimpleGameState,
        startedAt: Long,
        beforeKills: Long,
        beforeLevel: Long,
        beforeActs: Long,
        beforeTales: Long,
        beforeItems: Long,
    ): SettlementDelta = SettlementDelta(
        elapsedMillis = state.lastSettledAt - startedAt,
        defeatedMonsters = state.totalKills - beforeKills,
        levelsGained = state.hero.level - beforeLevel,
        actsCompleted = state.totalActs - beforeActs,
        talesCompleted = state.totalTales - beforeTales,
        itemsFound = state.totalItemsFound - beforeItems,
    )

    private fun combatCompletionAt(state: SimpleGameState): Long = when (state.combatPhase) {
        CombatPhase.VICTORY -> state.actionEndsAt
        CombatPhase.REVEAL,
        CombatPhase.ATTACKING,
        -> safeAdd(
            safeAdd(
                state.actionEndsAt,
                safeMul((remainingAttacks(state) - 1).coerceAtLeast(0).toLong(), ATTACK_PRESENTATION_MILLIS),
            ),
            VICTORY_PRESENTATION_MILLIS,
        )
    }

    private fun remainingAttacks(state: SimpleGameState): Int =
        (state.monster.expectedAttacks - state.monster.attacksCompleted).coerceAtLeast(0)

    private fun fastForwardCurrentCombat(state: SimpleGameState, now: Long) {
        if (state.combatPhase == CombatPhase.VICTORY || state.actionEndsAt > now) return

        val remaining = remainingAttacks(state)
        if (remaining == 0) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionStartedAt = state.actionEndsAt
            state.actionEndsAt = safeAdd(state.actionEndsAt, VICTORY_PRESENTATION_MILLIS)
            return
        }

        val elapsedAfterNextAttack = now - state.actionEndsAt
        val dueByTime = safeAdd(elapsedAfterNextAttack / ATTACK_PRESENTATION_MILLIS, 1L)
        val dueAttacks = minOf(remaining.toLong(), dueByTime).toInt()
        val lastAttackAt = safeAdd(
            state.actionEndsAt,
            safeMul((dueAttacks - 1).toLong(), ATTACK_PRESENTATION_MILLIS),
        )
        state.monster.attacksCompleted += dueAttacks
        state.actionSequence = safeAdd(state.actionSequence, dueAttacks.toLong())
        updateMonsterEnergy(state)
        state.actionStartedAt = lastAttackAt

        if (state.monster.attacksCompleted == state.monster.expectedAttacks) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionEndsAt = safeAdd(lastAttackAt, VICTORY_PRESENTATION_MILLIS)
        } else {
            state.combatPhase = CombatPhase.ATTACKING
            state.actionEndsAt = safeAdd(lastAttackAt, ATTACK_PRESENTATION_MILLIS)
        }
    }

    private fun clearAttackPresentation(state: SimpleGameState) {
        state.lastAttackName = ""
        state.lastAttackType = ""
        state.lastAttackWasSkill = false
        state.lastSkillCatalogId = ""
        state.lastDamage = 0L
    }

    private fun pauseTimeline(state: SimpleGameState, now: Long) {
        val pausedMillis = (now - state.lastSettledAt).coerceAtLeast(0L)
        state.actionStartedAt = safeAdd(state.actionStartedAt, pausedMillis)
        state.actionEndsAt = safeAdd(state.actionEndsAt, pausedMillis)
        state.lastSettledAt = now
        clearAttackPresentation(state)
    }

    fun experienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            304L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    fun monsterEnergyFraction(state: SimpleGameState): Float =
        if (state.monster.maxEnergy <= 0L) {
            0f
        } else {
            (state.monster.currentEnergy.toDouble() / state.monster.maxEnergy.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    fun expectedCombatDurationRangeMillis(grade: MonsterGrade): LongRange =
        safeAdd(
            safeAdd(
                ENCOUNTER_REVEAL_MILLIS,
                safeMul((grade.minAttacks - 1).toLong(), ATTACK_PRESENTATION_MILLIS),
            ),
            VICTORY_PRESENTATION_MILLIS,
        )..
            safeAdd(
                safeAdd(
                    ENCOUNTER_REVEAL_MILLIS,
                    safeMul((grade.maxAttacks - 1).toLong(), ATTACK_PRESENTATION_MILLIS),
                ),
                VICTORY_PRESENTATION_MILLIS,
            )

    /** Class-guided level growth, reused by Adventure Tale completion. */
    fun applyClassGuidedGrowth(stats: HeroStats, heroClass: HeroClass, rngSeed: Long): Long {
        val rng = StableRng(rngSeed)
        applyClassGuidedGrowth(stats, heroClass, rng)
        return rng.state
    }

    private fun performAttack(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        if (state.monster.attacksCompleted >= state.monster.expectedAttacks) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, VICTORY_PRESENTATION_MILLIS)
            return
        }

        val skill = state.skills.takeIf { it.isNotEmpty() && rng.nextInt(100) < SKILL_USE_PERCENT }
            ?.let { skills -> skills[rng.nextInt(skills.size)] }
        val skillDefinition = skill?.catalogId?.let(SkillCatalog::find)
        val baseDamage = baseAttackDamage(state)
        val abilityPercent = if (skill == null) {
            100L
        } else {
            skillDefinition?.damagePercent?.toLong()
                ?: (120L + ((skill.id - 1).coerceAtLeast(0) * 3L))
        }
        val variedDamage = scalePercent(
            scalePercent(baseDamage, abilityPercent),
            90L + rng.nextInt(21),
        ).coerceAtLeast(1L)
        state.monster.attacksCompleted = (state.monster.attacksCompleted + 1)
            .coerceAtMost(state.monster.expectedAttacks)
        updateMonsterEnergy(state)
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.lastDamage = variedDamage
        state.lastAttackWasSkill = skill != null
        state.lastSkillCatalogId = skillDefinition?.catalogId.orEmpty()
        state.lastAttackName = skill?.name.orEmpty()
        state.lastAttackType = if (skill == null) "기본 공격" else "보유 스킬"
        state.lastResult = if (skill == null) {
            "기본 공격 · ${variedDamage} 피해"
        } else {
            "${skill.name} · ${variedDamage} 피해"
        }

        if (state.monster.attacksCompleted == state.monster.expectedAttacks) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionEndsAt = safeAdd(eventAt, VICTORY_PRESENTATION_MILLIS)
        } else {
            state.combatPhase = CombatPhase.ATTACKING
            state.actionEndsAt = safeAdd(eventAt, ATTACK_PRESENTATION_MILLIS)
        }
    }

    private fun updateMonsterEnergy(state: SimpleGameState) {
        val remainingAttacks = remainingAttacks(state)
        state.monster.currentEnergy = if (remainingAttacks == 0) {
            0L
        } else {
            (remainingAttacks.toLong() * MONSTER_ENERGY_SCALE) / state.monster.expectedAttacks.toLong()
        }
    }

    private fun finishVictory(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        completeCombat(state, rng)
        continueAfterVictory(state, eventAt)
    }

    private fun continueAfterVictory(state: SimpleGameState, eventAt: Long) {
        beginLooting(state, eventAt)
        clearAttackPresentation(state)
    }

    private fun beginLooting(state: SimpleGameState, eventAt: Long) {
        state.adventurePhase = AdventurePhase.LOOTING
        state.combatPhase = CombatPhase.VICTORY
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, LOOT_RESULT_MILLIS)
        state.lastResult = state.lastLootSummary
    }

    private fun finishLooting(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) {
            beginReturning(state, eventAt)
        } else {
            beginCombat(state, rng, eventAt)
        }
    }

    private fun completeCombat(state: SimpleGameState, rng: StableRng) {
        val defeated = state.monster
        state.totalKills = safeIncrement(state.totalKills)

        val gradeMultiplier = when (defeated.grade) {
            MonsterGrade.NORMAL -> 1L
            MonsterGrade.ELITE -> 3L
            MonsterGrade.BOSS -> 8L
        }
        val xp = safeMul(safeAdd(16L, safeMul(defeated.level, 4L)), gradeMultiplier)
        grantExperience(state, xp, rng)
        val loot = if (rng.nextInt(100) < equipmentDropPercent(defeated)) {
            addEquipmentDrop(state, rng)
        } else {
            addTrophy(state, rng, defeated)
        }
        recordLootPresentation(state, loot)
        advanceTaleOnVictory(state, rng, defeated)
        state.lastResult = "${defeated.name} 처치 · 경험치 +$xp"
    }

    private fun beginReturning(state: SimpleGameState, eventAt: Long) {
        state.adventurePhase = AdventurePhase.RETURNING
        state.combatPhase = CombatPhase.VICTORY
        state.totalReturns = safeIncrement(state.totalReturns)
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, RETURN_TO_TOWN_MILLIS)
        state.lastTownItemName = ""
        state.lastTownGold = 0L
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
        clearLootPresentation(state)
        state.lastResult = "가방이 가득 차 마을로 귀환 중"
    }

    private fun beginCombat(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        state.monster = createMonster(state, rng)
        state.adventurePhase = AdventurePhase.COMBAT
        state.combatPhase = CombatPhase.REVEAL
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, ENCOUNTER_REVEAL_MILLIS)
        state.lastTownItemName = ""
        state.lastTownGold = 0L
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
        clearLootPresentation(state)
    }

    private fun finishTownAction(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        state.actionSequence = safeIncrement(state.actionSequence)
        when (state.adventurePhase) {
            AdventurePhase.LOOTING -> finishLooting(state, rng, eventAt)
            AdventurePhase.RETURNING -> beginEquipmentSortingOrSelling(state, rng, eventAt)
            AdventurePhase.EQUIPPING -> beginSellingOrShopping(state, rng, eventAt)
            AdventurePhase.SELLING -> beginSellingOrShopping(state, rng, eventAt)
            AdventurePhase.SHOPPING -> buyEquipment(state, rng, eventAt)
            AdventurePhase.SHOPPING_RESULT -> beginShoppingOrDeparting(state, rng, eventAt)
            AdventurePhase.DEPARTING -> beginCombat(state, rng, eventAt)
            AdventurePhase.COMBAT -> Unit
        }
    }

    private fun beginEquipmentSortingOrSelling(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
    ) {
        val equipmentLoot = state.inventory.count {
            it.kind == "장비" && it.equipmentSlot != null && it.equipmentPower != null
        }
        if (equipmentLoot == 0) {
            beginSellingOrShopping(state, rng, eventAt)
            return
        }

        val equippedNames = equipBestLoot(state)
        if (equippedNames.isEmpty()) {
            beginSellingOrShopping(state, rng, eventAt)
            return
        }
        state.adventurePhase = AdventurePhase.EQUIPPING
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, EQUIP_LOOT_MILLIS)
        state.lastTownItemName = equippedNames.joinToString(", ")
        state.lastTownGold = equippedNames.size.toLong()
        state.lastResult = if (equippedNames.isEmpty()) {
            "현재 장비보다 나은 전리품이 없음"
        } else {
            "드롭 장비 ${equippedNames.size}부위 교체"
        }
    }

    private fun equipBestLoot(state: SimpleGameState): List<String> {
        val equippedNames = mutableListOf<String>()
        EquipmentSlot.entries.forEach { slot ->
            val best = state.inventory.withIndex()
                .filter { (_, item) ->
                    item.kind == "장비" &&
                        item.equipmentSlot == slot &&
                        item.equipmentPower != null
                }
                .maxWithOrNull(
                    compareBy<IndexedValue<InventoryItem>> { it.value.equipmentPower ?: Long.MIN_VALUE }
                        .thenBy { rarityRank(it.value.rarity) },
                ) ?: return@forEach
            val current = state.equipment.first { it.slot == slot }
            val candidate = best.value
            val candidatePower = candidate.equipmentPower ?: return@forEach
            val isUpgrade = candidatePower > current.power ||
                (candidatePower == current.power && rarityRank(candidate.rarity) > rarityRank(current.rarity))
            if (!isUpgrade) return@forEach

            val replaced = InventoryItem(
                id = candidate.id,
                name = current.name,
                rarity = current.rarity,
                kind = "장비",
                foundAtLevel = current.acquiredAtLevel,
                equipmentSlot = current.slot,
                equipmentPower = current.power,
            )
            current.name = candidate.name
            current.power = candidatePower
            current.rarity = candidate.rarity
            current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
            state.inventory[best.index] = replaced
            state.totalLootEquipmentEquips = safeIncrement(state.totalLootEquipmentEquips)
            equippedNames += current.name
        }
        return equippedNames
    }

    private fun beginSellingOrShopping(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
    ) {
        val sold = state.inventory.removeLastOrNull()
        if (sold == null) {
            beginShoppingOrDeparting(state, rng, eventAt)
            return
        }
        val value = saleValue(sold)
        state.hero.gold = safeAdd(state.hero.gold, value)
        state.totalItemsSold = safeIncrement(state.totalItemsSold)
        state.totalSaleGold = safeAdd(state.totalSaleGold, value)
        state.adventurePhase = AdventurePhase.SELLING
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, SELL_ITEM_MILLIS)
        state.lastTownItemName = sold.name
        state.lastTownGold = value
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.lastResult = "${sold.name} 판매 · +${value}G"
    }

    private fun beginShoppingOrDeparting(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
    ) {
        val offer = createShopOffer(state, rng)
        state.lastShopPurchase = null
        if (offer != null) {
            state.pendingShopOffer = offer
            state.adventurePhase = AdventurePhase.SHOPPING
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, SHOP_OFFER_MILLIS)
            state.lastTownItemName = offer.name
            state.lastTownGold = offer.price
            state.lastResult = "${offer.name} 자동 구매 예정 · ${offer.price}G"
        } else {
            state.pendingShopOffer = null
            state.adventurePhase = AdventurePhase.DEPARTING
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, DEPART_TO_FIELDS_MILLIS)
            state.lastTownItemName = ""
            state.lastTownGold = 0L
            state.lastResult = "사냥터로 다시 출정 중"
        }
    }

    private fun buyEquipment(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        val offer = state.pendingShopOffer ?: createShopOffer(state, rng)
        val current = offer?.let { pending ->
            state.equipment.firstOrNull { it.slot == pending.slot }
        }
        if (offer != null && current != null && state.hero.gold < offer.price) {
            state.shopAttemptedSlots.replaceAllWithEquipmentSlots()
            state.pendingShopOffer = null
            beginShoppingOrDeparting(state, rng, eventAt)
            return
        }
        if (
            offer == null ||
            current == null ||
            current.power != offer.previousPower ||
            offer.newPower <= current.power ||
            rarityRank(offer.rarity) > SHOP_MAX_RARITY_RANK
        ) {
            state.pendingShopOffer = null
            beginShoppingOrDeparting(state, rng, eventAt)
            return
        }

        state.hero.gold -= offer.price
        current.name = offer.name
        current.power = offer.newPower
        current.rarity = offer.rarity
        current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
        state.totalEquipmentPurchases = safeIncrement(state.totalEquipmentPurchases)
        state.pendingShopOffer = null
        state.lastShopPurchase = offer
        state.lastTownItemName = offer.name
        state.lastTownGold = offer.price
        state.lastResult = "${offer.name} 구매 · -${offer.price}G"
        state.adventurePhase = AdventurePhase.SHOPPING_RESULT
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, SHOP_RESULT_MILLIS)
    }

    private fun createShopOffer(state: SimpleGameState, rng: StableRng): ShopEquipmentOffer? {
        while (true) {
            val remainingSlots = EquipmentSlot.entries.filterNot(state.shopAttemptedSlots::contains)
            if (remainingSlots.isEmpty()) return null

            val slot = remainingSlots.pick(rng)
            state.shopAttemptedSlots += slot
            val current = state.equipment.firstOrNull { it.slot == slot } ?: continue
            val rolledRarity = shopRarity(rng)
            val candidate = equipmentCandidate(state, rng, slot, rolledRarity, forShop = true)
            val protectsHighRarityAtAcquisitionLevel =
                rarityRank(current.rarity) > SHOP_MAX_RARITY_RANK &&
                    state.hero.level <= current.acquiredAtLevel
            if (protectsHighRarityAtAcquisitionLevel || candidate.power <= current.power) continue

            val price = equipmentPrice(state.hero.level, slot)
            if (state.hero.gold < price) {
                state.shopAttemptedSlots.replaceAllWithEquipmentSlots()
                return null
            }
            return ShopEquipmentOffer(
                slot = slot,
                name = candidate.name,
                rarity = candidate.rarity,
                previousPower = current.power,
                newPower = candidate.power,
                price = price,
            )
        }
    }

    private fun saleValue(item: InventoryItem): Long = safeMul(
        ((item.foundAtLevel.coerceAtLeast(1L) - 1L) / SALE_LEVELS_PER_STEP + 1L)
            .coerceAtMost(MAX_SALE_LEVEL_MULTIPLIER),
        rarityRank(item.rarity).toLong() + 1L,
    )

    internal fun saleValueForTest(item: InventoryItem): Long = saleValue(item)

    fun equipmentPrice(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeAdd(
            safeAdd(safeMul(5L, safeMul(safeLevel, safeLevel)), safeMul(10L, safeLevel)),
            20L,
        )
    }

    fun equipmentPrice(level: Long, slot: EquipmentSlot): Long =
        scalePercentRounded(equipmentPrice(level), equipmentPricePercent(slot))

    internal fun equipmentPricePercent(slot: EquipmentSlot): Long = when (slot) {
        EquipmentSlot.WEAPON -> 150L
        EquipmentSlot.BODY -> 120L
        EquipmentSlot.HEAD -> 100L
        EquipmentSlot.HANDS -> 90L
        EquipmentSlot.FEET -> 90L
        EquipmentSlot.ACCESSORY -> 80L
    }

    fun averageEquipmentPower(state: SimpleGameState): Long {
        if (state.equipment.isEmpty()) return 0L
        val count = state.equipment.size.toLong()
        var quotientSum = 0L
        var remainderSum = 0L
        state.equipment.forEach { item ->
            val power = item.power.coerceAtLeast(0L)
            quotientSum = safeAdd(quotientSum, power / count)
            remainderSum = safeAdd(remainderSum, power % count)
        }
        return safeAdd(
            quotientSum,
            safeAdd(remainderSum, count / 2L) / count,
        )
    }

    fun characterStatPower(state: SimpleGameState): Long {
        val stats = state.hero.stats
        val combatStats = stats.values().take(PRIMARY_STAT_COUNT)
        val (primaryIndex, secondaryIndex) = combatStatIndices(state.hero.heroClass)
        val primary = combatStats[primaryIndex]
        val secondary = combatStats[secondaryIndex]
        val weightedStatTenths = safeAdd(
            safeMul(primary.coerceAtLeast(0L), PRIMARY_STAT_WEIGHT),
            safeMul(secondary.coerceAtLeast(0L), SECONDARY_STAT_WEIGHT),
        )
        if (weightedStatTenths == 0L) return 0L

        val expectedStatThirtieths = expectedWeightedStatThirtieths(
            level = state.hero.level,
            classGuidedLevelGrowths = state.classGuidedLevelGrowths,
        )
        return normalizedStatCombatPower(
            weightedStatTenths = weightedStatTenths,
            expectedStatThirtieths = expectedStatThirtieths,
            equipmentBenchmark = expectedEquipmentCombatPower(state.hero.level),
        )
    }

    internal fun expectedWeightedStatThirtieths(
        level: Long,
        classGuidedLevelGrowths: Long = levelProgress(level),
    ): Long {
        val totalLevelGrowths = levelProgress(level)
        val guidedGrowths = classGuidedLevelGrowths.coerceIn(0L, totalLevelGrowths)
        val legacyGrowths = totalLevelGrowths - guidedGrowths
        return safeAdd(
            BASE_EXPECTED_STAT_THIRTIETHS,
            safeAdd(
                safeMul(legacyGrowths, LEGACY_EXPECTED_STAT_GROWTH_THIRTIETHS),
                safeMul(guidedGrowths, EXPECTED_STAT_GROWTH_THIRTIETHS),
            ),
        )
    }

    fun displayCombatPower(state: SimpleGameState): Long =
        safeAdd(characterStatPower(state), averageEquipmentPower(state))

    /** Deterministic 100% variance damage used only by the debug skill-effect viewer. */
    internal fun skillPreviewDamage(state: SimpleGameState, catalogId: String): Long {
        val definition = SkillCatalog.find(catalogId) ?: return baseAttackDamage(state)
        return scalePercent(baseAttackDamage(state), definition.damagePercent.toLong())
            .coerceAtLeast(1L)
    }

    internal fun expectedEquipmentCombatPower(level: Long): Long = safeAdd(
        BASE_EXPECTED_EQUIPMENT_POWER,
        safeMul(
            levelProgress(level),
            EXPECTED_EQUIPMENT_POWER_PER_LEVEL,
        ),
    )

    internal fun expectedCombatPower(state: SimpleGameState): Long =
        safeMul(expectedEquipmentCombatPower(state.hero.level), 2L)

    fun combatDurationPercent(state: SimpleGameState): Int {
        val expectedPower = expectedCombatPower(state).coerceAtLeast(1L).toDouble()
        val actualPower = displayCombatPower(state).coerceAtLeast(0L).toDouble()
        val relativeDifference = (actualPower - expectedPower) / expectedPower
        return (100.0 - relativeDifference * 50.0)
            .roundToInt()
            .coerceIn(MIN_COMBAT_DURATION_PERCENT, MAX_COMBAT_DURATION_PERCENT)
    }

    internal fun attackCountForCombatPower(state: SimpleGameState, baseAttacks: Int): Int {
        val scaled = (baseAttacks.toLong() * combatDurationPercent(state).toLong() + 50L) / 100L
        return scaled.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun grantExperience(state: SimpleGameState, amount: Long, rng: StableRng) {
        state.hero.experience = safeAdd(state.hero.experience, amount)
        while (state.hero.experience >= experienceRequired(state.hero.level)) {
            val required = experienceRequired(state.hero.level)
            state.hero.experience -= required
            state.hero.level = safeIncrement(state.hero.level)
            applyClassGuidedGrowth(state.hero.stats, state.hero.heroClass, rng)
            state.classGuidedLevelGrowths = safeIncrement(state.classGuidedLevelGrowths)
            learnSkillIfNeeded(state)
        }
    }

    private fun applyClassGuidedGrowth(
        stats: HeroStats,
        heroClass: HeroClass,
        rng: StableRng,
    ) {
        stats.maxHealth = safeAdd(
            stats.maxHealth,
            safeAdd(stats.constitution / 3L + 1L, rng.nextInt(4).toLong()),
        )
        stats.maxMana = safeAdd(
            stats.maxMana,
            safeAdd(
                manaBaseAttribute(stats.intelligence, stats.wisdom) / 3L + 1L,
                rng.nextInt(4).toLong(),
            ),
        )
        val (primaryIndex, secondaryIndex) = combatStatIndices(heroClass)
        stats.increment(if (rng.nextInt(2) == 0) primaryIndex else secondaryIndex)
        stats.increment(rng.nextInt(PRIMARY_STAT_COUNT))
    }

    private fun combatStatIndices(heroClass: HeroClass): Pair<Int, Int> = when (heroClass) {
        HeroClass.WARRIOR -> 0 to 1
        HeroClass.ROGUE -> 2 to 0
        HeroClass.RANGER -> 2 to 4
        HeroClass.MAGE -> 3 to 4
        HeroClass.CLERIC -> 4 to 3
        HeroClass.PALADIN -> 0 to 5
    }

    private fun learnSkillIfNeeded(state: SimpleGameState) {
        if (state.hero.level % 5L != 0L || state.skills.size >= MAX_SKILLS) return
        ensureSkillCatalogSeed(state)
        val tier = (state.hero.level / 5L).toInt().coerceIn(1, MAX_SKILLS)
        if (state.skills.any { it.acquiredAtLevel / 5L == tier.toLong() }) return
        val definition = SkillCatalog.select(state.skillCatalogSeed, state.hero.heroClass, tier)
        state.skills += LearnedSkill(
            id = tier,
            name = definition.name,
            acquiredAtLevel = state.hero.level,
            description = definition.description,
            catalogId = definition.catalogId,
        )
    }

    private fun advanceTaleOnVictory(
        state: SimpleGameState,
        gameplayRng: StableRng,
        defeated: MonsterState,
    ) {
        val tale = state.adventureTale
        val act = tale.activeAct()
        val nextProgress = safeIncrement(act.progress).coerceAtMost(act.target)
        if (nextProgress >= act.target && defeated.grade != MonsterGrade.BOSS) {
            act.progress = (act.target - 1L).coerceAtLeast(0L)
            return
        }
        act.progress = nextProgress
        if (act.progress < act.target) return

        act.completed = true
        state.totalActs = safeIncrement(state.totalActs)
        state.hero.gold = safeAdd(state.hero.gold, act.rewardGold)
        grantExperience(state, act.rewardExperience, gameplayRng)

        if (tale.currentActIndex < tale.acts.lastIndex) {
            tale.currentActIndex += 1
            return
        }

        state.totalTales = safeIncrement(state.totalTales)
        applyClassGuidedGrowth(state.hero.stats, state.hero.heroClass, gameplayRng)
        state.completedTaleHistory += CompletedTaleRecord(
            taleSequence = tale.sequence,
            taleId = tale.definitionId,
            kind = tale.kind,
            volumeNumber = tale.volumeNumber,
            chapterNumber = tale.chapterNumber,
            title = tale.title,
            summary = tale.ending,
            nextHook = tale.nextHook,
            actMemories = tale.acts.map { it.completionBody },
            completedAtLevel = state.hero.level,
        )
        trimRepeatableTaleHistory(state)

        val taleRng = StableRng(state.taleRngState)
        val currentDefinition = AdventureTaleCatalog.requireDefinition(tale.definitionId)
        val nextDefinition = AdventureTaleCatalog.nextDefinition(currentDefinition, tale.sequence)
        state.adventureTale = createAdventureTale(
            definition = nextDefinition,
            sequence = safeIncrement(tale.sequence),
            level = state.hero.level,
            heroName = state.hero.name,
            rng = taleRng,
        )
        state.taleRngState = taleRng.state
    }

    private fun createAdventureTale(
        definition: AdventureTaleDefinition,
        sequence: Long,
        level: Long,
        heroName: String,
        rng: StableRng,
    ): AdventureTaleState {
        val variant = AdventureTaleCatalog.variantAt(rng.nextInt(AdventureTaleCatalog.variantCount()))
        return AdventureTaleCatalog.instantiate(
            definition = definition,
            sequence = sequence,
            heroName = heroName,
            heroLevel = level,
            variant = variant,
        )
    }

    private fun trimRepeatableTaleHistory(state: SimpleGameState) {
        while (state.completedTaleHistory.count { it.kind == TaleKind.EPILOGUE } > MAX_EPILOGUE_HISTORY) {
            val oldestEpilogue = state.completedTaleHistory.indexOfFirst { it.kind == TaleKind.EPILOGUE }
            if (oldestEpilogue < 0) return
            state.completedTaleHistory.removeAt(oldestEpilogue)
        }
    }

    private fun createMonster(state: SimpleGameState, rng: StableRng): MonsterState {
        val levelDelta = rng.nextInt(5) - 2
        val level = (state.hero.level + levelDelta).coerceAtLeast(1L)
        val tale = state.adventureTale
        val actIndex = tale.currentActIndex.coerceIn(0, tale.acts.lastIndex)
        val act = tale.acts[actIndex]
        val grade = QuestMonsterCatalog.encounterGrade(act.progress, act.target)
        val baseAttacks = grade.minAttacks + rng.nextInt(grade.maxAttacks - grade.minAttacks + 1)
        val group = QuestMonsterCatalog.groupFor(tale.definitionId)
        var catalogId = ""
        var baseName = ""
        val name = when {
            group == null -> uniqueName(state.recentMonsterNames, RECENT_MONSTERS, rng) {
                "${SimpleContent.monsterAdjectives.pick(rng)} ${SimpleContent.monsterKinds.pick(rng)}"
            }

            grade == MonsterGrade.NORMAL -> {
                val pool = group.normals.take(QuestMonsterCatalog.normalPoolSize(actIndex))
                uniqueName(state.recentMonsterNames, RECENT_MONSTERS, rng) {
                    val definition = pool.pick(rng)
                    catalogId = definition.id
                    baseName = definition.baseName
                    "${group.adjectives.pick(rng)} ${definition.baseName}"
                }
            }

            grade == MonsterGrade.ELITE -> {
                val definition = group.elites[QuestMonsterCatalog.eliteIndex(act.progress, act.target)]
                catalogId = definition.id
                baseName = definition.baseName
                definition.baseName
            }

            else -> {
                val definition = group.bosses[actIndex]
                catalogId = definition.id
                baseName = definition.baseName
                definition.baseName
            }
        }
        return MonsterState(
            id = safeIncrement(state.totalKills),
            name = name,
            level = level,
            maxEnergy = MONSTER_ENERGY_SCALE,
            grade = grade,
            currentEnergy = MONSTER_ENERGY_SCALE,
            expectedAttacks = attackCountForCombatPower(state, baseAttacks),
            attacksCompleted = 0,
            catalogId = catalogId,
            baseName = baseName.ifBlank { name },
            isFinalBoss = grade == MonsterGrade.BOSS && actIndex == tale.acts.lastIndex,
        )
    }

    /**
     * Converts the former health-like monster value into a presentation-only battle gauge.
     * The original action boundary is replaced with the first reveal boundary so every
     * elapsed period is replayed through the same deterministic attack timeline.
     */
    private fun normalizeLegacyCombat(
        state: SimpleGameState,
        rng: StableRng,
        savedSchema: Int = state.schemaVersion,
    ) {
        val storyRebased = synchronizeCatalogs(state, savedSchema)
        synchronizePendingShopOffer(state, rng)
        if (storyRebased) state.monster = createMonster(state, rng)
        val needsCombatRebaseline =
            savedSchema < ACTIVE_COMBAT_REBASE_SCHEMA_VERSION &&
                state.adventurePhase == AdventurePhase.COMBAT
        val invalidTimeline = state.monster.maxEnergy != MONSTER_ENERGY_SCALE ||
            state.monster.expectedAttacks <= 0 ||
            state.monster.attacksCompleted !in 0..state.monster.expectedAttacks ||
            needsCombatRebaseline ||
            storyRebased
        if (!invalidTimeline) return

        val grade = state.monster.grade
        state.monster.maxEnergy = MONSTER_ENERGY_SCALE
        state.monster.currentEnergy = MONSTER_ENERGY_SCALE
        state.monster.expectedAttacks = attackCountForCombatPower(
            state,
            grade.minAttacks + rng.nextInt(grade.maxAttacks - grade.minAttacks + 1),
        )
        state.monster.attacksCompleted = 0
        state.combatPhase = CombatPhase.REVEAL
        state.actionStartedAt = state.lastSettledAt
        state.actionEndsAt = safeAdd(state.lastSettledAt, ENCOUNTER_REVEAL_MILLIS)
        clearAttackPresentation(state)
    }

    private fun synchronizePendingShopOffer(state: SimpleGameState, rng: StableRng) {
        if (state.adventurePhase != AdventurePhase.SHOPPING) return
        state.pendingShopOffer?.let { pending ->
            if (pending.slot !in state.shopAttemptedSlots) {
                state.shopAttemptedSlots += pending.slot
            }
            return
        }
        val offer = createShopOffer(state, rng)
        if (offer == null) {
            state.adventurePhase = AdventurePhase.DEPARTING
            state.lastTownItemName = ""
            state.lastTownGold = 0L
            state.lastResult = "사냥터로 다시 출정 중"
            return
        }
        state.pendingShopOffer = offer
        state.lastShopPurchase = null
        state.lastTownItemName = offer.name
        state.lastTownGold = offer.price
        state.lastResult = "${offer.name} 자동 구매 예정 · ${offer.price}G"
    }

    private fun synchronizeCatalogs(state: SimpleGameState, savedSchema: Int): Boolean {
        val stateSchema = state.schemaVersion
        var storyRebased = false
        if (savedSchema < STORY_EXPANSION_SCHEMA_VERSION && stateSchema < STORY_EXPANSION_SCHEMA_VERSION) {
            storyRebased = migrateExpandedMainStory(state)
            if (!storyRebased) {
                migrateProgressionTargets(
                    state = state,
                    scaleRewards = savedSchema < HUNTING_PACING_SCHEMA_VERSION,
                )
            }
        }
        if (savedSchema < EXPERIENCE_CURVE_SCHEMA_VERSION && stateSchema < EXPERIENCE_CURVE_SCHEMA_VERSION) {
            migrateExperienceProgress(state, savedSchema)
        }
        if (savedSchema < ATTACK_SKILL_CATALOG_SCHEMA_VERSION) {
            synchronizeAttackSkillCatalog(state)
        }
        if (
            savedSchema < CLASS_SKILL_CATALOG_SCHEMA_VERSION ||
            state.skillCatalogSeed == 0L ||
            state.skills.any { it.catalogId.isBlank() }
        ) {
            synchronizeClassSkillCatalog(state)
        }
        synchronizeSkillDisplayNames(state)
        if (savedSchema < DISPLAY_NAME_SCHEMA_VERSION) {
            synchronizeEquipmentNames(state)
            synchronizeMonsterNames(state)
        }
        if (savedSchema < SHOP_POWER_FIRST_SCHEMA_VERSION) {
            migrateShopPowerFirstState(state)
        }
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        return storyRebased
    }

    private fun migrateShopPowerFirstState(state: SimpleGameState) {
        val migrationLevel = state.hero.level.coerceAtLeast(1L)
        state.equipment.forEach { item -> item.acquiredAtLevel = migrationLevel }
        state.shopAttemptedSlots.clear()
        if (state.adventurePhase == AdventurePhase.SHOPPING) {
            state.pendingShopOffer = null
            state.lastShopPurchase = null
        }
    }

    private fun migrateExpandedMainStory(state: SimpleGameState): Boolean {
        val oldTale = state.adventureTale
        if (oldTale.kind != TaleKind.EPILOGUE) return false
        val firstSecondVolume = AdventureTaleCatalog.mainTales[LEGACY_MAIN_TALE_COUNT]
        state.adventureTale = AdventureTaleCatalog.instantiate(
            definition = firstSecondVolume,
            sequence = LEGACY_MAIN_TALE_COUNT.toLong() + 1L,
            heroName = state.hero.name,
            heroLevel = state.hero.level,
            variant = oldTale.variant,
        )
        return true
    }

    private fun migrateProgressionTargets(
        state: SimpleGameState,
        scaleRewards: Boolean,
    ) {
        val tale = state.adventureTale
        tale.currentActIndex = tale.currentActIndex.coerceIn(0, tale.acts.lastIndex)
        tale.acts.indices.forEach { index ->
            val old = tale.acts[index]
            val newTarget = AdventureTaleCatalog.targetFor(tale.definitionId, index)
            val wasCompleted = old.completed || index < tale.currentActIndex
            val migratedProgress = if (wasCompleted) {
                newTarget
            } else {
                val oldTarget = old.target.coerceAtLeast(1L)
                val ratioProgress = (old.progress.coerceAtLeast(0L).toDouble() /
                    oldTarget.toDouble() * newTarget.toDouble()).toLong()
                val upperBound = if (index == tale.currentActIndex) {
                    (newTarget - 1L).coerceAtLeast(0L)
                } else {
                    newTarget
                }
                ratioProgress.coerceIn(0L, upperBound)
            }
            tale.acts[index] = old.copy(
                progress = migratedProgress,
                target = newTarget,
                rewardExperience = if (scaleRewards) {
                    scaleFormerPacingValue(old.rewardExperience)
                } else {
                    old.rewardExperience
                },
                completed = wasCompleted,
            )
        }
    }

    private fun migrateExperienceProgress(state: SimpleGameState, savedSchema: Int) {
        val oldRequired = when {
            savedSchema < 20 -> legacyExperienceRequired(state.hero.level)
            savedSchema < 21 -> formerExperienceRequired(state.hero.level)
            else -> previousExperienceRequired(state.hero.level)
        }.coerceAtLeast(1L)
        val newRequired = experienceRequired(state.hero.level).coerceAtLeast(1L)
        if (oldRequired == newRequired) return
        val migrated = state.hero.experience.coerceAtLeast(0L).toDouble() /
            oldRequired.toDouble() * newRequired.toDouble()
        state.hero.experience = when {
            !migrated.isFinite() || migrated >= newRequired.toDouble() -> newRequired - 1L
            else -> migrated.toLong().coerceIn(0L, newRequired - 1L)
        }
    }

    private fun formerExperienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            210L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    private fun previousExperienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            345L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    private fun legacyExperienceRequired(level: Long): Long {
        val step = max(0L, level - 1L)
        return safeAdd(
            100L,
            safeAdd(safeMul(step, 40L), safeMul(safeMul(step, step), 8L)),
        )
    }

    private fun scaleFormerPacingValue(value: Long): Long {
        if (value <= 0L) return 0L
        val whole = safeMul(value / 20L, 29L)
        val remainder = safeAdd(safeMul(value % 20L, 29L), 10L) / 20L
        return safeAdd(whole, remainder)
    }

    private fun ensureSkillCatalogSeed(state: SimpleGameState) {
        if (state.skillCatalogSeed != 0L) return
        val heroSalt = state.hero.name.fold(0L) { hash, character ->
            hash * 31L + character.code.toLong()
        }
        state.skillCatalogSeed = SkillCatalog.deriveSeed(
            state.rngState xor state.presentationRngState xor heroSalt,
            state.hero.heroClass,
        )
    }

    private fun synchronizeClassSkillCatalog(state: SimpleGameState) {
        ensureSkillCatalogSeed(state)
        state.skills.indices.forEach { index ->
            val learned = state.skills[index]
            val tier = when {
                learned.acquiredAtLevel >= 5L -> (learned.acquiredAtLevel / 5L).toInt()
                else -> learned.id
            }.coerceIn(1, MAX_SKILLS)
            val definition = SkillCatalog.select(
                state.skillCatalogSeed,
                state.hero.heroClass,
                tier,
            )
            state.skills[index] = learned.copy(
                id = tier,
                name = definition.name,
                acquiredAtLevel = tier * 5L,
                description = definition.description,
                catalogId = definition.catalogId,
            )
        }
    }

    private fun synchronizeSkillDisplayNames(state: SimpleGameState) {
        state.skills.indices.forEach { index ->
            val learned = state.skills[index]
            val definition = SkillCatalog.find(learned.catalogId) ?: return@forEach
            if (learned.name != definition.name || learned.description != definition.description) {
                state.skills[index] = learned.copy(
                    name = definition.name,
                    description = definition.description,
                )
            }
        }

        val activeDefinition = SkillCatalog.find(state.lastSkillCatalogId) ?: return
        val previousAttackName = state.lastAttackName
        state.lastAttackName = activeDefinition.name
        if (
            state.lastAttackWasSkill &&
            previousAttackName.isNotBlank() &&
            state.lastResult.startsWith("$previousAttackName · ")
        ) {
            state.lastResult = activeDefinition.name + state.lastResult.removePrefix(previousAttackName)
        }
    }

    private fun synchronizeAttackSkillCatalog(state: SimpleGameState) {
        state.skills.indices.forEach { index ->
            val learned = state.skills[index]
            val (name, description) = SimpleContent.skills.getOrNull(learned.id - 1) ?: return@forEach
            state.skills[index] = learned.copy(
                name = name,
                description = description,
            )
        }
    }

    private fun synchronizeEquipmentNames(state: SimpleGameState) {
        state.equipment.forEach { item ->
            item.name = normalizedEquipmentName(item.name, item.rarity)
        }
        state.inventory.indices.forEach { index ->
            val item = state.inventory[index]
            if (item.kind == "장비") {
                state.inventory[index] = item.copy(
                    name = normalizedEquipmentName(item.name, item.rarity),
                )
            }
        }
    }

    private fun synchronizeMonsterNames(state: SimpleGameState) {
        state.monster.name = normalizedMonsterName(state.monster.name)
        state.recentMonsterNames = state.recentMonsterNames
            .map(::normalizedMonsterName)
            .filter { it.isNotBlank() }
            .distinct()
            .take(RECENT_MONSTERS)
            .toMutableList()
        state.lastResult = state.lastResult.replace(LEGACY_MONSTER_EPITHET_IN_RESULT, "")
    }

    /** Damage is descriptive feedback only; battle completion is driven solely by time. */
    private fun baseAttackDamage(state: SimpleGameState): Long {
        val stats = state.hero.stats
        val offense = when (state.hero.heroClass) {
            HeroClass.WARRIOR -> stats.strength
            HeroClass.ROGUE -> stats.dexterity
            HeroClass.RANGER -> safeAdd(stats.dexterity, stats.wisdom) / 2L
            HeroClass.MAGE -> stats.intelligence
            HeroClass.CLERIC -> stats.wisdom
            HeroClass.PALADIN -> safeAdd(stats.strength, stats.wisdom) / 2L
        }
        val equipmentPower = state.equipment.fold(0L) { total, item -> safeAdd(total, item.power) }
        return safeAdd(
            8L,
            safeAdd(
                safeMul(state.hero.level, 2L),
                safeAdd(safeMul(offense, 3L), equipmentPower / 3L),
            ),
        )
    }

    private fun scalePercent(value: Long, percent: Long): Long = safeAdd(
        safeMul(value / 100L, percent),
        safeMul(value % 100L, percent) / 100L,
    )

    private fun addTrophy(
        state: SimpleGameState,
        rng: StableRng,
        defeated: MonsterState,
    ): CombatLoot? {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return null
        val name = uniqueName(state.recentItemNames, RECENT_ITEMS, rng) {
            QuestMonsterCatalog.definition(defeated.catalogId)
                ?.trophyNames
                ?.pick(rng)
                ?: "${SimpleContent.lootMaterials.pick(rng)} ${SimpleContent.lootForms.pick(rng)}"
        }
        val rarity = trophyRarity(rng)
        addInventory(
            state,
            InventoryItem(
                id = safeIncrement(state.totalItemsFound),
                name = name,
                rarity = rarity,
                kind = "전리품",
                foundAtLevel = state.hero.level,
            ),
        )
        return CombatLoot(name = name, rarity = rarity, kind = "전리품")
    }

    private fun addEquipmentDrop(state: SimpleGameState, rng: StableRng): CombatLoot? {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return null
        val slot = EquipmentSlot.entries[rng.nextInt(EquipmentSlot.entries.size)]
        val candidate = equipmentCandidate(state, rng, slot, equipmentLootRarity(rng))
        val current = state.equipment.first { it.slot == slot }
        val droppedItemId = safeIncrement(state.totalItemsFound)
        val isUpgrade = candidate.power > current.power ||
            (candidate.power == current.power && rarityRank(candidate.rarity) > rarityRank(current.rarity))

        if (isUpgrade) {
            val replaced = InventoryItem(
                id = droppedItemId,
                name = current.name,
                rarity = current.rarity,
                kind = "장비",
                foundAtLevel = current.acquiredAtLevel,
                equipmentSlot = current.slot,
                equipmentPower = current.power,
            )
            current.name = candidate.name
            current.power = candidate.power
            current.rarity = candidate.rarity
            current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
            addInventory(state, replaced)
            state.totalLootEquipmentEquips = safeIncrement(state.totalLootEquipmentEquips)
            return CombatLoot(
                name = candidate.name,
                rarity = candidate.rarity,
                kind = "장비",
                equipped = true,
                equipmentSlot = slot,
                equipmentPower = candidate.power,
                previousPower = replaced.equipmentPower,
            )
        }

        addInventory(
            state,
            InventoryItem(
                id = droppedItemId,
                name = candidate.name,
                rarity = candidate.rarity,
                kind = "장비",
                foundAtLevel = state.hero.level,
                equipmentSlot = slot,
                equipmentPower = candidate.power,
            ),
        )
        return CombatLoot(
            name = candidate.name,
            rarity = candidate.rarity,
            kind = "장비",
            equipmentSlot = slot,
            equipmentPower = candidate.power,
            previousPower = current.power,
        )
    }

    private fun recordLootPresentation(state: SimpleGameState, loot: CombatLoot?) {
        clearLootPresentation(state)
        if (loot == null) {
            state.lastLootSummary = "가방이 가득 차 전리품을 더 담지 못했습니다"
            return
        }
        state.lastLootName = loot.name
        state.lastLootRarity = loot.rarity
        state.lastLootKind = loot.kind
        state.lastLootEquipped = loot.equipped
        state.lastLootEquipmentSlot = loot.equipmentSlot
        state.lastLootEquipmentPower = loot.equipmentPower
        state.lastLootPreviousPower = loot.previousPower
        state.lastLootSummary = if (loot.equipped) {
            "${loot.name} · 새 장비로 장착"
        } else {
            "${loot.name} · 가방에 보관"
        }
    }

    private fun clearLootPresentation(state: SimpleGameState) {
        state.lastLootSummary = ""
        state.lastLootName = ""
        state.lastLootRarity = ""
        state.lastLootKind = ""
        state.lastLootEquipped = false
        state.lastLootEquipmentSlot = null
        state.lastLootEquipmentPower = null
        state.lastLootPreviousPower = null
    }

    private fun equipmentCandidate(
        state: SimpleGameState,
        rng: StableRng,
        slot: EquipmentSlot,
        rarity: String,
        forShop: Boolean = false,
    ): EquipmentCandidate {
        val name = uniqueName(state.recentItemNames, RECENT_ITEMS, rng) {
            equipmentNameForRarity(
                coreName = "${SimpleContent.equipmentPrefixes(state.hero.level).pick(rng)} " +
                    SimpleContent.equipmentBase(
                        slot = slot,
                        level = state.hero.level,
                        heroClass = state.hero.heroClass,
                        variantIndex = rng.nextInt(ClassEquipmentCatalog.BASE_VARIANTS_PER_SLOT),
                    ),
                rarity = rarity,
            )
        }
        val powerRoll = rng.nextInt(12)
        val power = if (forShop) {
            shopEquipmentPowerForRoll(state.hero.level, rarity, powerRoll)
        } else {
            val rarityPower = when (rarity) {
                "신화" -> 30L
                "전설" -> 20L
                "영웅" -> 12L
                "희귀" -> 7L
                "고급" -> 3L
                else -> 0L
            }
            safeAdd(safeMul(state.hero.level, 4L), rarityPower + powerRoll)
        }
        return EquipmentCandidate(
            name = name,
            rarity = rarity,
            power = power,
        )
    }

    internal fun shopEquipmentPowerForRoll(level: Long, rarity: String, roll: Int): Long =
        safeAdd(
            expectedEquipmentCombatPower(level) - 1L,
            rarityRank(rarity).coerceIn(0, SHOP_MAX_RARITY_RANK).toLong() +
                roll.coerceIn(0, 11),
        ).coerceAtLeast(1L)

    internal fun equipmentNameForRarity(
        coreName: String,
        rarity: String,
    ): String {
        val enhancement = rarityRank(rarity)
        return if (enhancement == 0) {
            coreName
        } else {
            "$coreName +$enhancement"
        }
    }

    private fun normalizedEquipmentName(name: String, rarity: String): String {
        val enhancement = rarityRank(rarity)
        val coreName = name.substringBefore(" · ").replace(ENHANCEMENT_SUFFIX, "")
        return if (enhancement == 0) coreName else "$coreName +$enhancement"
    }

    private fun normalizedMonsterName(name: String): String = name.substringBefore(" · ").trim()

    private fun addInventory(state: SimpleGameState, item: InventoryItem) {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return
        state.totalItemsFound = safeIncrement(state.totalItemsFound)
        state.inventory.add(0, item)
    }

    private fun equipmentLootRarity(rng: StableRng): String =
        equipmentLootRarityForRoll(rng.nextInt(LOOT_RARITY_ROLL_BOUND))

    private fun trophyRarity(rng: StableRng): String =
        trophyRarityForRoll(rng.nextInt(100))

    private fun shopRarity(rng: StableRng): String =
        shopRarityForRoll(rng.nextInt(SHOP_RARITY_ROLL_BOUND))

    internal fun equipmentLootRarityForRoll(roll: Int): String {
        val bounded = roll.coerceIn(0, LOOT_RARITY_ROLL_BOUND - 1)
        return when {
            bounded < 100 -> "신화"
            bounded < 1_100 -> "전설"
            bounded < 51_100 -> "영웅"
            bounded < 191_100 -> "희귀"
            bounded < 491_100 -> "고급"
            else -> "일반"
        }
    }

    internal fun trophyRarityForRoll(roll: Int): String = when (roll.coerceIn(0, 99)) {
        in 0..6 -> "영웅"
        in 7..20 -> "희귀"
        in 21..50 -> "고급"
        else -> "일반"
    }

    internal fun shopRarityForRoll(roll: Int): String =
        when (roll.coerceIn(0, SHOP_RARITY_ROLL_BOUND - 1)) {
            0 -> "영웅"
            in 1..20 -> "희귀"
            in 21..249 -> "고급"
            else -> "일반"
        }

    internal fun equipmentDropPercent(monster: MonsterState): Int = when (monster.grade) {
        MonsterGrade.NORMAL -> 2
        MonsterGrade.ELITE -> 10
        MonsterGrade.BOSS -> if (monster.isFinalBoss) 100 else 50
    }

    private fun rarityRank(rarity: String): Int = when (rarity) {
        "신화" -> 5
        "전설" -> 4
        "영웅" -> 3
        "희귀" -> 2
        "고급" -> 1
        else -> 0
    }

    private fun uniqueName(
        recent: MutableList<String>,
        keep: Int,
        rng: StableRng,
        create: () -> String,
    ): String {
        var candidate = create()
        repeat(64) {
            if (candidate !in recent) {
                recent.add(0, candidate)
                while (recent.size > keep) recent.removeAt(recent.lastIndex)
                return candidate
            }
            candidate = create()
        }
        recent.add(0, candidate)
        while (recent.size > keep) recent.removeAt(recent.lastIndex)
        return candidate
    }

    private fun starterEquipment(heroClass: HeroClass): MutableList<EquippedItem> =
        EquipmentSlot.entries.map { slot ->
            EquippedItem(
                slot = slot,
                name = SimpleContent.equipmentBase(
                    slot = slot,
                    level = 1L,
                    heroClass = heroClass,
                    variantIndex = 0,
                ),
                power = 1L,
            )
        }.toMutableList()

    private data class EquipmentCandidate(
        val name: String,
        val rarity: String,
        val power: Long,
    )

    private fun <T> List<T>.pick(rng: StableRng): T = this[rng.nextInt(size)]

    private fun MutableList<EquipmentSlot>.replaceAllWithEquipmentSlots() {
        clear()
        addAll(EquipmentSlot.entries)
    }

    private fun safeIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private fun levelProgress(level: Long): Long = level.coerceAtLeast(1L) - 1L

    internal fun manaBaseAttribute(intelligence: Long, wisdom: Long): Long {
        val safeIntelligence = intelligence.coerceAtLeast(0L)
        val safeWisdom = wisdom.coerceAtLeast(0L)
        return safeAdd(
            safeAdd(safeIntelligence / 2L, safeWisdom / 2L),
            (safeIntelligence % 2L + safeWisdom % 2L) / 2L,
        )
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun safeMul(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun scalePercentRounded(value: Long, percent: Long): Long {
        val whole = safeMul(value / 100L, percent)
        val remainder = safeMul(value % 100L, percent)
        return safeAdd(whole, safeAdd(remainder, 50L) / 100L)
    }

    private fun normalizedStatCombatPower(
        weightedStatTenths: Long,
        expectedStatThirtieths: Long,
        equipmentBenchmark: Long,
    ): Long {
        if (
            weightedStatTenths <= 0L ||
            expectedStatThirtieths <= 0L ||
            equipmentBenchmark <= 0L
        ) {
            return 0L
        }
        // A 0.35 soft range keeps the two displayed contributions comparable during endless
        // epilogue growth without ever making a real primary/secondary stat gain reduce power.
        val statRatio = weightedStatTenths.toDouble() * 3.0 /
            expectedStatThirtieths.toDouble()
        val deviation = statRatio - 1.0
        val softenedMultiplier = 1.0 +
            deviation / (1.0 + kotlin.math.abs(deviation) / STAT_SOFT_CAP_RANGE)
        val scaled = equipmentBenchmark.toDouble() * softenedMultiplier
        return if (!scaled.isFinite() || scaled >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            scaled.roundToLong().coerceAtLeast(0L)
        }
    }

    private fun emptyDelta() = SettlementDelta(0L, 0L, 0L, 0L, 0L, 0L)

    private class StableRng(initialSeed: Long) {
        var state: Long = initialSeed.takeUnless { it == 0L } ?: DEFAULT_SEED
            private set

        fun nextInt(bound: Int): Int {
            require(bound > 0)
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 1) % bound.toLong()).toInt()
        }

        fun nextDouble(): Double {
            nextInt(Int.MAX_VALUE)
            return (state ushr 11).toDouble() / 9_007_199_254_740_992.0
        }
    }

    companion object {
        private const val PRIMARY_STAT_WEIGHT = 7L
        private const val SECONDARY_STAT_WEIGHT = 3L
        private const val PRIMARY_STAT_COUNT = 6
        private const val BASE_EXPECTED_STAT_THIRTIETHS = 315L
        private const val LEGACY_EXPECTED_STAT_GROWTH_THIRTIETHS = 9L
        private const val EXPECTED_STAT_GROWTH_THIRTIETHS = 20L
        private const val STAT_SOFT_CAP_RANGE = 0.35
        private const val BASE_EXPECTED_EQUIPMENT_POWER = 1L
        private const val EXPECTED_EQUIPMENT_POWER_PER_LEVEL = 5L
        const val ATTACK_PRESENTATION_MILLIS = 1_400L
        const val ENCOUNTER_SEARCH_MILLIS = 5_000L
        const val ENCOUNTER_DISCOVERY_MILLIS = 2_000L
        const val ENCOUNTER_REVEAL_MILLIS =
            ENCOUNTER_SEARCH_MILLIS + ENCOUNTER_DISCOVERY_MILLIS
        const val VICTORY_PRESENTATION_MILLIS = 1_600L
        const val LOOT_RESULT_MILLIS = 3_000L
        const val RETURN_TO_TOWN_MILLIS = 4_000L
        const val EQUIP_LOOT_MILLIS = 2_000L
        const val SELL_ITEM_MILLIS = 1_000L
        const val BUY_EQUIPMENT_MILLIS = 5_000L
        const val SHOP_OFFER_MILLIS = 3_000L
        const val SHOP_RESULT_MILLIS = BUY_EQUIPMENT_MILLIS - SHOP_OFFER_MILLIS
        const val DEPART_TO_FIELDS_MILLIS = 4_000L
        const val MONSTER_ENERGY_SCALE = 100L
        const val MAX_SKILLS = 20
        const val ACTS_PER_TALE = AdventureTaleCatalog.ACTS_PER_TALE
        const val OFFLINE_ADVENTURE_CHARGE_MILLIS = 10L * 60L * 1_000L
        const val OFFLINE_ADVENTURE_CAPACITY_MILLIS = 24L * 60L * 60L * 1_000L
        const val OFFLINE_ADVENTURE_EARN_RATE =
            OFFLINE_ADVENTURE_CAPACITY_MILLIS / OFFLINE_ADVENTURE_CHARGE_MILLIS
        private val ENHANCEMENT_SUFFIX = Regex(" \\+[1-5]$")
        private val LEGACY_MONSTER_EPITHET_IN_RESULT = Regex(" · [^·]+(?= 처치 · 경험치 \\+)")
        private const val CURRENT_SCHEMA_VERSION = SIMPLE_GAME_SCHEMA_VERSION
        private const val OFFLINE_ADVENTURE_SCHEMA_VERSION = 9
        private const val LEGACY_AUTO_HUNT_SCHEMA_VERSION = 8
        private const val ATTACK_SKILL_CATALOG_SCHEMA_VERSION = 7
        private const val CLASS_SKILL_CATALOG_SCHEMA_VERSION = 17
        private const val DISPLAY_NAME_SCHEMA_VERSION = 16
        private const val HUNTING_PACING_SCHEMA_VERSION = 21
        private const val EXPERIENCE_CURVE_SCHEMA_VERSION = 22
        private const val STORY_EXPANSION_SCHEMA_VERSION = 22
        private const val ACTIVE_COMBAT_REBASE_SCHEMA_VERSION = 22
        private const val SHOP_POWER_FIRST_SCHEMA_VERSION = 24
        private const val LEGACY_MAIN_TALE_COUNT = 12
        private const val MIN_COMBAT_DURATION_PERCENT = 60
        private const val MAX_COMBAT_DURATION_PERCENT = 140
        private const val SKILL_USE_PERCENT = 45
        private const val SHOP_MAX_RARITY_RANK = 3
        private const val SHOP_RARITY_ROLL_BOUND = 1_000
        private const val SALE_LEVELS_PER_STEP = 12L
        private const val MAX_SALE_LEVEL_MULTIPLIER = 4L
        private const val LOOT_RARITY_ROLL_BOUND = 1_000_000
        private const val RECENT_MONSTERS = 24
        private const val RECENT_ITEMS = 48
        private const val MAX_EPILOGUE_HISTORY = 60
        private const val DEFAULT_SEED = 0x5A17_2026_0811L
        private const val PRESENTATION_SEED_SALT = 0x4D59_5DF4_D0F3_3173L
        private const val TALE_SEED_SALT = 0x29A7_63D1_4B5E_8F21L
    }
}
