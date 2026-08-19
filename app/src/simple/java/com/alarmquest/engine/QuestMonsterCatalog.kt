package com.alarmquest.engine

import com.alarmquest.model.MonsterGrade

internal data class QuestMonsterDefinition(
    val id: String,
    val baseName: String,
) {
    val trophyNames: List<String>
        get() = listOf(
            "${baseName}의 잔해", "$baseName 표본", "${baseName}의 흔적", "${baseName}의 외피 조각",
            "${baseName}의 조직 표본", "${baseName}의 둡지 부스러기", "${baseName}의 발자국 석고",
            "${baseName}의 먹이 흔적", "${baseName}의 재가루", "${baseName}의 생태 기록",
            "${baseName}의 활동 기록", "${baseName}의 탈피 조각", "${baseName}의 선혈 표본",
            "${baseName}의 음영 스케치", "${baseName}의 이동로 표식", "${baseName}의 관찰 일지",
        )
}

internal data class QuestMonsterGroup(
    val taleId: String,
    val normals: List<QuestMonsterDefinition>,
    val elites: List<QuestMonsterDefinition>,
    val bosses: List<QuestMonsterDefinition>,
    val adjectives: List<String>,
)

/**
 * Each authored tale owns ten cumulative normal species, two elites, and one boss per act.
 * Normal prefixes are presentation-only; catalog ids remain stable for saves and analytics.
 */
internal object QuestMonsterCatalog {
    const val NORMALS_PER_QUEST = 10
    const val ELITES_PER_QUEST = 2
    const val BOSSES_PER_QUEST = 5

    private val COMMON_ADJECTIVES = listOf(
        "길 잃은", "배고픈", "날카로운", "낮게 웅크린", "상처 입은", "눈 밝은",
        "제멋대로인", "숨을 죽인", "빠른", "노련한", "무리를 이탈한", "밤을 노리는",
        "한쪽 눈의", "거친 털의", "소리 없는", "흔적을 감춘",
    )

