package com.nullplaying.engine

import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.BattleEquipmentSlot
import com.nullplaying.model.BattleEquipmentSnapshot
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.UserInitiatedBattleRequest
import java.math.BigInteger

/**
 * Builds service-shaped Battle QA requests entirely from local state.
 *
 * The user's snapshot reflects the currently selected test character. Opponents come from this
 * fixed synthetic catalog and are scaled into the user's level, combat-power and rating band.
 * Nothing here reads from or writes to Supabase.
 */
internal object BattleQaMatchFactory {
    data class Match(
        val templateId: String,
        val request: UserInitiatedBattleRequest,
        /** Permanent power used for the fair matching band. */
        val userCombatPower: Long,
        val opponentCombatPower: Long,
        /** Combat powers consumed by the Arena adapter. Condition modifiers are retired. */
        val userEffectiveCombatPower: Long,
        val opponentEffectiveCombatPower: Long,
    )

    private data class VirtualSkill(
        val id: String,
        val name: String,
        val kind: BattleSkillKind,
        val powerBasisPoints: Int,
    )

    private data class VirtualEquipment(
        val id: String,
        val name: String,
        val slot: BattleEquipmentSlot,
        val rarity: String,
        val narrativeTag: String,
    )

    private data class VirtualOpponent(
        val id: String,
        val name: String,
        val heroClass: BattleHeroClass,
        val levelOffset: Int,
        val powerPermille: Int,
        val scoreOffset: Int,
        val guidance: BattleGuidance,
        val build: BattleBuildStats,
        val skills: List<VirtualSkill>,
        val equipment: List<VirtualEquipment>,
    )

