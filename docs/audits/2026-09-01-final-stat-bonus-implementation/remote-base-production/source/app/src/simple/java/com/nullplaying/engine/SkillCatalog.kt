package com.nullplaying.engine

import com.nullplaying.engine.SkillElement.*
import com.nullplaying.engine.SkillMotion.*
import com.nullplaying.model.HeroClass

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
    val damagePercentMin: Int,
    val damagePercentMax: Int,
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
    private const val MAX_TIER = 20
    private const val CATALOG_SEED_SALT = 0x2F63_1A4D_7B29_5CE1L

    private data class SignatureSkill(
        val catalogId: String,
        val name: String,
        val hitCount: Int,
    )

    private val signatureSkills: Map<HeroClass, List<SignatureSkill>> by lazy(::createSignatureSkills)
    val all: List<SkillDefinition> = HeroClass.entries.flatMap(::buildClassCatalog)
    private val byId = all.associateBy(SkillDefinition::catalogId)
    private val byClass = all.groupBy(SkillDefinition::heroClass)

    init {
        require(all.size == HeroClass.entries.size * MAX_TIER)
        require(byId.size == all.size)
        HeroClass.entries.forEach { heroClass ->
            val definitions = byClass.getValue(heroClass)
            require(definitions.size == MAX_TIER)
            require(definitions.map { it.name }.distinct().size == definitions.size)
            require(definitions.map { it.unlockLevel } == listOf(1) + (5..95 step 5).toList())
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

    fun select(@Suppress("UNUSED_PARAMETER") skillCatalogSeed: Long, heroClass: HeroClass, tier: Int): SkillDefinition {
        val safeTier = tier.coerceIn(1, MAX_TIER)
        return byClass.getValue(heroClass)[safeTier - 1]
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

    fun damagePercentRange(tier: Int): IntRange {
        val safeTier = tier.coerceIn(1, MAX_TIER)
        val minimum = 90 + (safeTier - 1) * 20
        return minimum..(minimum + 10)
    }

    private fun buildClassCatalog(heroClass: HeroClass): List<SkillDefinition> {
        val skills = signatureSkills.getValue(heroClass)
        require(skills.size == MAX_TIER)
        return skills.mapIndexed { tierIndex, signature ->
            val tier = tierIndex + 1
            val candidate = signature.catalogId.substringAfterLast("_c").toInt() - 1
            require(signature.catalogId.startsWith("${heroClass.name.lowercase()}_t${tier.toString().padStart(2, '0')}_"))
            require(candidate in 0..4)
            val timingProfile = SkillTimingProfile.entries[(tier + candidate) % SkillTimingProfile.entries.size]
            val element = elementFor(heroClass, candidate, tier, signature.name)
            val motion = motionFor(heroClass, candidate, tier, signature.name)
            val damagePercentRange = damagePercentRange(tier)
            SkillDefinition(
                catalogId = signature.catalogId,
                heroClass = heroClass,
                unlockLevel = if (tier == 1) 1 else (tier - 1) * 5,
                candidate = candidate,
                name = signature.name,
                description = if (signature.hitCount == 1) {
                    "${signature.name} 기술로 적을 강하게 공격한다."
                } else {
                    "${signature.name} 기술로 적을 ${signature.hitCount}회 연속 공격한다."
                },
                damagePercentMin = damagePercentRange.first,
                damagePercentMax = damagePercentRange.last,
                hitWeights = weightsFor(signature.hitCount),
                hitTimingsMillis = authoredHitTimingsFor(
                    catalogId = signature.catalogId,
                    hitCount = signature.hitCount,
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

    /**
     * The reviewed sprite sheets share an F01-F09 attack contract: buildup occupies F01-F07,
     * contact starts around F08 and the final/strongest hit lands on F09 (about 500 ms).
     * Explicit exceptions mirror the authored web VFX metadata.
     */
    private fun authoredHitTimingsFor(catalogId: String, hitCount: Int): List<Int> = when (catalogId) {
        "warrior_t01_c01" -> listOf(490)
        "warrior_t03_c02" -> listOf(250)
        "warrior_t06_c05" -> listOf(63, 125, 188, 250, 313, 500)
        "warrior_t14_c03",
        "warrior_t17_c01",
        "warrior_t18_c02",
        "warrior_t19_c02",
        "warrior_t20_c01",
        -> listOf(800)
        "warrior_t16_c05" -> listOf(63, 100, 138, 175, 213, 250, 288, 325, 363, 400, 438, 500)
        else -> when (hitCount) {
            1 -> listOf(500)
            2 -> listOf(313, 500)
            3 -> listOf(188, 313, 500)
            4 -> listOf(125, 250, 375, 500)
            5 -> listOf(125, 219, 313, 406, 500)
            else -> List(hitCount) { index ->
                63 + ((500 - 63) * index / (hitCount - 1).coerceAtLeast(1))
            }
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

    private fun signatureRows(value: String): List<SignatureSkill> = value.trimIndent()
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val (catalogId, name, hitCount) = line.split('|').map(String::trim)
            SignatureSkill(catalogId, name, hitCount.toInt())
        }
        .toList()

    private fun createSignatureSkills(): Map<HeroClass, List<SignatureSkill>> = mapOf(
        HeroClass.WARRIOR to signatureRows(
            """
            warrior_t01_c01|칼날 베기|1
            warrior_t02_c03|강철 베기|1
            warrior_t03_c02|파쇄격|1
            warrior_t04_c03|대지 가르기|1
            warrior_t05_c02|십자 참격|1
            warrior_t06_c05|폭풍 베기|6
            warrior_t07_c01|철갑 돌진|1
            warrior_t08_c02|전장의 돌격|1
            warrior_t09_c03|회오리 참격|1
            warrior_t10_c01|대지 분쇄|1
            warrior_t11_c02|폭풍검|1
            warrior_t12_c04|섬광 일섬|1
            warrior_t13_c03|무영 연참|1
            warrior_t14_c03|용살검|1
            warrior_t15_c01|멸천 일섬|1
            warrior_t16_c05|무극일섬|12
            warrior_t17_c01|파멸의 검|1
            warrior_t18_c02|천지 가르기|1
            warrior_t19_c02|천하대양단|1
            warrior_t20_c01|최후의 일격|1
            """,
        ),
        HeroClass.ROGUE to signatureRows(
            """
            rogue_t01_c01|빠른 찌르기|3
            rogue_t02_c02|암습|1
            rogue_t03_c03|맹독 쌍침|2
            rogue_t04_c04|칼날 덫|3
            rogue_t05_c01|초승달 단검|3
            rogue_t06_c05|사각 일격|1
            rogue_t07_c01|네 갈래 비수|4
            rogue_t08_c02|잔상 도약|1
            rogue_t09_c02|월하 암습|2
            rogue_t10_c04|칼날 감옥|3
            rogue_t11_c04|은사 난무|4
            rogue_t12_c05|침묵의 처형|1
            rogue_t13_c03|독왕의 송곳니|2
            rogue_t14_c04|천라지망|4
            rogue_t15_c04|월광 철사|2
            rogue_t16_c04|검은 실 감옥|3
            rogue_t17_c05|왕의 숨통|1
            rogue_t18_c01|종말의 단검무|4
            rogue_t19_c02|그림자 세계 절단|3
            rogue_t20_c05|죽음의 한 점|1
            """,
        ),
        HeroClass.RANGER to signatureRows(
            """
            ranger_t01_c01|정조준 사격|1
            ranger_t02_c02|연속 사격|3
            ranger_t03_c04|올가미 화살|2
            ranger_t04_c03|회오리 화살|2
            ranger_t05_c02|유성 화살비|3
            ranger_t06_c04|곰발톱 강타|3
            ranger_t07_c01|철갑 관통|1
            ranger_t08_c05|보름달 일격|1
            ranger_t09_c04|매의 급강하|2
            ranger_t10_c02|천공 화살비|5
            ranger_t11_c05|월식 사격|1
            ranger_t12_c04|그리핀 급습|3
            ranger_t13_c05|은하수 관통|3
            ranger_t14_c03|대기의 칼날|3
            ranger_t15_c04|왕의 사냥개|2
            ranger_t16_c02|무한 화살진|5
            ranger_t17_c03|창공 붕괴|3
            ranger_t18_c04|신화의 덫|3
            ranger_t19_c05|은하 종단|3
            ranger_t20_c05|성운 가르기|1
            """,
        ),
        HeroClass.MAGE to signatureRows(
            """
            mage_t01_c01|불씨 화살|2
            mage_t02_c02|얼음 창|4
            mage_t03_c03|삼중 낙뢰|3
            mage_t04_c04|비전 충격|2
            mage_t05_c01|용암 분출|3
            mage_t06_c04|공간 절단|1
            mage_t07_c02|서리 폭풍|1
            mage_t08_c03|사방 낙뢰|4
            mage_t09_c05|유성우|4
            mage_t10_c04|공간 왜곡파|2
            mage_t11_c01|지옥불 구체|3
            mage_t12_c02|절대영도 파열|2
            mage_t13_c03|뇌신의 사슬|5
            mage_t14_c04|공허 칼날|3
            mage_t15_c01|불사조 격돌|1
            mage_t16_c05|행성 낙하|1
            mage_t17_c04|비전 특이점|3
            mage_t18_c02|절대빙옥 파쇄|4
            mage_t19_c05|초신성|4
            mage_t20_c05|우주 종말|5
            """,
        ),
        HeroClass.CLERIC to signatureRows(
            """
            cleric_t01_c01|빛의 화살|2
            cleric_t02_c02|심판의 망치|1
            cleric_t03_c03|퇴마 연타|4
            cleric_t04_c04|성화 폭발|2
            cleric_t05_c05|수호천사 강하|3
            cleric_t06_c03|퇴마 사슬|4
            cleric_t07_c01|순백의 파동|2
            cleric_t08_c03|파마의 인장|3
            cleric_t09_c05|대천사 돌진|3
            cleric_t10_c04|정화의 불기둥|2
            cleric_t11_c02|천벌 분쇄|1
            cleric_t12_c05|대천사 검무|2
            cleric_t13_c03|악마 파쇄진|2
            cleric_t14_c04|성화 해일|3
            cleric_t15_c03|지옥문 붕괴|4
            cleric_t16_c01|세계수 성광포|2
            cleric_t17_c05|천군 포화|3
            cleric_t18_c03|세계 정화폭진|4
            cleric_t19_c02|신좌의 망치|1
            cleric_t20_c05|천국문 폭격|2
            """,
        ),
        HeroClass.PALADIN to signatureRows(
            """
            paladin_t01_c01|빛의 베기|2
            paladin_t02_c02|철벽 밀치기|2
            paladin_t03_c03|기사의 망치 강타|1
            paladin_t04_c01|십자 베기|2
            paladin_t05_c04|새벽의 칼날|4
            paladin_t06_c03|심판의 성추|1
            paladin_t07_c05|충성의 일격|1
            paladin_t08_c02|수호벽 돌파|2
            paladin_t09_c01|성광 검무|3
            paladin_t10_c05|왕권 일격|1
            paladin_t11_c03|별철 성추 강타|1
            paladin_t12_c05|황금 서약참|2
            paladin_t13_c02|천공 방패격|1
            paladin_t14_c01|천상 십자참|2
            paladin_t15_c03|심판왕의 망치|1
            paladin_t16_c02|성역 방패돌진|2
            paladin_t17_c05|불멸 서약참|2
            paladin_t18_c04|태양 종말|2
            paladin_t19_c01|별을 가르는 성검|2
            paladin_t20_c05|왕국 영겁참|5
            """,
        ),
    )
}
