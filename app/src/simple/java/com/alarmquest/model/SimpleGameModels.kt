package com.alarmquest.model

import kotlinx.serialization.Serializable

const val SIMPLE_GAME_SCHEMA_VERSION = 42
const val BASE_INVENTORY_CAPACITY = 15L
private const val INVENTORY_LEVEL_BONUS_NUMERATOR = 3L
private const val INVENTORY_LEVEL_BONUS_DENOMINATOR = 5L
private const val INVENTORY_STRENGTH_PER_SLOT = 2L

@Serializable
enum class HeroClass(
    val labelKo: String,
    val primaryStatIndex: Int,
    val secondaryStatIndex: Int,
) {
    WARRIOR("파이터", primaryStatIndex = 0, secondaryStatIndex = 1),
    ROGUE("시프", primaryStatIndex = 2, secondaryStatIndex = 0),
    RANGER("레인져", primaryStatIndex = 2, secondaryStatIndex = 4),
    MAGE("메이지", primaryStatIndex = 3, secondaryStatIndex = 4),
    CLERIC("클래릭", primaryStatIndex = 4, secondaryStatIndex = 3),
    PALADIN("팔라딘", primaryStatIndex = 0, secondaryStatIndex = 5),
}

@Serializable
data class HeroStats(
    var strength: Long,
    var constitution: Long,
    var dexterity: Long,
    var intelligence: Long,
    var wisdom: Long,
    var charisma: Long,
    var maxHealth: Long,
    var maxMana: Long,
) {
    fun values(): List<Long> = listOf(
        strength,
        constitution,
        dexterity,
        intelligence,
        wisdom,
        charisma,
        maxHealth,
        maxMana,
    )

    fun increment(index: Int) {
        when (index) {
            0 -> strength = safeIncrement(strength)
            1 -> constitution = safeIncrement(constitution)
            2 -> dexterity = safeIncrement(dexterity)
            3 -> intelligence = safeIncrement(intelligence)
            4 -> wisdom = safeIncrement(wisdom)
            5 -> charisma = safeIncrement(charisma)
            6 -> maxHealth = safeIncrement(maxHealth)
            7 -> maxMana = safeIncrement(maxMana)
        }
    }

    fun copyMutable(): HeroStats = copy()

    private fun safeIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) value else value + 1L

    companion object {
        val labels = listOf("STR", "CON", "DEX", "INT", "WIS", "CHA", "HP MAX", "MP MAX")
    }
}

@Serializable
data class HeroState(
    var name: String,
    var heroClass: HeroClass,
    var level: Long = 1L,
    var experience: Long = 0L,
    var gold: Long = 0L,
    var stats: HeroStats,
)

@Serializable
enum class EquipmentSlot(val labelKo: String) {
    WEAPON("무기"),
    HEAD("머리"),
    BODY("몸"),
    HANDS("손"),
    FEET("발"),
    ACCESSORY("장신구"),
}

@Serializable
data class EquippedItem(
    val slot: EquipmentSlot,
    var name: String,
    var power: Long,
    var rarity: String = "일반",
    var acquiredAtLevel: Long = 1L,
)

@Serializable
data class ShopEquipmentOffer(
    val slot: EquipmentSlot,
    val name: String,
    val rarity: String,
    val previousPower: Long,
    val newPower: Long,
    val price: Long,
)

@Serializable
data class LearnedSkill(
    val id: Int,
    val name: String,
    val acquiredAtLevel: Long,
    val description: String,
    val catalogId: String = "",
    val usageCount: Long = 0L,
) {
    val boundedUsageCount: Long
        get() = usageCount.coerceIn(0L, MAX_USAGE_COUNT)

    val level: Long
        get() = boundedUsageCount / USES_PER_LEVEL + 1L

    val damageBonusPercent: Long
        get() = (level - 1L) * MAX_DAMAGE_BONUS_PERCENT / (MAX_LEVEL - 1L)

    val isMaxLevel: Boolean
        get() = level >= MAX_LEVEL

    val masteryExperience: Long
        get() = if (isMaxLevel) USES_PER_LEVEL else boundedUsageCount % USES_PER_LEVEL

    val masteryProgress: Float
        get() = masteryExperience.toFloat() / USES_PER_LEVEL.toFloat()

    val nextUsageCount: Long
        get() = (boundedUsageCount + 1L).coerceAtMost(MAX_USAGE_COUNT)

    val displayName: String
        get() = "$name LV.$level"

    companion object {
        const val USES_PER_LEVEL = 100L
        const val MAX_LEVEL = 100L
        const val MAX_DAMAGE_BONUS_PERCENT = 50L
        const val MAX_USAGE_COUNT = (MAX_LEVEL - 1L) * USES_PER_LEVEL
    }
}