    private val opponents = listOf(
        VirtualOpponent(
            id = "luen",
            name = "루엔",
            heroClass = BattleHeroClass.ROGUE,
            levelOffset = 0,
            powerPermille = 1_010,
            scoreOffset = 6,
            guidance = BattleGuidance.GUARD,
            build = BattleBuildStats(34, 30, 66, 24, 27, 38),
            skills = listOf(
                VirtualSkill("luen-shadow-chain", "그림자 연격", BattleSkillKind.PIERCE, 12_800),
                VirtualSkill("luen-fog-step", "안개 걸음", BattleSkillKind.CONTROL, 11_200),
                VirtualSkill("luen-moon-feint", "달빛 속임수", BattleSkillKind.CONTROL, 11_600),
                VirtualSkill("luen-silent-pierce", "무음 관통", BattleSkillKind.PIERCE, 12_300),
            ),
            equipment = listOf(
                VirtualEquipment("luen-blades", "월광 쌍검", BattleEquipmentSlot.WEAPON, "희귀", "차가운 잔광"),
                VirtualEquipment("luen-boots", "가속 경갑 장화", BattleEquipmentSlot.FEET, "고급", "순간 가속"),
            ),
        ),
        VirtualOpponent(
            id = "mira",
            name = "미라",
            heroClass = BattleHeroClass.RANGER,
            levelOffset = 1,
            powerPermille = 1_035,
            scoreOffset = 24,
            guidance = BattleGuidance.ASSAULT,
            build = BattleBuildStats(29, 31, 61, 25, 50, 28),
            skills = listOf(
                VirtualSkill("mira-orbit-shot", "궤도 사격", BattleSkillKind.PIERCE, 13_100),
                VirtualSkill("mira-wind-pin", "바람 쐐기", BattleSkillKind.CONTROL, 11_700),
                VirtualSkill("mira-hawk-volley", "매의 연사", BattleSkillKind.PIERCE, 12_400),
                VirtualSkill("mira-sky-turn", "하늘 선회", BattleSkillKind.CONTROL, 11_300),
            ),
            equipment = listOf(
                VirtualEquipment("mira-bow", "별매듭 장궁", BattleEquipmentSlot.WEAPON, "영웅", "휘어지는 사선"),
                VirtualEquipment("mira-gloves", "매사냥 장갑", BattleEquipmentSlot.HANDS, "희귀", "안정된 조준"),
            ),
        ),
        VirtualOpponent(
            id = "kain",
            name = "카인",
            heroClass = BattleHeroClass.WARRIOR,
            levelOffset = -1,
            powerPermille = 975,
            scoreOffset = -18,
            guidance = BattleGuidance.BALANCED,
            build = BattleBuildStats(64, 57, 34, 20, 28, 25),
            skills = listOf(
                VirtualSkill("kain-ice-cleave", "빙벽 가르기", BattleSkillKind.STRIKE, 12_600),
                VirtualSkill("kain-iron-counter", "철갑 반격", BattleSkillKind.CONTROL, 11_600),
                VirtualSkill("kain-frost-charge", "서리 돌진", BattleSkillKind.STRIKE, 12_200),
                VirtualSkill("kain-glacier-roar", "빙하의 포효", BattleSkillKind.CONTROL, 11_400),
            ),
            equipment = listOf(
                VirtualEquipment("kain-greatsword", "북풍 대검", BattleEquipmentSlot.WEAPON, "영웅", "묵직한 냉기"),
                VirtualEquipment("kain-mail", "설원 판금갑", BattleEquipmentSlot.BODY, "희귀", "충격 분산"),
            ),
        ),
        VirtualOpponent(
            id = "sera",
            name = "세라",
            heroClass = BattleHeroClass.MAGE,
            levelOffset = 2,
            powerPermille = 1_055,
            scoreOffset = 38,
            guidance = BattleGuidance.ASSAULT,
            build = BattleBuildStats(19, 28, 36, 68, 53, 35),
            skills = listOf(
                VirtualSkill("sera-eclipse-wave", "월식 파동", BattleSkillKind.ARCANE, 13_500),
                VirtualSkill("sera-gravity-knot", "중력 매듭", BattleSkillKind.CONTROL, 11_900),
                VirtualSkill("sera-star-fragment", "성운 파편", BattleSkillKind.ARCANE, 12_700),
                VirtualSkill("sera-night-orbit", "밤의 궤도", BattleSkillKind.CONTROL, 11_500),
            ),
            equipment = listOf(
                VirtualEquipment("sera-staff", "검은 달 지팡이", BattleEquipmentSlot.WEAPON, "영웅", "빛을 접는 파동"),
                VirtualEquipment("sera-ring", "초승 고리", BattleEquipmentSlot.ACCESSORY, "희귀", "마력 응축"),
            ),
        ),
        VirtualOpponent(
            id = "eve",
            name = "이브",
            heroClass = BattleHeroClass.CLERIC,
            levelOffset = 0,
            powerPermille = 995,
            scoreOffset = -4,
            guidance = BattleGuidance.GUARD,
            build = BattleBuildStats(32, 49, 27, 40, 65, 47),
            skills = listOf(
                VirtualSkill("eve-holy-ripple", "성광 파문", BattleSkillKind.ARCANE, 12_100),
                VirtualSkill("eve-calm-prayer", "고요한 기도", BattleSkillKind.RECOVER, 10_800),
                VirtualSkill("eve-silver-verdict", "은빛 심판", BattleSkillKind.ARCANE, 12_500),
                VirtualSkill("eve-sanctuary-bell", "성역의 종", BattleSkillKind.CONTROL, 11_300),
            ),
            equipment = listOf(
                VirtualEquipment("eve-mace", "은종 메이스", BattleEquipmentSlot.WEAPON, "희귀", "맑은 공명"),
                VirtualEquipment("eve-robe", "백은 예복", BattleEquipmentSlot.BODY, "고급", "빛의 완충"),
            ),
        ),
        VirtualOpponent(
            id = "arin",
            name = "아린",
            heroClass = BattleHeroClass.PALADIN,
            levelOffset = 1,
            powerPermille = 1_020,
            scoreOffset = 15,
            guidance = BattleGuidance.BALANCED,
            build = BattleBuildStats(57, 55, 28, 27, 46, 51),
            skills = listOf(
                VirtualSkill("arin-dawn-counter", "서광 반격", BattleSkillKind.STRIKE, 12_700),
                VirtualSkill("arin-oath-guard", "맹세의 수호", BattleSkillKind.RECOVER, 11_000),
                VirtualSkill("arin-radiant-wall", "찬란한 방벽", BattleSkillKind.CONTROL, 11_600),
                VirtualSkill("arin-daybreak-charge", "여명 돌진", BattleSkillKind.STRIKE, 12_300),
            ),
            equipment = listOf(
                VirtualEquipment("arin-blade", "새벽 방패검", BattleEquipmentSlot.WEAPON, "영웅", "금빛 검로"),
                VirtualEquipment("arin-shield", "맹세의 방패", BattleEquipmentSlot.BODY, "희귀", "정면 방어"),
            ),
        ),
        VirtualOpponent(
            id = "noa",
            name = "노아",
            heroClass = BattleHeroClass.ROGUE,
            levelOffset = -2,
            powerPermille = 950,
            scoreOffset = -35,
            guidance = BattleGuidance.ASSAULT,
            build = BattleBuildStats(38, 28, 69, 24, 29, 41),
            skills = listOf(
                VirtualSkill("noa-smoke-thrust", "연무 찌르기", BattleSkillKind.PIERCE, 12_900),
                VirtualSkill("noa-false-step", "거짓 발걸음", BattleSkillKind.CONTROL, 11_300),
                VirtualSkill("noa-ash-cross", "재의 교차", BattleSkillKind.PIERCE, 12_300),
                VirtualSkill("noa-haze-swap", "아지랑이 전환", BattleSkillKind.CONTROL, 11_500),
            ),
            equipment = listOf(
                VirtualEquipment("noa-daggers", "잿빛 단검", BattleEquipmentSlot.WEAPON, "희귀", "흐린 잔상"),
                VirtualEquipment("noa-cloak", "연무 외투", BattleEquipmentSlot.BODY, "고급", "윤곽 은폐"),
            ),
        ),
        VirtualOpponent(
            id = "raon",
            name = "라온",
            heroClass = BattleHeroClass.RANGER,
            levelOffset = 0,
            powerPermille = 985,
            scoreOffset = -9,
            guidance = BattleGuidance.BALANCED,
            build = BattleBuildStats(27, 35, 63, 26, 52, 32),
            skills = listOf(
                VirtualSkill("raon-blue-trail", "청람 궤적", BattleSkillKind.PIERCE, 12_500),
                VirtualSkill("raon-crosswind", "엇바람 사격", BattleSkillKind.CONTROL, 11_500),
                VirtualSkill("raon-rain-volley", "소나기 연사", BattleSkillKind.PIERCE, 12_600),
                VirtualSkill("raon-tailwind-step", "순풍 걸음", BattleSkillKind.CONTROL, 11_200),
            ),
            equipment = listOf(
                VirtualEquipment("raon-bow", "청람 장궁", BattleEquipmentSlot.WEAPON, "희귀", "푸른 바람결"),
                VirtualEquipment("raon-boots", "순풍 장화", BattleEquipmentSlot.FEET, "고급", "거리 유지"),
            ),
        ),
    )

