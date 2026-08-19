package com.alarmquest.engine

import com.alarmquest.engine.SkillElement.*
import com.alarmquest.engine.SkillMotion.*
import com.alarmquest.model.HeroClass

internal enum class SkillElement(val labelKo: String) {
    PHYSICAL("물리"), FIRE("화염"), ICE("냉기"), LIGHTNING("번개"), ARCANE("비전"),
    DARK("암흑"), POISON("맹독"), WIND("바람"), EARTH("대지"), HOLY("신성"), COSMIC("우주"),
}

internal enum class SkillMotion {
    HEAVY_FALL, CLEAVE_HORIZONTAL, CROSS_SLASH, PIERCE_LINE, DASH_IMPACT,
    SPIN_CUT, RAPID_THREE, FRENZY_FIVE, PROJECTILE_SINGLE, PROJECTILE_VOLLEY,
    RAIN_VERTICAL, BEAM_CHANNEL, CHAIN_ARC, NOVA_RADIAL, PILLAR_DROP,
    ERUPTION_UP, TRAP_SNAP, SUMMON_DIVE, EXECUTE_PAUSE, GRAVITY_COLLAPSE,
}

internal enum class SkillTimingProfile { RAPID, EVEN, DELAYED_FINISH, ACCELERATE, DECELERATE }
internal enum class SkillFinisher { NONE, RING, FRACTURE, COLUMN, BURST, AFTERIMAGE }

internal data class SkillDefinition(
    val catalogId: String,
    val heroClass: HeroClass,
    val unlockLevel: Int,
    val candidate: Int,
    val name: String,
    val description: String,
    val damagePercent: Int,
    val hitWeights: List<Int>,
    val hitTimingsMillis: List<Int>,
    val element: SkillElement,
    val motion: SkillMotion,
    val timingProfile: SkillTimingProfile,
    val finisher: SkillFinisher,
    val effectVariant: Int,
    val intensityTier: Int,
) {
    val hitCount: Int get() = hitWeights.size
}

internal object SkillCatalog {
    private const val CANDIDATES_PER_TIER = 5
    private const val MAX_TIER = 20
    private const val CATALOG_SEED_SALT = 0x2F63_1A4D_7B29_5CE1L

    private val catalogNames: Map<HeroClass, List<List<String>>> by lazy(::createCatalogNames)
    val all: List<SkillDefinition> = HeroClass.entries.flatMap(::buildClassCatalog)
    private val byId = all.associateBy(SkillDefinition::catalogId)
    private val byClass = all.groupBy(SkillDefinition::heroClass)

    init {
        require(all.size == HeroClass.entries.size * MAX_TIER * CANDIDATES_PER_TIER)
        require(byId.size == all.size)
        HeroClass.entries.forEach { heroClass ->
            val definitions = byClass.getValue(heroClass)
            require(definitions.size == 100)
            require(definitions.map { it.name }.distinct().size == definitions.size)
            require((1..MAX_TIER).all { tier -> definitions.count { it.unlockLevel == tier * 5 } == 5 })
        }
        all.forEach { definition ->
            require(definition.hitWeights.size in 1..12)
            require(definition.hitWeights.all { it > 0 })
            require(definition.hitWeights.sum() == 100)
            require(definition.hitTimingsMillis.size == definition.hitCount)
            require(definition.hitTimingsMillis.zipWithNext().all { (a, b) -> a < b })
            require(definition.hitTimingsMillis.last() <= 900)
        }
    }

    fun find(catalogId: String): SkillDefinition? = byId[catalogId]

    fun forClass(heroClass: HeroClass): List<SkillDefinition> = byClass.getValue(heroClass)

    fun select(skillCatalogSeed: Long, heroClass: HeroClass, tier: Int): SkillDefinition {
        val safeTier = tier.coerceIn(1, MAX_TIER)
        val candidate = candidateIndex(skillCatalogSeed, heroClass, safeTier)
        return byClass.getValue(heroClass).first {
            it.unlockLevel == safeTier * 5 && it.candidate == candidate
        }
    }

    fun deriveSeed(seed: Long, heroClass: HeroClass): Long {
        val mixed = mix64(seed xor CATALOG_SEED_SALT xor heroClass.ordinal.toLong())
        return if (mixed == 0L) CATALOG_SEED_SALT else mixed
    }

    fun splitDamage(totalDamage: Long, weights: List<Int>): List<Long> {
        if (weights.size == 1) return listOf(totalDamage.coerceAtLeast(0L))
        val safeTotal = totalDamage.coerceAtLeast(0L)
        val result = ArrayList<Long>(weights.size)
        var assigned = 0L
        weights.dropLast(1).forEach { weight ->
            val damage = scalePercent(safeTotal, weight.toLong())
                .coerceAtMost(safeTotal - assigned)
            result += damage
            assigned += damage
        }
        result += safeTotal - assigned
        return result
    }

