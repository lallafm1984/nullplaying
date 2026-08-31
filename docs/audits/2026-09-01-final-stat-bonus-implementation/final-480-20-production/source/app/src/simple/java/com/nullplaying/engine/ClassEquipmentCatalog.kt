package com.nullplaying.engine

import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass

/**
 * Class identity lives in the equipment archetype, while the progression label changes every
 * three hero levels. Legacy class-labelled equipment is migrated through [modernizeName].
 */
internal object ClassEquipmentCatalog {
    const val LEVELS_PER_NAME_BAND = 3L
    const val BASE_VARIANTS_PER_SLOT = 6

    private val progressionLabels = listOf(
        "훈련식",
        "견습식",
        "모험식",
        "철제",
        "강화 철제",
        "정련 철제",
        "정규군식",
        "개량 전투식",
        "숙련 전투식",
        "강철",
        "정련 강철",
        "기사단식",
        "룬각인",
        "왕실제",
        "비전 강화",
        "명장제",
        "정예 기사식",
        "대가식",
        "전승식",
        "은빛 합금",
        "별철",
        "용골 강화",
        "성유 각인",
        "마정석",
        "영혼결정",
        "태양각인",
        "달각인",
        "성역식",
        "유산급",
        "심층제",
        "천공식",
        "태고식",
        "운명식",
        "초월식",
    )