    val groups: List<QuestMonsterGroup> = listOf(
        group(
            "ash_border.c01",
            "재먼지 들쥐|막사 뒷거미|잔불 박쥐|검댕 뽔토끼|성벽 여우|잿빛 갈까마귀|부러진 창 도마뱀|등불 나방|숨은 하이에나|재밭 딱정벌레",
            "순찰대 포식자|발자국 지우개",
            "빈 막사를 지키는 자|거꾸로 걷는 사냥꾼|부러진 창날의 주인|재더미 잠복자|검은 원의 추적자",
            "성벽 안에 숨은|순찰대를 따른|재 지도에 남은|새벽을 피한",
        ),
        group(
            "ash_border.c02",
            "수로 슬라임|잿물 도롱뇽|우물 거북|종가루 거미|녹슨 물뱀|수문 이끼골렘|진흙 두꺼비|물밑 설치류|주조장 나방|재거품 기생벌레",
            "우물밑 잠복자|종혀 삼키는 자",
            "검어진 물의 주인|물밑 종소리의 응답자|잠긴 수로의 문지기|버려진 주조장의 감시자|첫 번째 종혀의 포식자",
            "잿물에 불은|수문 틈에 난|종소리에 끌린|우물 재를 먹은",
        ),
        group(
            "ash_border.c03",
            "종이 깃털새|봉인 나방|잉크 도롱뇽|재 속 지렁이|편지 테두리벌레|역인 독거미|진흙 전령새|충성 사냥개|파수꾼 박쥐|지워진 이름 여우",
            "공문 사냥꾼|봉인을 먹는 자",
            "재 속 종이 새의 주인|지워진 수신인의 명령자|죽은 자의 전령|쪸기는 편지의 탈취자|오르반의 이름을 지키는 자",
            "봉인을 난도질한|죽은 명령을 따르는|종이 새를 쪷는|지워진 이름을 먹은",
        ),
        group(
            "ash_border.c04",
            "예배당 박쥐|벽화 도마뱀|종혀 구멍 거미|지하 왕쥐|찬가 나방|청동 딱정벌레|계단 도롱뇽|문 없는 살쾋이|종벽 이끼골렘|침묵 포식새",
            "충돌하는 종소리|지하 찬가의 감시자",
            "문 없는 예배당을 지키는 자|종혀로 열린 길의 파수꾼|세 새벽종의 그림자|침묵의 찬가를 짓는 자|울리면 안 되는 종의 주인",
            "종혀 홈에 웅크린|소리 없이 찬가하는|비밀 계단을 지키는|청동 먼지를 뒤집어쓴",
        ),
        group(
            "ash_border.c05",
            "밀수로 들쥐|은빛 단추 까마귀|세관 여우|보급함 거미|지하로 도롱뇽|샛길 하이에나|길잡이 부엉이|녹슨 왕쥐|짐수레 독거미|통로 이끼골렘",
            "니아의 길을 쪷는 자|군수품 탈취자",
            "훔쳐 간 지도의 사냥꾼|니아의 값을 지키는 자|세관 아래 길의 문지기|돌아오지 못한 밀수꾼|정직한 거짓말의 주인",
            "은빛 단추를 물은|밀수로에 납작 엎드린|군용 통로를 따르는|짐수레를 휘감은",
        ),
        group(
            "ash_border.c06",
            "적진 들개|깃발 까마귀|천막 거미|재 흔적 하이에나|군패 도마뱀|빈 진지 여우|화살촉 딱정벌레|경계선 살쾋이|재무리 도롱뇽|진지 스크리커",
            "빌린 깃발의 포식자|안쪽을 향한 화살꾼",
            "너무 조용한 적진의 주인|같은 재를 키우는 자|마지막 경계병의 사냥꾼|빌린 깃발 아래의 투장|안쪽을 향한 화살의 지휘자",
            "버려진 적기를 휘감은|양군의 재를 먹은|빈 진지에 남은|성채에서 내려온",
        ),
        group(
            "ash_border.c07",
            "성벽 들쥐|서문 박쥐|재 파도 도롱뇽|훃불 나방|종탑 거미|성벽 살쾋이|잿빛 스크리커|금 간 종 딱정벌레|서문 하이에나|재먹이 포식새",
            "서문 파괴자|새벽 한 조각을 먹는 자",
            "재의 파도를 몰고 온 자|서문의 이름을 지우는 자|금 간 종의 감시자|첫 번째 울림을 삼킨 자|새벽 한 조각의 포식자",
            "성벽의 빛을 먹는|서문을 들이받는|금 간 종소리에 멈춘|재의 파도에서 튀어나온",
        ),
        group(
            "ash_border.c08",
            "명령서 좀|인장 거미|기록실 박쥐|잉크 도롱뇽|철수 명령 까마귀|서기관 살쾋이|검은 재 딱정벌레|성채 왕쥐|밀랍 여우|조작 문서 나방",
            "서기관 사냥꾼|베른의 인장수",
            "같은 날의 명령서를 찢는 자|다른 잉크의 투장|사라진 서기관을 지키는 자|보호라는 변명의 집행자|성채로 향한 이름의 주인",
            "인장을 물어뜯은|검은 잉크를 흘리는|기록실에 깃든|지워진 줄에서 나온",
        ),
        group(
            "ash_border.c09",
            "장례 수레 들쥐|검은 천 거미|쇠사슬 도마뱀|종 수레 까마귀|추격로 여우|밀수로 박쥐|청동 가루 딱정벌레|끊어진 다리 살쾋이|종혀 상자 왕쥐|재 봉인 나방",
            "검은 종 호송자|종혀 탈취자",
            "장례 행렬을 이끄는 자|천 아래 종의 감시자|끊어진 추격로의 파괴자|두 번째 종혀의 약탈자|한 번 울린 흔적의 주인",
            "검은 천을 뒤집어쓴|종 쇠사슬을 끌고 다니는|끊어진 추격로에 숨은|재로 봉인된",
        ),
        group(
            "ash_border.c10",
            "성채 왕쥐|빈 병영 거미|지하로 도마뱀|종소리 박쥐|재 갑옷 딱정벌레|성문 살쾋이|파수자 여우|잠든 자 나방|종홀 이끼골렘|재 왕의 사냥개",
            "베른의 친위수|잘못된 수호자",
            "닫히지 않는 문의 문지기|빈 병영의 모집관|베른의 고백을 지키는 자|잘못된 수호자의 그림자|마지막 종혀의 포식자",
            "성채 안에서 기어나온|종소리에 홀린|재 갑옷을 두른|빈 병영을 배회하는",
        ),
        group(
            "ash_border.c11",
            "꺼진 하늘 박쥐|재 왕 들쥐|피난로 거미|종 울림 나방|잉걸 사자 까마귀|재 갑옷 왕쥐|성채문 도마뱀|잊힌 이름 살쾋이|새벽 포식자|재폭풍 스크리커",
            "재의 왕 친위대|새벽을 삼킨 투장",
            "꺼진 하늘의 하수인|되돌아온 길의 파괴자|세 종의 대답을 먹는 자|잊힌 이름의 왕|베른의 마지막 명령을 막는 자",
            "잊힌 이름을 웅얼거리는|꺼진 하늘 아래의|재 갑옷 틈에 숨은|피난로를 가로막는",
        ),
        group(
            "ash_border.c12",
            "종혀 피라미|세 탑 까마귀|새벽 나방|유리 잎 들쥐|떨어진 재 도롱뇽|종탑 도마뱀|빛금 거미|국경 여우|유리 잎 딱정벌레|마지막 재 스크리커",
            "세 탑의 울림을 흐리는 자|돌아온 새벽의 포식자",
            "세 종혀를 흘어 놓는 자|세 탑의 불빛을 끄는 자|함께 울린 종의 반향|돌아온 새벽의 마지막 재|유리 잎을 지키는 북방의 사자",
            "새벽빛에 흐려지는|유리 잎을 물은|세 종탑 사이를 오가는|마지막 재에서 피어난",
        ),
        group(
            "ash_border.epilogue_rebuild",
            "보급로 들쥐|수레바퀴 거미|표지석 도마뱀|다리 밑 여우|길목 나방|샛길 두꺼비|짐수레 까마귀|보급품 딱정벌레|진흙 하이에나|성문 살쾋이",
            "보급수레 약탈자|무너진 다리의 지배자",
            "끊긴 보급표의 조작자|겹쳐진 바퀴자국의 주인|길목 둥지의 어미|무너진 다리의 파괴자|첫 수레를 막는 자",
            "새로 난 길을 따르는|보급품 냄새를 맡은|바퀴자국에 숨은|다리 그늘에 납작 엎드린",
        ),
        group(
            "ash_border.epilogue_old_bell",
            "초소 들쥐|늦은 종 박쥐|군패 거미|귀환로 여우|폐허 도마뱀|순찰 명부 좀|표지석 까마귀|초소 왕쥐|종줄 살쾋이|오래된 임무 나방",
            "이름 없는 군패를 지키는 자|귀환 신호를 울리는 자",
            "빈 초소의 발자국 주인|늦은 종소리의 응답자|이름 없는 군패의 포식자|마지막 귀환로의 파수꾼|전해진 표식을 되찾는 자",
            "오래된 종소리를 따르는|이름 닳은 군패를 물은|지도에서 지워진|빈 초소를 배회하는",
        ),
        group(
            "ash_border.epilogue_glass_leaf",
            "유리숲 들쥐|빛먹은 나방|북쪽 도롱뇽|투명 껑질 딱정벌레|이정표 까마귀|낯선 야영 여우|유리풀 거미|물길 살쾋이|빛 신호 박쥐|숲 가장자리 하이에나",
            "유리숲 경계자|세 불빛의 교란자",
            "빛을 머금은 잎의 포식자|북쪽 이정표를 지키는 자|유리숲 가장자리의 사냥꾼|낯선 야영지의 감시자|세 번의 답신을 흐리는 자",
            "유리 나무 사이의|북쪽 빛에 이끌린|낯선 불씨를 따라온|투명하게 굳은 껑질의",
        ),
        group(
            "ash_border.epilogue_waterway",
            "수문 슬라임|검은 띠 도롱뇽|종가루 거미|점검로 왕쥐|재 웅덩이 두꺼비|푸른 돌 딱정벌레|역류 물뱀|수로 살쾋이|녹슨 수문 거북|세 갈래 이끼골렘",
            "수문 아래 눌러앉은 자|굳은 재의 파수꾼",
            "다시 검어진 우물의 주인|막힌 수문을 지키는 자|물밑 종가루의 포식자|재의 웅덩이에 굳은 자|맑아진 수로의 마지막 오물",
            "검은 물띠를 휘감은|수문 바닥에 붙은|종가루를 털어 내는|세 물길을 오가는",
        ),
        group(
            "ash_border.epilogue_memorial",
            "묘표 들쥐|명부 좀|유품 까마귀|공동묘지 거미|군패 여우|빗물 도롱뇽|편지 고리 딱정벌레|묘지 살쾋이|기억 등불 나방|북방 우편낭 왕쥐",
            "지워진 명단의 수호자|이름 없는 무덤의 주인",
            "비에 드러난 묘표의 파괴자|지워진 명단을 먹는 자|순찰대 유품의 약탈자|이름을 새기는 밤의 감시자|기억의 등불을 끄는 자",
            "오래된 이름을 긁는|유품 사이에 숨은|묘표 그늘을 도는|기억의 빛을 먹는",
        ),
        group(
            "ash_border.epilogue_three_lights",
            "북방 들쥐|신호빛 나방|국경 너머 여우|낯선 장화 거미|골짜기 도롱뇽|거울탑 박쥐|사절길 살쾋이|북방 지도 까마귀|공동 표지석 딱정벌레|세 불빛 하이에나",
            "신호를 어지럽히는 자|북방 사절 사냥꾼",
            "북쪽의 첫 불빛을 삼킨 자|두 번째 답신을 흐리는 자|국경 너머 발자국의 주인|밤의 회담을 깨는 자|열어 둔 길의 마지막 파수꾼",
            "세 번째 불빛에 이끌린|낯선 장화자국을 따른|북방 표지석에 웅크린|공동 순찰로를 배회하는",
        ),
    ) + NorthernGlassMonsterCatalog.groups