    private fun candidateIndex(seed: Long, heroClass: HeroClass, tier: Int): Int {
        val mixed = mix64(
            seed xor
                ((heroClass.ordinal + 1L) * 0x1F12_3BB5_9A77_4D21L) xor
                (tier.toLong() * 0x0D6E_8FEB_8665_9FD9L),
        )
        return ((mixed ushr 1) % CANDIDATES_PER_TIER.toLong()).toInt()
    }

    private fun buildClassCatalog(heroClass: HeroClass): List<SkillDefinition> {
        val rows = catalogNames.getValue(heroClass)
        require(rows.size == MAX_TIER)
        return rows.flatMapIndexed { tierIndex, row ->
            require(row.size == CANDIDATES_PER_TIER)
            row.mapIndexed { candidate, name ->
                val tier = tierIndex + 1
                val hitCount = if (heroClass == HeroClass.WARRIOR) {
                    warriorHitCount(tier, candidate)
                } else {
                    explicitHitCount(name) ?: hitCount(heroClass, candidate, tier, name)
                }
                val timingProfile = SkillTimingProfile.entries[(tier + candidate) % SkillTimingProfile.entries.size]
                val element = elementFor(heroClass, candidate, tier, name)
                val motion = motionFor(heroClass, candidate, tier, name)
                SkillDefinition(
                    catalogId = "${heroClass.name.lowercase()}_t${tier.toString().padStart(2, '0')}_c${(candidate + 1).toString().padStart(2, '0')}",
                    heroClass = heroClass,
                    unlockLevel = tier * 5,
                    candidate = candidate,
                    name = name,
                    description = if (hitCount == 1) {
                        "$name 기술로 적을 강하게 공격한다."
                    } else {
                        "$name 기술로 적을 ${hitCount}회 연속 공격한다."
                    },
                    damagePercent = 120 + (tier - 1) * 3,
                    hitWeights = weightsFor(hitCount),
                    hitTimingsMillis = timingsFor(
                        hitCount = hitCount,
                        profile = timingProfile,
                        singleImpactMillis = singleImpactMillisFor(heroClass, candidate),
                    ),
                    element = element,
                    motion = motion,
                    timingProfile = timingProfile,
                    finisher = finisherFor(element, motion, tier, candidate),
                    effectVariant = (tier + candidate) % 4,
                    intensityTier = ((tier - 1) / 4 + 1).coerceIn(1, 5),
                )
            }
        }
    }

    private fun singleImpactMillisFor(heroClass: HeroClass, candidate: Int): Int {
        val safeCandidate = candidate.coerceIn(0, 4)
        return when (heroClass) {
            HeroClass.WARRIOR -> 420
            HeroClass.ROGUE -> intArrayOf(340, 500, 440, 520, 580)[safeCandidate]
            HeroClass.RANGER -> intArrayOf(500, 340, 420, 500, 560)[safeCandidate]
            HeroClass.MAGE -> intArrayOf(440, 520, 300, 580, 600)[safeCandidate]
            HeroClass.CLERIC -> intArrayOf(480, 560, 520, 450, 580)[safeCandidate]
            HeroClass.PALADIN -> intArrayOf(420, 440, 560, 480, 600)[safeCandidate]
        }
    }

    private fun hitCount(heroClass: HeroClass, candidate: Int, tier: Int, name: String): Int {
        val range = when (heroClass) {
            HeroClass.WARRIOR -> error("Warrior uses catalog hit counts")
            HeroClass.ROGUE -> listOf(2..5, 1..3, 2..4, 2..4, 1..1)
            HeroClass.RANGER -> listOf(1..1, 3..5, 2..4, 2..3, 1..3)
            HeroClass.MAGE -> listOf(1..3, 1..4, 2..5, 1..3, 1..5)
            HeroClass.CLERIC -> listOf(1..3, 1..1, 2..4, 1..3, 2..5)
            HeroClass.PALADIN -> listOf(1..3, 1..2, 1..1, 2..4, 1..5)
        }[candidate]
        return range.first + ((tier + candidate) % (range.last - range.first + 1))
    }