@Serializable
data class InventoryItem(
    val id: Long,
    val name: String,
    val rarity: String,
    val kind: String,
    val foundAtLevel: Long,
    val equipmentSlot: EquipmentSlot? = null,
    val equipmentPower: Long? = null,
)

@Serializable
enum class TaleKind {
    PROLOGUE,
    MAIN,
    EPILOGUE,
    LABYRINTH,
}

@Serializable
data class TaleVariant(
    val minorEnemy: String,
)

@Serializable
data class TaleActState(
    val id: String,
    val number: Int,
    val title: String,
    val body: String,
    val completionBody: String,
    var progress: Long,
    val target: Long,
    val rewardExperience: Long,
    val rewardGold: Long,
    var completed: Boolean = false,
)

@Serializable
data class AdventureTaleState(
    val definitionId: String,
    val sequence: Long,
    val kind: TaleKind,
    val volumeNumber: Int,
    val volumeTitle: String,
    val chapterNumber: Int,
    val title: String,
    val subtitle: String,
    val opening: String,
    val ending: String,
    val nextHook: String,
    val variant: TaleVariant,
    val acts: MutableList<TaleActState>,
    var currentActIndex: Int = 0,
    val openingSlides: List<String> = emptyList(),
    val labyrinthDepth: Long = 0L,
) {
    fun activeAct(): TaleActState = acts[currentActIndex.coerceIn(0, acts.lastIndex)]
}

@Serializable
data class CompletedTaleRecord(
    val taleSequence: Long,
    val taleId: String,
    val kind: TaleKind,
    val volumeNumber: Int,
    val chapterNumber: Int,
    val title: String,
    val summary: String,
    val nextHook: String,
    val actMemories: List<String>,
    val completedAtLevel: Long,
    val labyrinthDepth: Long = 0L,
)

@Serializable
data class MonsterState(
    var id: Long,
    var name: String,
    var level: Long,
    var maxEnergy: Long,
    var grade: MonsterGrade = MonsterGrade.NORMAL,
    var currentEnergy: Long = maxEnergy,
    var expectedAttacks: Int = grade.minAttacks,
    var attacksCompleted: Int = 0,
    var catalogId: String = "",
    var baseName: String = "",
    var isFinalBoss: Boolean = false,
    var isLabyrinthGateBoss: Boolean = false,
)

@Serializable
enum class MonsterGrade(
    val labelKo: String,
    val minAttacks: Int,
    val maxAttacks: Int,
) {
    NORMAL("일반", 10, 10),
    ELITE("정예", 20, 20),
    BOSS("보스", 30, 30),
}

@Serializable
enum class CombatPhase {
    REVEAL,
    ATTACKING,
    VICTORY,
}

@Serializable
enum class AdventurePhase(val labelKo: String) {
    OPENING("서막"),
    COMBAT("전투"),
    LOOTING("아이템 획득"),
    RETURNING("귀환"),
    EQUIPPING("장비 선별"),
    SELLING("전리품 판매"),
    SHOPPING("장비 정비"),
    SHOPPING_RESULT("구매 결과"),
    SHOPPING_EMPTY("장비 검토"),
    DEPARTING("출정"),
}