    private val groupsByTaleId = groups.associateBy { it.taleId }
    private val monstersById = groups
        .flatMap { it.normals + it.elites + it.bosses }
        .associateBy { it.id }

    fun groupFor(taleId: String): QuestMonsterGroup? = groupsByTaleId[taleId]

    fun definition(id: String): QuestMonsterDefinition? = monstersById[id]

    fun normalPoolSize(actIndex: Int): Int = when (actIndex.coerceIn(0, BOSSES_PER_QUEST - 1)) {
        0 -> 4
        1 -> 6
        2 -> 8
        else -> 10
    }

    fun encounterGrade(progress: Long, target: Long): MonsterGrade {
        val safeTarget = target.coerceAtLeast(1L)
        val ordinal = progress.coerceAtLeast(0L).let { if (it == Long.MAX_VALUE) it else it + 1L }
        return when (ordinal) {
            safeTarget -> MonsterGrade.BOSS
            percentPoint(safeTarget, 30), percentPoint(safeTarget, 60) -> MonsterGrade.ELITE
            else -> if (ordinal > safeTarget) MonsterGrade.BOSS else MonsterGrade.NORMAL
        }
    }

    fun eliteIndex(progress: Long, target: Long): Int {
        val safeProgress = progress.coerceAtLeast(0L)
        val ordinal = if (safeProgress == Long.MAX_VALUE) safeProgress else safeProgress + 1L
        return if (ordinal == percentPoint(target.coerceAtLeast(1L), 30)) 0 else 1
    }