    /** Exact number words are a player-facing promise and always beat candidate defaults. */
    private fun explicitHitCount(name: String): Int? = when {
        name.contains("일격") || name == "만악 종결광" -> 1
        name == "비수 난무" -> 4
        name == "차원 연쇄" || name == "성광 검무" -> 3
        name == "성검 회오리" || name == "대천사 성검" -> 3
        name.contains("십이연") -> 12
        name.containsAny("백련", "무한 참격") -> 10
        name.contains("일곱") || name.contains("칠연") -> 7
        name.contains("오연") -> 5
        name.containsAny("네 갈래", "사방") -> 4
        name.containsAny("세 갈래", "삼연", "삼중") -> 3
        name.containsAny("쌍침", "십자참", "십자 베기") -> 2
        else -> null
    }

    /** Warrior hit choreography is stable catalog data, never a side effect of display wording. */
    private fun warriorHitCount(tier: Int, candidate: Int): Int {
        if (candidate != 4) return 1
        return intArrayOf(3, 3, 5, 5, 5, 6, 4, 7, 6, 7, 7, 7, 6, 10, 8, 12, 9, 8, 10, 12)[tier - 1]
    }

    private fun weightsFor(hitCount: Int): List<Int> = when (hitCount) {
        1 -> listOf(100)
        2 -> listOf(48, 52)
        3 -> listOf(28, 32, 40)
        4 -> listOf(20, 22, 25, 33)
        5 -> listOf(15, 17, 18, 20, 30)
        else -> {
            val weights = MutableList(hitCount) { 100 / hitCount }
            repeat(100 - weights.sum()) { offset ->
                weights[hitCount - 1 - offset.mod(hitCount)] += 1
            }
            val finisherBoost = minOf(12, (hitCount - 1) * (weights.first() - 1))
            repeat(finisherBoost) { offset ->
                weights[offset.mod(hitCount - 1)] -= 1
                weights[hitCount - 1] += 1
            }
            weights
        }
    }

    private fun timingsFor(
        hitCount: Int,
        profile: SkillTimingProfile,
        singleImpactMillis: Int = 420,
    ): List<Int> = when (hitCount) {
        1 -> listOf(singleImpactMillis)
        2 -> when (profile) {
            SkillTimingProfile.RAPID -> listOf(180, 500)
            SkillTimingProfile.EVEN -> listOf(240, 680)
            SkillTimingProfile.DELAYED_FINISH -> listOf(220, 780)
            SkillTimingProfile.ACCELERATE -> listOf(330, 650)
            SkillTimingProfile.DECELERATE -> listOf(170, 690)
        }
        3 -> when (profile) {
            SkillTimingProfile.RAPID -> listOf(140, 300, 520)
            SkillTimingProfile.EVEN -> listOf(180, 430, 720)
            SkillTimingProfile.DELAYED_FINISH -> listOf(170, 360, 790)
            SkillTimingProfile.ACCELERATE -> listOf(260, 470, 650)
            SkillTimingProfile.DECELERATE -> listOf(140, 420, 780)
        }
        4 -> when (profile) {
            SkillTimingProfile.RAPID -> listOf(120, 250, 390, 580)
            SkillTimingProfile.EVEN -> listOf(140, 320, 520, 760)
            SkillTimingProfile.DELAYED_FINISH -> listOf(130, 280, 450, 810)
            SkillTimingProfile.ACCELERATE -> listOf(260, 450, 600, 720)
            SkillTimingProfile.DECELERATE -> listOf(110, 310, 550, 800)
        }
        5 -> when (profile) {
            SkillTimingProfile.RAPID -> listOf(100, 210, 330, 460, 620)
            SkillTimingProfile.EVEN -> listOf(110, 250, 400, 570, 780)
            SkillTimingProfile.DELAYED_FINISH -> listOf(100, 220, 350, 500, 820)
            SkillTimingProfile.ACCELERATE -> listOf(260, 430, 560, 660, 740)
            SkillTimingProfile.DECELERATE -> listOf(90, 250, 450, 650, 820)
        }
        else -> extendedTimingsFor(hitCount, profile)
    }

    private fun extendedTimingsFor(hitCount: Int, profile: SkillTimingProfile): List<Int> {
        val start = when (profile) {
            SkillTimingProfile.RAPID -> 80
            SkillTimingProfile.EVEN -> 100
            SkillTimingProfile.DELAYED_FINISH -> 90
            SkillTimingProfile.ACCELERATE -> 150
            SkillTimingProfile.DECELERATE -> 70
        }
        val end = when (profile) {
            SkillTimingProfile.RAPID -> 710
            SkillTimingProfile.EVEN -> 770
            SkillTimingProfile.DELAYED_FINISH -> 790
            SkillTimingProfile.ACCELERATE -> 750
            SkillTimingProfile.DECELERATE -> 780
        }
        var previous = start - 1
        return List(hitCount) { index ->
            val progress = index.toDouble() / (hitCount - 1).toDouble()
            val curved = when (profile) {
                SkillTimingProfile.RAPID -> progress
                SkillTimingProfile.EVEN -> progress
                SkillTimingProfile.DELAYED_FINISH -> if (index == hitCount - 1) 1.0 else progress * 0.72
                SkillTimingProfile.ACCELERATE -> 1.0 - Math.pow(1.0 - progress, 1.45)
                SkillTimingProfile.DECELERATE -> Math.pow(progress, 1.45)
            }
            val timing = (start + (end - start) * curved).toInt().coerceAtLeast(previous + 34)
            previous = timing
            timing
        }
    }