    private val archetypes: Map<HeroClass, Map<EquipmentSlot, List<String>>> = mapOf(
        HeroClass.WARRIOR to mapOf(
            EquipmentSlot.WEAPON to listOf("장검", "전투도끼", "장창", "대검", "철퇴", "쌍날검"),
            EquipmentSlot.HEAD to listOf("파이터 투구", "철면", "돌격 두건", "사슬모자", "뿔투구", "지휘관 투구"),
            EquipmentSlot.BODY to listOf("파이터 갑옷", "판금 흉갑", "사슬갑옷", "돌격 외투", "중갑", "지휘관 갑주"),
            EquipmentSlot.HANDS to listOf("파이터 건틀릿", "완갑", "전투 장갑", "손갑", "주먹갑", "지휘관 손목보호대"),
            EquipmentSlot.FEET to listOf("파이터 장화", "중장화", "각반", "철제신", "돌격 전투화", "지휘관 판금화"),
            EquipmentSlot.ACCESSORY to listOf("힘의 반지", "전투 목걸이", "용맹 브로치", "투지 부적", "파이터 허리띠", "승전 인장"),
        ),
        HeroClass.ROGUE to mapOf(
            EquipmentSlot.WEAPON to listOf("단검", "쌍단검", "암살검", "투척검", "곡도", "손쇠뇌"),
            EquipmentSlot.HEAD to listOf("시프 후드", "복면", "잠행 두건", "야행 머리띠", "암살 가면", "그림자 모자"),
            EquipmentSlot.BODY to listOf("시프 가죽옷", "잠행복", "암살 조끼", "그림자 외투", "경량 갑옷", "야행 코트"),
            EquipmentSlot.HANDS to listOf("시프 장갑", "손가락 장갑", "투척 장갑", "잠행 완갑", "암살 손목띠", "그림자 손갑"),
            EquipmentSlot.FEET to listOf("시프 장화", "잠행화", "경량 각반", "암살 장화", "그림자 신발", "야행 전투화"),
            EquipmentSlot.ACCESSORY to listOf("기민 반지", "독니 목걸이", "은신 브로치", "행운 부적", "도구 허리띠", "시프 인장"),
        ),
        HeroClass.RANGER to mapOf(
            EquipmentSlot.WEAPON to listOf("단궁", "장궁", "사냥활", "복합궁", "석궁", "레인저 창"),
            EquipmentSlot.HEAD to listOf("레인저 두건", "깃털 모자", "정찰 후드", "숲 머리띠", "추적 가면", "매사냥 모자"),
            EquipmentSlot.BODY to listOf("레인저 사냥복", "정찰 조끼", "가죽 갑옷", "숲 외투", "추적자 경갑", "매사냥 코트"),
            EquipmentSlot.HANDS to listOf("궁술 장갑", "사냥 장갑", "정찰 완갑", "활시위 손목띠", "매사냥 장갑", "숲 손갑"),
            EquipmentSlot.FEET to listOf("사냥 장화", "정찰화", "숲 각반", "추적 장화", "순찰 전투화", "바람 신발"),
            EquipmentSlot.ACCESSORY to listOf("명중 반지", "매눈 목걸이", "나침반 브로치", "숲 부적", "화살통 허리띠", "순찰 인장"),
        ),
        HeroClass.MAGE to mapOf(
            EquipmentSlot.WEAPON to listOf("마도봉", "원소 지팡이", "마력 단검", "주문서", "수정구", "마법서"),
            EquipmentSlot.HEAD to listOf("메이지 관", "마법 모자", "현자 후드", "룬 머리띠", "별 가면", "주문 두건"),
            EquipmentSlot.BODY to listOf("메이지 로브", "비전 외투", "룬 예복", "원소 망토", "현자 의복", "별빛 법복"),
            EquipmentSlot.HANDS to listOf("마력 장갑", "주문 손목띠", "룬 완갑", "원소 장갑", "현자 손갑", "비전 소매"),
            EquipmentSlot.FEET to listOf("메이지 신발", "비단신", "룬 장화", "원소 각반", "현자 장화", "별빛 덧신"),
            EquipmentSlot.ACCESSORY to listOf("마력 반지", "수정 목걸이", "룬 브로치", "원소 부적", "주문 허리띠", "비전 인장"),
        ),
        HeroClass.CLERIC to mapOf(
            EquipmentSlot.WEAPON to listOf("사제 철퇴", "기도 지팡이", "성서", "향로", "성물봉", "치유봉"),
            EquipmentSlot.HEAD to listOf("사제 두건", "클래릭 관", "기도 머리띠", "성가대 모자", "성유 가면", "신관 관"),
            EquipmentSlot.BODY to listOf("사제복", "클래릭 로브", "기도 예복", "성가 외투", "신관 법복", "성유 망토"),
            EquipmentSlot.HANDS to listOf("기도 장갑", "클래릭 완갑", "성유 손목띠", "치유 장갑", "신관 손갑", "성가 소매"),
            EquipmentSlot.FEET to listOf("사제 신발", "클래릭 장화", "기도 각반", "순례화", "신관 비단신", "성유 장화"),
            EquipmentSlot.ACCESSORY to listOf("기도 반지", "성상 목걸이", "성가 브로치", "치유 부적", "클래릭 허리띠", "신앙 인장"),
        ),
        HeroClass.PALADIN to mapOf(
            EquipmentSlot.WEAPON to listOf("성검", "성전 철퇴", "심판 망치", "기도창", "수호 대검", "왕실 장검"),
            EquipmentSlot.HEAD to listOf("팔라딘 투구", "성전 면갑", "수호 관", "왕실 투구", "심판 가면", "서약 두건"),
            EquipmentSlot.BODY to listOf("팔라딘 갑옷", "성전 흉갑", "수호 판금", "왕실 갑주", "심판 외투", "서약 사슬갑옷"),
            EquipmentSlot.HANDS to listOf("팔라딘 건틀릿", "성전 완갑", "수호 손갑", "왕실 장갑", "심판 주먹갑", "서약 손목보호대"),
            EquipmentSlot.FEET to listOf("팔라딘 장화", "성전 각반", "수호 철제신", "왕실 전투화", "심판 장화", "서약 판금화"),
            EquipmentSlot.ACCESSORY to listOf("서약 반지", "성휘 목걸이", "성전 브로치", "수호 부적", "팔라딘 허리띠", "심판 인장"),
        ),
    )