    fun validationErrors(taleIds: Set<String>): List<String> = buildList {
        val missing = taleIds - groupsByTaleId.keys
        val extra = groupsByTaleId.keys - taleIds
        if (missing.isNotEmpty()) add("missing monster groups: $missing")
        if (extra.isNotEmpty()) add("unknown monster groups: $extra")
        groups.forEach { group ->
            if (group.normals.size != NORMALS_PER_QUEST) add("${group.taleId}: normals")
            if (group.elites.size != ELITES_PER_QUEST) add("${group.taleId}: elites")
            if (group.bosses.size != BOSSES_PER_QUEST) add("${group.taleId}: bosses")
            if (group.adjectives.size < 16) add("${group.taleId}: adjectives")
            val monsters = group.normals + group.elites + group.bosses
            if (monsters.any { it.baseName.isBlank() }) add("${group.taleId}: blank monster")
            if (monsters.map { it.id }.distinct().size != monsters.size) add("${group.taleId}: duplicate ids")
            if (monsters.map { it.baseName }.distinct().size != monsters.size) add("${group.taleId}: duplicate names")
        }
        if (monstersById.size != groups.size * (NORMALS_PER_QUEST + ELITES_PER_QUEST + BOSSES_PER_QUEST)) {
            add("duplicate global monster ids")
        }
    }

    private fun group(
        taleId: String,
        normalNames: String,
        eliteNames: String,
        bossNames: String,
        thematicAdjectives: String,
    ): QuestMonsterGroup = QuestMonsterGroup(
        taleId = taleId,
        normals = definitions(taleId, "normal", normalNames),
        elites = definitions(taleId, "elite", eliteNames),
        bosses = definitions(taleId, "boss", bossNames),
        adjectives = thematicAdjectives.split('|').map(::proofread) + COMMON_ADJECTIVES,
    )

    private fun definitions(
        taleId: String,
        grade: String,
        names: String,
    ): List<QuestMonsterDefinition> = names.split('|').mapIndexed { index, name ->
        QuestMonsterDefinition(
            id = "$taleId.$grade.${(index + 1).toString().padStart(2, '0')}",
            baseName = proofread(name),
        )
    }

    private fun proofread(value: String): String = value
        .replace('\uBF54', '\uBFD4')
        .replace('\uCAB8', '\uCAD3')
        .replace('\uCAB7', '\uC887')
        .replace('\uD6C3', '\uD683')
        .replace('\uCF8B', '\uCFA1')
        .replace('\uAED1', '\uAECD')
        .replace("흘어", "흩어")

    private fun percentPoint(target: Long, percent: Int): Long {
        val whole = (target / 100L) * percent.toLong()
        val remainder = ((target % 100L) * percent.toLong() + 99L) / 100L
        return whole + remainder
    }
}