    private fun motionFor(heroClass: HeroClass, candidate: Int, tier: Int, name: String): SkillMotion {
        if (heroClass == HeroClass.WARRIOR) return warriorMotion(candidate, tier)
        val semanticMotion = when {
            heroClass == HeroClass.RANGER &&
                name.containsAny("조준", "저격", "필중", "일점", "한 발") -> PROJECTILE_SINGLE
            heroClass in setOf(HeroClass.RANGER, HeroClass.MAGE, HeroClass.CLERIC) &&
                name.containsAny("연사", "난사", "연탄", "광선", "포화") -> PROJECTILE_VOLLEY
            heroClass == HeroClass.MAGE && name.contains("천둥 종말") -> RAIN_VERTICAL
            heroClass == HeroClass.MAGE && name.contains("초신성") -> NOVA_RADIAL
            heroClass == HeroClass.CLERIC && name == "만악 종결광" -> BEAM_CHANNEL
            heroClass == HeroClass.CLERIC && name == "영혼왕의 일격" -> SUMMON_DIVE
            heroClass == HeroClass.PALADIN && name == "왕권 일격" -> EXECUTE_PAUSE
            heroClass == HeroClass.PALADIN && name.contains("서약") && name.containsAny("검", "참") -> CLEAVE_HORIZONTAL
            name.containsAny("난무", "연격", "연참", "광란", "백련", "참무") -> FRENZY_FIVE
            name.containsAny("낙하", "내려치기", "강하", "천벌") -> PILLAR_DROP
            name.containsAny("폭발", "폭풍", "파동", "진동") -> NOVA_RADIAL
            name.containsAny("광선", "빔", "섬광", "광주") -> BEAM_CHANNEL
            name.containsAny("연쇄", "사슬") -> CHAIN_ARC
            name.containsAny("화살비", "유성우", "별비", "비검") -> RAIN_VERTICAL
            name.containsAny("회전", "소용돌이", "회오리", "윤무") -> SPIN_CUT
            name.containsAny("돌진", "돌파", "쇄도", "질주") -> DASH_IMPACT
            name.containsAny("속사", "연사") -> RAPID_THREE
            name.containsAny("화살", "사격", "탄환") -> PROJECTILE_SINGLE
            name.containsAny("관통", "찌르기", "창격") -> PIERCE_LINE
            name.containsAny("교차", "쌍검") -> CROSS_SLASH
            name.containsAny("가르기", "베기", "참격") -> CLEAVE_HORIZONTAL
            name.containsAny("처형", "종결", "단두", "마침표") -> EXECUTE_PAUSE
            name.containsAny("덫", "함정", "포획") -> TRAP_SNAP
            else -> null
        }
        if (semanticMotion != null) return semanticMotion
        val options = when (heroClass) {
            HeroClass.WARRIOR -> listOf(
                CLEAVE_HORIZONTAL to CROSS_SLASH,
                HEAVY_FALL to PILLAR_DROP,
                DASH_IMPACT to PIERCE_LINE,
                ERUPTION_UP to NOVA_RADIAL,
                RAPID_THREE to FRENZY_FIVE,
            )
            HeroClass.ROGUE -> listOf(
                CROSS_SLASH to FRENZY_FIVE,
                DASH_IMPACT to GRAVITY_COLLAPSE,
                PIERCE_LINE to RAPID_THREE,
                TRAP_SNAP to CROSS_SLASH,
                EXECUTE_PAUSE to PIERCE_LINE,
            )
            HeroClass.RANGER -> listOf(
                PROJECTILE_SINGLE to PIERCE_LINE,
                PROJECTILE_VOLLEY to RAIN_VERTICAL,
                PROJECTILE_VOLLEY to SPIN_CUT,
                TRAP_SNAP to SUMMON_DIVE,
                RAIN_VERTICAL to PILLAR_DROP,
            )
            HeroClass.MAGE -> listOf(
                PROJECTILE_SINGLE to ERUPTION_UP,
                PIERCE_LINE to NOVA_RADIAL,
                CHAIN_ARC to PILLAR_DROP,
                BEAM_CHANNEL to GRAVITY_COLLAPSE,
                RAIN_VERTICAL to GRAVITY_COLLAPSE,
            )
            HeroClass.CLERIC -> listOf(
                BEAM_CHANNEL to PILLAR_DROP,
                EXECUTE_PAUSE to PILLAR_DROP,
                CHAIN_ARC to TRAP_SNAP,
                NOVA_RADIAL to ERUPTION_UP,
                SUMMON_DIVE to RAIN_VERTICAL,
            )
            HeroClass.PALADIN -> listOf(
                CLEAVE_HORIZONTAL to CROSS_SLASH,
                DASH_IMPACT to NOVA_RADIAL,
                HEAVY_FALL to PILLAR_DROP,
                BEAM_CHANNEL to NOVA_RADIAL,
                EXECUTE_PAUSE to SUMMON_DIVE,
            )
        }[candidate]
        return if (tier % 2 == 0) options.second else options.first
    }