    fun createMatch(
        state: SimpleGameState,
        combatPower: Long,
        guidance: BattleGuidance,
        userScore: Int,
        matchSequence: Int,
        battleId: String,
        serverSeed: Long,
        requestedAtMillis: Long,
    ): Match {
        val user = playerProjection(
            state = state,
            combatPower = combatPower,
            guidance = guidance,
            issuedAtMillis = requestedAtMillis,
            selectionSeed = serverSeed xor stableHash(battleId).toLong(),
        )
        val template = opponents[Math.floorMod(
            stableHash("${state.hero.name}|$userScore|${combatPower.coerceAtLeast(0L)}") + matchSequence,
            opponents.size,
        )]
        val opponentPower = scalePower(combatPower.coerceAtLeast(1L), template.powerPermille)
        val opponentScore = (userScore.toLong() + template.scoreOffset.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val opponent = BattleProjectionSnapshot(
            projectionId = "battle-qa-virtual-${template.id}-$matchSequence",
            displayName = template.name,
            heroClass = template.heroClass,
            level = (state.hero.level + template.levelOffset.toLong()).coerceAtLeast(1L),
            verifiedPower = opponentPower,
            condition = BattleCondition.NORMAL,
            build = template.build,
            guidance = template.guidance,
            skills = template.skills.map { skill ->
                BattleSkillSnapshot(
                    skillId = skill.id,
                    displayName = skill.name,
                    kind = skill.kind,
                    powerBasisPoints = skill.powerBasisPoints,
                    cooldownRounds = 1,
                    masteryLevel = (state.hero.level / 2L).coerceIn(0L, 100L).toInt(),
                    finisherEligible = skill.kind != BattleSkillKind.RECOVER,
                )
            },
            equipment = template.equipment.map { item ->
                BattleEquipmentSnapshot(
                    itemId = item.id,
                    displayName = item.name,
                    slot = item.slot,
                    rarity = item.rarity,
                    narrativeTag = item.narrativeTag,
                )
            },
            activeTraitIds = opponentTraitIds(template.heroClass),
            issuedAtMillis = requestedAtMillis,
        )
        return Match(
            templateId = template.id,
            request = UserInitiatedBattleRequest(
                battleId = battleId,
                serverSeed = serverSeed,
                requestedAtMillis = requestedAtMillis,
                user = user,
                opponent = opponent,
                opponentReferenceScore = opponentScore,
                rules = BattleRules(),
            ),
            userCombatPower = combatPower.coerceAtLeast(0L),
            opponentCombatPower = opponentPower,
            userEffectiveCombatPower = combatPower.coerceAtLeast(0L),
            opponentEffectiveCombatPower = opponentPower,
        )
    }

    private fun playerProjection(
        state: SimpleGameState,
        combatPower: Long,
        guidance: BattleGuidance,
        issuedAtMillis: Long,
        selectionSeed: Long,
    ): BattleProjectionSnapshot = BattleProjectionSnapshot(
        projectionId = state.rankingCharacterId.ifBlank { "battle-qa-local-character" },
        displayName = state.hero.name,
        heroClass = state.hero.heroClass.toBattleClass(),
        level = state.hero.level,
        verifiedPower = combatPower.coerceAtLeast(0L),
        condition = BattleCondition.NORMAL,
        build = BattleBuildStats(
            strength = state.hero.stats.strength,
            constitution = state.hero.stats.constitution,
            dexterity = state.hero.stats.dexterity,
            intelligence = state.hero.stats.intelligence,
            wisdom = state.hero.stats.wisdom,
            charisma = state.hero.stats.charisma,
        ),
        guidance = guidance,
        skills = selectRandomized(
            values = state.skills,
            count = BATTLE_PLAYER_SKILL_SLOTS,
            seed = selectionSeed,
            stableId = { skill -> "${skill.id}:${skill.catalogId}" },
        ).map(::playerSkill),
        equipment = state.equipment.take(6).map { item ->
            BattleEquipmentSnapshot(
                itemId = "local-${item.slot.name.lowercase()}",
                displayName = item.name,
                slot = item.slot.toBattleSlot(),
                rarity = item.rarity,
                narrativeTag = "${item.slot.labelKo} · ${item.rarity}",
            )
        },
        activeTraitIds = state.heroPath.activeTraitIds.ifEmpty {
            state.battleTraits.active.map { it.traitId }
        },
        heroPathTraitRanks = state.heroPath.traits.associate { progress ->
            progress.traitId to progress.effectiveRank
        },
        heroPathRevision = state.heroPath.revision,
        heroPathCatalogVersion = state.heroPath.catalogVersion,
        snapshotVersion = 2,
        issuedAtMillis = issuedAtMillis,
    )

    private fun opponentTraitIds(heroClass: BattleHeroClass): List<String> = when (heroClass) {
        BattleHeroClass.WARRIOR -> listOf("TRAIT_021", "TRAIT_013", "TRAIT_050")
        BattleHeroClass.ROGUE -> listOf("TRAIT_002", "TRAIT_017", "TRAIT_067")
        BattleHeroClass.RANGER -> listOf("TRAIT_005", "TRAIT_057", "TRAIT_049")
        BattleHeroClass.MAGE -> listOf("TRAIT_054", "TRAIT_069", "TRAIT_096")
        BattleHeroClass.CLERIC -> listOf("TRAIT_030", "TRAIT_056", "TRAIT_073")
        BattleHeroClass.PALADIN -> listOf("TRAIT_028", "TRAIT_078", "TRAIT_097")
    }

    private fun playerSkill(skill: LearnedSkill): BattleSkillSnapshot {
        val definition = SkillCatalog.find(skill.catalogId)
        val averageDamagePercent = definition?.let {
            (it.damagePercentMin.toLong() + it.damagePercentMax.toLong()) / 2L
        } ?: 110L
        return BattleSkillSnapshot(
            skillId = skill.catalogId.ifBlank { "local-skill-${skill.id}" },
            displayName = skill.name,
            kind = when (definition?.element) {
                SkillElement.FIRE,
                SkillElement.ICE,
                SkillElement.LIGHTNING,
                SkillElement.ARCANE,
                SkillElement.DARK,
                SkillElement.POISON,
                SkillElement.WIND,
                SkillElement.EARTH,
                SkillElement.HOLY,
                SkillElement.COSMIC,
                -> BattleSkillKind.ARCANE
                SkillElement.PHYSICAL,
                null,
                -> BattleSkillKind.STRIKE
            },
            powerBasisPoints = ((averageDamagePercent + skill.damageBonusPercent) * 100L)
                .coerceIn(8_000L, 20_000L)
                .toInt(),
            cooldownRounds = 2,
            masteryLevel = skill.level.coerceIn(0L, 100L).toInt(),
            finisherEligible = true,
        )
    }

    private fun <T> selectRandomized(
        values: List<T>,
        count: Int,
        seed: Long,
        stableId: (T) -> String,
    ): List<T> = values
        .distinctBy(stableId)
        .sortedBy { value -> selectionRank(seed, stableId(value)) }
        .take(count)

    private fun selectionRank(seed: Long, stableId: String): Long {
        var value = seed xor stableHash(stableId).toLong()
        value = (value xor (value ushr 33)) * -4_903_647_606_946_694_059L
        value = (value xor (value ushr 29)) * -4_267_526_408_917_041_441L
        return value xor (value ushr 32)
    }

    private fun HeroClass.toBattleClass(): BattleHeroClass = BattleHeroClass.valueOf(name)

    private fun EquipmentSlot.toBattleSlot(): BattleEquipmentSlot =
        BattleEquipmentSlot.valueOf(name)

    private fun scalePower(value: Long, permille: Int): Long {
        val scaled = BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(permille.toLong()))
            .divide(BigInteger.valueOf(1_000L))
        return scaled.coerceAtLeast(BigInteger.ONE)
            .coerceAtMost(BigInteger.valueOf(Long.MAX_VALUE))
            .toLong()
    }

    private fun stableHash(value: String): Int {
        var hash = 0x61C88647
        value.forEach { character -> hash = hash * 31 + character.code }
        return hash
    }

    private const val BATTLE_PLAYER_SKILL_SLOTS = 4
}