    fun bases(heroClass: HeroClass, slot: EquipmentSlot, level: Long): List<String> {
        val label = progressionLabel(level)
        return archetypes.getValue(heroClass).getValue(slot).map { archetype ->
            "$label $archetype"
        }
    }

    fun base(heroClass: HeroClass, slot: EquipmentSlot, level: Long, variantIndex: Int): String {
        val slotArchetypes = archetypes.getValue(heroClass).getValue(slot)
        val safeIndex = Math.floorMod(variantIndex, slotArchetypes.size)
        return "${progressionLabel(level)} ${slotArchetypes[safeIndex]}"
    }

    fun bandStart(level: Long): Long {
        val tier = tierIndex(level)
        return tier * LEVELS_PER_NAME_BAND + 1L
    }

    fun modernizeName(name: String): String =
        LEGACY_CLASS_EQUIPMENT_TERMS.fold(name) { updatedName, (legacy, current) ->
            updatedName.replace(legacy, current)
        }

    fun splitBaseName(name: String): Pair<String, String>? {
        val staticLabel = progressionLabels
            .asSequence()
            .sortedByDescending(String::length)
            .firstOrNull { name.startsWith("$it ") }
        val progressionLabel = staticLabel ?: TRANSCENDENT_LABEL
            .find(name)
            ?.value
            ?: return null
        val archetype = name.removePrefix("$progressionLabel ")
        return if (archetype in allArchetypes) progressionLabel to archetype else null
    }

    val localizableProgressionLabels: Set<String>
        get() = progressionLabels.toSet()

    val localizableArchetypes: Set<String>
        get() = allArchetypes

    private fun progressionLabel(level: Long): String {
        val tier = tierIndex(level)
        return if (tier < progressionLabels.size.toLong()) {
            progressionLabels[tier.toInt()]
        } else {
            "초월 ${tier - progressionLabels.lastIndex}단식"
        }
    }

    private fun tierIndex(level: Long): Long =
        (level.coerceAtLeast(1L) - 1L) / LEVELS_PER_NAME_BAND

    private val allArchetypes: Set<String> = archetypes.values
        .flatMap { slots -> slots.values.flatten() }
        .toSet()

    private val TRANSCENDENT_LABEL = Regex("^초월 [1-9][0-9]*단식(?= )")

    private val LEGACY_CLASS_EQUIPMENT_TERMS = listOf(
        "전사 투구" to "파이터 투구",
        "전사 갑옷" to "파이터 갑옷",
        "전사 건틀릿" to "파이터 건틀릿",
        "전사 장화" to "파이터 장화",
        "전사 허리띠" to "파이터 허리띠",
        "도적 후드" to "시프 후드",
        "도적 가죽옷" to "시프 가죽옷",
        "도적 장갑" to "시프 장갑",
        "도적 장화" to "시프 장화",
        "도적 인장" to "시프 인장",
        "레인져 창" to "레인저 창",
        "레인져 두건" to "레인저 두건",
        "레인져 사냥복" to "레인저 사냥복",
        "순찰자 창" to "레인저 창",
        "순찰자 두건" to "레인저 두건",
        "순찰자 사냥복" to "레인저 사냥복",
        "마도사 관" to "메이지 관",
        "마도사 로브" to "메이지 로브",
        "마도사 신발" to "메이지 신발",
        "성직 관" to "클래릭 관",
        "성직자 로브" to "클래릭 로브",
        "성직 완갑" to "클래릭 완갑",
        "성직 장화" to "클래릭 장화",
        "성직 허리띠" to "클래릭 허리띠",
        "성기사 투구" to "팔라딘 투구",
        "성기사 갑옷" to "팔라딘 갑옷",
        "성기사 건틀릿" to "팔라딘 건틀릿",
        "성기사 장화" to "팔라딘 장화",
        "성기사 허리띠" to "팔라딘 허리띠",
    )
}