@Serializable
data class SimpleGameState(
    var schemaVersion: Int = SIMPLE_GAME_SCHEMA_VERSION,
    var rankingCharacterId: String = "",
    var hero: HeroState,
    var equipment: MutableList<EquippedItem>,
    var skills: MutableList<LearnedSkill> = mutableListOf(),
    var consecutiveBasicAttacks: Int = 0,
    var inventory: MutableList<InventoryItem> = mutableListOf(),
    var adventureTale: AdventureTaleState,
    var monster: MonsterState,
    var totalKills: Long = 0L,
    var totalActs: Long = 0L,
    var totalTales: Long = 0L,
    var classGuidedLevelGrowths: Long = 0L,
    var totalItemsFound: Long = 0L,
    var actionSequence: Long = 0L,
    var actionStartedAt: Long,
    var actionEndsAt: Long,
    var lastSettledAt: Long,
    var rngState: Long,
    var presentationRngState: Long = 0L,
    var taleRngState: Long = 0L,
    var skillCatalogSeed: Long = 0L,
    var adventurePhase: AdventurePhase = AdventurePhase.COMBAT,
    var combatPhase: CombatPhase = CombatPhase.REVEAL,
    var lastAttackName: String = "전투 개시",
    var lastAttackType: String = "조우",
    var lastAttackWasSkill: Boolean = false,
    var lastSkillCatalogId: String = "",
    var lastActivatedSkillCatalogId: String = "",
    var lastDamage: Long = 0L,
    var lastMonsterEnergyBeforeAttack: Long = 0L,
    var lastResult: String = "모험을 준비하는 중",
    var totalReturns: Long = 0L,
    var totalItemsSold: Long = 0L,
    var totalEquipmentPurchases: Long = 0L,
    var totalLootEquipmentEquips: Long = 0L,
    var totalSaleGold: Long = 0L,
    var lastTownItemName: String = "",
    var lastTownItemRarity: String = "",
    var lastTownGold: Long = 0L,
    var pendingShopOffer: ShopEquipmentOffer? = null,
    var lastShopPurchase: ShopEquipmentOffer? = null,
    var shopAttemptedSlots: MutableList<EquipmentSlot> = mutableListOf(),
    var lastLootSummary: String = "",
    var lastLootName: String = "",
    var lastLootRarity: String = "",
    var lastLootKind: String = "",
    var lastLootEquipped: Boolean = false,
    var lastLootEquipmentSlot: EquipmentSlot? = null,
    var lastLootEquipmentPower: Long? = null,
    var lastLootPreviousPower: Long? = null,
    var recentMonsterNames: MutableList<String> = mutableListOf(),
    var recentItemNames: MutableList<String> = mutableListOf(),
    var offlineAdventureMillis: Long = 0L,
    var lastRewardRequestId: String = "",
    var completedTaleHistory: MutableList<CompletedTaleRecord> = mutableListOf(),
    var labyrinthDepthCompleted: Long = 0L,
) {
    /**
     * Strength still expands the bag, while a level-scaled baseline and capped strength bonus
     * keep same-level capacities strictly below a two-to-one spread.
     */
    fun inventoryCapacity(): Long {
        val strength = hero.stats.strength.coerceAtLeast(0L)
        val levelProgress = hero.level.coerceAtLeast(1L) - 1L
        val levelBonus = saturatingInventoryAdd(
            saturatingInventoryMultiply(
                levelProgress / INVENTORY_LEVEL_BONUS_DENOMINATOR,
                INVENTORY_LEVEL_BONUS_NUMERATOR,
            ),
            (levelProgress % INVENTORY_LEVEL_BONUS_DENOMINATOR) *
                INVENTORY_LEVEL_BONUS_NUMERATOR / INVENTORY_LEVEL_BONUS_DENOMINATOR,
        )
        val baseCapacity = saturatingInventoryAdd(BASE_INVENTORY_CAPACITY, levelBonus)
        val strengthBonus = (strength / INVENTORY_STRENGTH_PER_SLOT)
            .coerceAtMost((baseCapacity - 1L).coerceAtLeast(0L))
        return saturatingInventoryAdd(baseCapacity, strengthBonus)
    }
}

private fun saturatingInventoryAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun saturatingInventoryMultiply(left: Long, right: Long): Long =
    if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

data class StatRoll(
    val stats: HeroStats,
    val nextSeed: Long,
)

data class SettlementDelta(
    val elapsedMillis: Long,
    val defeatedMonsters: Long,
    val levelsGained: Long,
    val actsCompleted: Long,
    val talesCompleted: Long,
    val itemsFound: Long,
)