    private fun warriorMotion(candidate: Int, tier: Int): SkillMotion = when (candidate) {
        0 -> if (tier == 6 || tier == 9) SPIN_CUT else CLEAVE_HORIZONTAL
        1 -> if (tier % 2 == 0 || tier == 1 || tier == 5) PILLAR_DROP else HEAVY_FALL
        2 -> DASH_IMPACT
        3 -> when (tier) {
            4, 7, 10 -> ERUPTION_UP
            12, 15, 16, 18, 19 -> GRAVITY_COLLAPSE
            else -> NOVA_RADIAL
        }
        4 -> FRENZY_FIVE
        else -> error("Unknown warrior candidate $candidate")
    }

    private fun elementFor(heroClass: HeroClass, candidate: Int, tier: Int, name: String): SkillElement {
        if (heroClass == HeroClass.WARRIOR) {
            return listOf(PHYSICAL, EARTH, PHYSICAL, EARTH, PHYSICAL)[candidate]
        }
        val semanticElement = when {
            name.contains("초신성") -> COSMIC
            name.containsAny("유성", "혜성") -> COSMIC
            name.containsAny("독", "맹독", "독니", "독가시") -> POISON
            name.containsAny("번개", "천둥", "뇌격", "전격", "전광", "낙뢰", "벼락") -> LIGHTNING
            name.containsAny("얼음", "빙", "서리", "냉기", "설원") -> ICE
            name.containsAny("화염", "불꽃", "불길", "화산", "용암", "홍련", "업화", "작열", "폭염") -> FIRE
            name.containsAny("암흑", "칠흑", "그림자", "밤의", "심연", "월식", "혈월") -> DARK
            name.containsAny("바람", "폭풍", "질풍", "열풍", "회오리", "돌풍") -> WIND
            name.containsAny("대지", "암석", "바위", "지진", "산맥", "거신") -> EARTH
            name.containsAny("별", "성운", "우주", "혜성", "중력", "은하", "천체", "달빛", "월광") -> COSMIC
            name.containsAny("비전", "마력", "룬", "마법") -> ARCANE
            name.containsAny("신성", "성광", "찬란", "여명", "천상", "성휘", "심판", "성역", "축복") -> HOLY
            else -> null
        }
        val base = semanticElement ?: when (heroClass) {
            HeroClass.WARRIOR -> listOf(PHYSICAL, EARTH, PHYSICAL, EARTH, PHYSICAL)[candidate]
            HeroClass.ROGUE -> listOf(PHYSICAL, DARK, POISON, PHYSICAL, DARK)[candidate]
            HeroClass.RANGER -> listOf(PHYSICAL, WIND, WIND, EARTH, COSMIC)[candidate]
            HeroClass.MAGE -> listOf(FIRE, ICE, LIGHTNING, ARCANE, COSMIC)[candidate]
            HeroClass.CLERIC -> listOf(HOLY, HOLY, ARCANE, FIRE, HOLY)[candidate]
            HeroClass.PALADIN -> listOf(HOLY, HOLY, EARTH, HOLY, HOLY)[candidate]
        }
        return base
    }

