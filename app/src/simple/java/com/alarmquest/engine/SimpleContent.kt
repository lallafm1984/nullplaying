package com.alarmquest.engine

import com.alarmquest.model.EquipmentSlot
import com.alarmquest.model.HeroClass

internal object SimpleContent {
    val monsterKinds = listOf(
        "뿔늑대", "동굴 거미", "갑주 멧돼지", "잿빛 슬라임", "숲 고블린",
        "돌가죽 트롤", "해골 기사", "그림자 박쥐", "늪 히드라", "서리 와이번",
        "불꽃 정령", "고대 골렘", "심연 사냥개", "독안개 만티코어", "모래 리자드맨",
        "광산 코볼트", "황혼 밴시", "천둥 그리핀", "혈월 오우거", "공허 드레이크",
    )

    val monsterAdjectives = listOf(
        "굶주린", "잿빛", "광포한", "상처 입은", "검은갈기", "핏빛",
        "고대의", "폭풍을 두른", "달빛에 물든", "저주받은", "강철발톱", "안개 속",
        "수정 껍질의", "별을 삼킨", "불길한", "길 잃은", "깨어난", "침묵의",
    )

    val lootMaterials = listOf(
        "뿔늑대 송곳니", "수정 파편", "검은 깃털", "별가루", "용암 핵",
        "은빛 비늘", "고대 톱니", "유령 천", "거미 독낭", "그리핀 발톱",
        "정령 잔불", "푸른 수액", "황혼 진주", "미스릴 조각", "봉인석",
        "와이번 가죽", "심연의 눈", "골렘 심장", "달빛 버섯", "왕가의 인장",
        "트롤 어금니", "밴시의 눈물", "천둥 결정", "붉은 모래",
    )

    val lootForms = listOf(
        "표본", "부적", "결정", "꾸러미", "유물", "조각", "전리품", "밀봉함",
        "정수", "고서", "파편함", "문장", "핵", "가루병", "장식품", "기념물",
    )

    private data class EquipmentPrefixPool(
        val minimumLevel: Long,
        val prefixes: List<String>,
    )

    private val equipmentPrefixPools = listOf(
        EquipmentPrefixPool(
            minimumLevel = 1L,
            prefixes = listOf(
                "낡은", "거친", "소박한", "수습생의", "여행자의", "손질한", "튼튼한", "빛바랜",
            ),
        ),
        EquipmentPrefixPool(
            minimumLevel = 10L,
            prefixes = listOf(
                "단단한", "예리한", "정교한", "용병의", "경비대의", "철빛", "잘 벼린", "든든한",
            ),
        ),
        EquipmentPrefixPool(
            minimumLevel = 30L,
            prefixes = listOf(
                "수호자의", "용맹한", "왕가의", "폭풍의", "서리", "화염", "그림자", "핏빛",
            ),
        ),
        EquipmentPrefixPool(
            minimumLevel = 60L,
            prefixes = listOf(
                "미스릴", "용비늘", "별빛", "신성한", "새벽의", "황혼의", "월광", "정령의",
            ),
        ),
        EquipmentPrefixPool(
            minimumLevel = 100L,
            prefixes = listOf(
                "고대의", "심연의", "잊힌", "천상의", "태고의", "운명의", "세계수의", "성좌의",
            ),
        ),
    )

    fun equipmentPrefixes(level: Long): List<String> = equipmentPrefixPool(level).prefixes

    fun equipmentBases(slot: EquipmentSlot, level: Long, heroClass: HeroClass): List<String> =
        ClassEquipmentCatalog.bases(heroClass, slot, level)

    fun equipmentBase(slot: EquipmentSlot, level: Long, heroClass: HeroClass, variantIndex: Int): String =
        ClassEquipmentCatalog.base(heroClass, slot, level, variantIndex)

    private fun equipmentPrefixPool(level: Long): EquipmentPrefixPool {
        val safeLevel = level.coerceAtLeast(1L)
        return equipmentPrefixPools.last { safeLevel >= it.minimumLevel }
    }

    val skills = listOf(
        "강타" to "힘을 실은 일격으로 적을 강하게 타격한다.",
        "연속 타격" to "빈틈을 파고들어 적을 빠르게 연타한다.",
        "파쇄 충격" to "강한 충격을 터뜨려 적의 방어를 깨뜨린다.",
        "마력 폭발" to "응축한 마력을 폭발시켜 적을 휩쓴다.",
        "급소 공격" to "적의 약점을 정확히 노려 치명적인 일격을 가한다.",
        "빛의 심판" to "응집된 빛을 내리꽂아 적을 공격한다.",
        "그림자 습격" to "그림자 속에서 나타나 적을 기습한다.",
        "정령 포화" to "정령의 힘을 한꺼번에 쏟아 적을 타격한다.",
        "불꽃 소용돌이" to "거센 불꽃을 일으켜 적을 휘감아 공격한다.",
        "별빛 베기" to "별빛을 두른 궤적으로 적을 가른다.",
        "폭풍 돌진" to "폭풍처럼 돌진해 적의 방어선을 돌파한다.",
        "대지 분쇄" to "대지를 뒤흔드는 충격으로 적을 강타한다.",
        "심연 폭발" to "심연의 힘을 폭발시켜 적을 집어삼킨다.",
        "제왕의 일격" to "압도적인 힘을 집중해 적을 내려친다.",
        "시간 절단" to "찰나를 가르는 공격으로 적을 베어낸다.",
        "용의 숨결" to "뜨거운 파동을 뿜어 적을 불태운다.",
        "운명 파쇄" to "불리한 운명마저 깨뜨리는 일격을 적에게 가한다.",
        "천상의 사슬" to "빛의 사슬로 적을 조여 강한 피해를 준다.",
        "세계수의 창" to "거대한 생명의 창을 만들어 적을 꿰뚫는다.",
        "종말의 일격" to "모든 수련을 한 번의 공격에 담아 적에게 폭발시킨다.",
    )
}