    private fun finisherFor(
        element: SkillElement,
        motion: SkillMotion,
        tier: Int,
        candidate: Int,
    ): SkillFinisher {
        // Finishers are deliberately staged by unlock level: early skills teach the
        // core silhouette, while later tiers add the large secondary payoff.
        if (tier <= 4) return SkillFinisher.NONE
        val primary = when {
            motion in setOf(CLEAVE_HORIZONTAL, CROSS_SLASH, RAPID_THREE, FRENZY_FIVE, DASH_IMPACT) ->
                SkillFinisher.AFTERIMAGE
            motion in setOf(HEAVY_FALL, PILLAR_DROP, GRAVITY_COLLAPSE) || element in setOf(EARTH, ICE) ->
                SkillFinisher.FRACTURE
            motion in setOf(PROJECTILE_SINGLE, PROJECTILE_VOLLEY, TRAP_SNAP, ERUPTION_UP) ||
                element in setOf(FIRE, POISON) -> SkillFinisher.BURST
            motion in setOf(NOVA_RADIAL, SPIN_CUT) || element in setOf(WIND, ARCANE, COSMIC) ->
                SkillFinisher.RING
            motion in setOf(BEAM_CHANNEL, CHAIN_ARC, RAIN_VERTICAL, SUMMON_DIVE) ||
                element in setOf(HOLY, LIGHTNING) -> SkillFinisher.COLUMN
            motion == EXECUTE_PAUSE || motion == PIERCE_LINE -> SkillFinisher.AFTERIMAGE
            else -> SkillFinisher.NONE
        }
        return if (tier <= 8 && (tier + candidate) % 2 == 0) SkillFinisher.NONE else primary
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { contains(it) }

    private fun scalePercent(value: Long, percent: Long): Long {
        val whole = value / 100L
        val remainder = value % 100L
        val wholeScaled = saturatingMultiply(whole, percent)
        val remainderScaled = saturatingMultiply(remainder, percent) / 100L
        return saturatingAdd(wholeScaled, remainderScaled)
    }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun saturatingMultiply(a: Long, b: Long): Long = when {
        a == 0L || b == 0L -> 0L
        a > Long.MAX_VALUE / b -> Long.MAX_VALUE
        else -> a * b
    }

    private fun mix64(input: Long): Long {
        var z = input + -0x61C8864680B583EBL
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }

    private fun rows(value: String): List<List<String>> = value.trimIndent()
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line -> line.split('|').map(String::trim) }
        .toList()

    private fun createCatalogNames(): Map<HeroClass, List<List<String>>> = mapOf(
        HeroClass.WARRIOR to rows(
            """
            칼날 베기|완력 내려찍기|전열 돌진|지면 발구르기|거친 연속참
            반월 가르기|갑주 깨부수기|방패 돌파|땅울림 강타|강철 연격
            전열 베기|철퇴 강타|맹진 찌르기|암반 쪼개기|맹수 난격
            강철 양단|투구 박살|공성 돌격|바위기둥 솟구침|전장 난무
            대검 절단|전투도끼 낙하|철쐐기 돌파|지반 내려찍기|광전 난격
            대검 회전참|거인철퇴|중갑 돌진|단층 깨뜨리기|광폭 연참
            맹호 참격|골절 대강타|돌격 분쇄|대지 뒤엎기|혈전 연격
            용맹 대절단|성문 깨기|전차 충돌|지축 파쇄타|백전 난격
            철갑 회전참|공성 대강타|불굴 진격|균열 폭쇄|광란 대난격
            성벽 양단|요새 분쇄|선봉 쇄도|지층 솟구침|철혈 연참
            검호의 대참|파성 철퇴|불패 돌파|협곡 균열타|불굴 난무
            거인 가르기|거구 분쇄|군왕 진군|산맥 붕괴타|전쟁광 연격
            전쟁군주 참격|용골 대파쇄|철혈 관통|전장 지반격파|폭군 난격
            대장군 대절단|절벽 깨부수기|군단 대돌파|지각 대폭쇄|군왕 연참
            군왕의 일도|군왕 강타|군왕 돌진|거산 함몰타|패왕 대난무
            패왕 양단|패왕 대강타|성채 대돌파|철옹성 지반붕쇄|용살 연속참
            용살 대참|태산 대붕괴|용살 쇄도|산하 대파쇄|전쟁왕 광란
            전쟁왕 대참격|전쟁왕 대철퇴|패왕 대진군|만산 대붕괴|천하 난무
            만군 대양단|대륙 파쇄타|불퇴 대돌파|대륙 대붕쇄|만군 대참무
            천하대양단|무쌍 대분쇄|천하무패 돌격|천하붕쇄|멸군광란
            """,
        ),
        HeroClass.ROGUE to rows(
            """
            빠른 찌르기|그림자 베기|독니 찌르기|발목 덫|급소 베기
            쌍아 연격|암습|녹독 파열|철사 절단|숨통 끊기
            삼연 찌르기|잔영 습격|맹독 쌍침|올가미 강타|심장 찌르기
            비수 난무|어둠 도약|독안개 폭침|칼날 덫|무음 처형
            초승달 단검|그림자 교차|독액 분사|은사 포박|붉은 급소
            질풍 쌍검|야행 습격|부식 파열|회전 올가미|사각 일격
            네 갈래 비수|흑영 베기|사독 연침|사슬 덫|치명 절단
            유령 난도|잔상 도약|독사 송곳니|강철 실선|영혼 찌르기
            핏빛 쌍아|월하 암습|녹독 폭발|처형의 올가미|한밤의 급소
            폭풍 비수|그림자 분신참|왕독 연쇄|칼날 감옥|절명 일격
            환영 연격|심연 도약|검은독 파열|은사 난무|목숨 베기
            백야 쌍검|무형 습격|용독 쌍침|사슬 절단진|침묵의 처형
            악몽 비수|칠흑 교차참|독왕의 송곳니|그림자 덫|운명 절단
            천공 단검무|공허 도약|심연독 폭침|천라지망|사신의 급소
            별빛 쌍아|밤의 군무|맹독성 폭발|월광 철사|영혼 처형
            무한 비수|그림자 왕의 습격|재앙독 연쇄|검은 실 감옥|존재 절단
            신살 쌍검|심연 분신참|세계수 독침|운명 포박|왕의 숨통
            종말의 단검무|무월 암습|종언독 파열|사신의 올가미|절대 급소
            천야백련|그림자 세계 절단|만독 관통|인과 절단선|찰나 처형
            무영천살|공허의 마지막 춤|독신 폭살|천망 종결|죽음의 한 점
            """,
        ),
        HeroClass.RANGER to rows(
            """
            정조준 사격|세 갈래 화살|산들 화살|가시 덫|달빛 화살
            관통 사격|연속 사격|돌풍 시위|늑대 엄니|별가루 사격
            매눈 관통|오연사|바람 가르기|올가미 화살|초승달 관통
            약점 관통|부채꼴 사격|회오리 화살|맹수 덫|별빛 연사
            사냥꾼의 일점|유성 화살비|질풍 관통|독가시 덫|월광 저격
            장거리 저격|매의 깃 연사|폭풍 시위|곰발톱 강타|혜성 화살
            철갑 관통|일곱 화살|청풍 난사|사슬 올가미|은하 사격
            심장 조준|비익 연사|회오리 관통|송곳니 덫|보름달 일격
            추적자의 화살|폭우 사격|폭풍 화살|매의 급강하|별무리 연사
            왕가의 저격|천공 화살비|태풍 시위|야수의 협공|유성 관통
            무음 관통|백발백중 연사|질풍 폭발|거대 가시 덫|월식 사격
            천리 저격|군집 화살|폭풍왕의 화살|그리핀 급습|성좌의 화살
            용안 조준|빛살 난무|천공 회오리|와이번 발톱|은하수 관통
            필중의 일점|만화살 폭우|대기의 칼날|고대 야수 덫|별의 추적자
            황금 매 저격|유성우 연사|폭풍신의 시위|왕의 사냥개|만월 파열
            세계수 관통|무한 화살진|하늘 가르기|신수의 엄니|성운 폭격
            용살 저격|천익 난사|창공 붕괴|별짐승 급습|별자리 관통
            운명 조준|종말의 화살비|태풍 종결|신화의 덫|월신의 일격
            천안 필중|백만 화살|세계풍 관통|사냥신 강습|은하 종단
            지평선의 한 발|무한성우|창세의 바람|야생의 종언|별을 꿰는 화살
            """,
        ),
        HeroClass.MAGE to rows(
            """
            불씨 화살|서리 파편|전격|마력탄|별가루 폭발
            화염구|얼음 창|연쇄 번개|비전 파동|유성 조각
            불꽃 고리|동결 파열|삼중 낙뢰|마력 칼날|달빛 폭발
            화염 폭발|서리 연창|뇌광 구체|비전 충격|작은 유성우
            용암 분출|얼음 송곳비|번개 사슬|마력 회오리|성운 파동
            불기둥|빙결 창벽|폭뢰|공간 절단|혜성 낙하
            홍염 연폭|서리 폭풍|천둥 구체|비전 연탄|별무리 폭격
            화염 소용돌이|빙하 파열|사방 낙뢰|차원 칼날|월식 파동
            불새 강하|눈보라|뇌전 폭발|마력 붕괴|유성우
            용의 화염|영구빙창|폭풍 번개|공간 왜곡파|성좌 폭발
            지옥불 구체|빙산 낙하|청뢰 연쇄|비전 폭풍|혜성 충돌
            태양 화염|절대영도 파열|천뢰|차원 붕괴|은하 파동
            홍련 폭발|서리왕의 창|뇌신의 사슬|마력 핵폭발|별바다 폭격
            용암해일|빙하 폭풍|만뢰|공허 칼날|초신성 파편
            불사조 격돌|영겁빙쇄|폭풍신의 낙뢰|차원 연쇄|성운 붕괴
            태양핵 폭발|세계빙벽 붕괴|천둥 종말|공간 소멸|행성 낙하
            화신 폭발|빙신의 심판|신격 뇌전 연격|비전 특이점|별자리 붕괴
            종말의 불바다|절대빙옥 파쇄|세계뇌전|공허 폭발|은하 충돌
            태양을 삼킨 불꽃|시간을 얼리는 창|천벌의 만뢰|차원 종단|초신성
            창세의 화염|영원빙설 폭발|신들의 뇌폭|무한 특이점 붕괴|우주 종말
            """,
        ),
        HeroClass.CLERIC to rows(
            """
            빛의 화살|작은 심판|정화의 일격|성스러운 불씨|수호령 돌진
            성광탄|심판의 망치|악령 파쇄|성화구|천사의 깃날
            삼중 광선|죄악 분쇄|퇴마 연타|정화의 불꽃|영혼의 파동
            빛의 고리|단죄 강하|성수 파열|성화 폭발|천익 연격
            새벽 광선|정의의 망치|악마 봉인격|백염 기둥|수호천사 강하
            축복 광탄|천칭 심판|퇴마 사슬|성화 회오리|영혼 군세
            순백의 파동|죄인 분쇄|정화 연격|새벽 불꽃|천사의 검무
            성광 폭발|대심판|파마의 인장|백색 화염|성령 포화
            태양 광선|신벌의 망치|악령 소멸진|성화 폭풍|대천사 돌진
            구원 광탄|정의의 광주|퇴마 광쇄|정화의 불기둥|영혼 심판
            성자의 광선|천벌 분쇄|성수 연쇄|신성 화염구|천익 폭격
            찬란한 폭발|교단의 심판|파마 대연격|새벽의 화염|대천사 검무
            순교자의 광선|신의 망치|악마 파쇄진|백염 폭발|성령 군단
            천상의 광선|절대 단죄|퇴마 성역폭진|성화 해일|천군 강하
            구원자의 파동|운명 심판|지옥문 붕괴|태양 성화|영혼왕 연격
            세계수 성광포|종말 분쇄|만마 퇴마격|신성 불바다|대천사 연무
            신성 폭발|천국의 심판|악신 파쇄|창세의 성화|천군 포화
            영원의 광주|최후의 단죄|세계 정화폭진|종말의 백염|성령 폭격
            별을 밝히는 광선|신좌의 망치|천지 퇴마광|태양신의 불꽃|만천사 돌격
            창세 성광포|절대 심판|만악 종결광|영원한 성화|천국문 폭격
            """,
        ),
        HeroClass.PALADIN to rows(
            """
            빛의 베기|방패 강타|전투망치 강타|새벽 파동|수호의 일격
            성검 가르기|철벽 밀치기|성추 강하|여명 베기|맹세의 검격
            삼중 성검|빛의 방패돌진|기사의 망치 강타|새벽 연격|왕국의 일격
            십자 베기|방패 파쇄|단죄 강타|여명 폭발|기사단 돌격
            축성 검광|성벽 충격|황금 망치 강타|새벽의 칼날|불굴의 검격
            성검 회오리|빛의 돌진방패|심판의 성추|여명 파동진|왕가의 검격
            백은 가르기|철성 강타|신벌 망치|찬란한 연격|충성의 일격
            태양 십자참|수호벽 돌파|대성추 강하|새벽 폭풍|기사왕 돌진
            성광 검무|황금 방패격|천벌 강타|여명 폭발진|불패의 돌격
            왕국의 성검|성채 밀치기|정의의 대망치 강하|태양 파동|왕권 일격
            구원 가르기|신성 방패돌진|별철 성추 강타|여명 검무|기사단 심판
            대천사 성검|빛의 성벽격|혜성 망치|찬란한 검풍|황금 서약참
            용살 성검|천공 방패격|용골 분쇄추|새벽의 군무|성왕의 일격
            천상 십자참|신의 방벽돌파|천벌 대성추|여명 해일|영원 서약참
            태양왕의 검격|절대 수호격|심판왕의 망치|황금 새벽광|왕권 연격
            세계수 성검|성역 방패돌진|종말 성추|여명 종결진|기사왕 천공돌격
            신살 가르기|천국의 방패격|신격 분쇄추|창세 새벽광|불멸 서약참
            종말 십자참|세계벽 돌파|최후의 망치|태양 종말|성왕의 심판
            별을 가르는 성검|신좌 방패격|운명 분쇄추|영원 여명광|만기사 돌격
            창세의 성검|절대성벽 충격|신의 마지막 망치|첫 빛의 종언|왕국 영겁참
            """,
        ),
    )
}
