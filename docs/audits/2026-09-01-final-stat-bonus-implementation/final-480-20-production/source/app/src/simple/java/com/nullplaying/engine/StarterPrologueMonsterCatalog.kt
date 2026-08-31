package com.nullplaying.engine

import com.nullplaying.model.HeroClass

/** Class-specific encounter pools for the playable opening quests. */
internal object StarterPrologueMonsterCatalog {
    val groups: List<QuestMonsterGroup> = listOf(
        group(HeroClass.WARRIOR,
            "성문 들쥐|검댕 들개|수레 거미|쇠사슬 도마뱀|성벽 여우|방패 까마귀|재먼지 박쥐|북문 살쾡이|파수꾼 하이에나|국경 뿔토끼",
            "방패 이름을 먹는 자|귀환로 파괴자",
            "부러진 성문의 약탈자|문지기의 이름을 지우는 자|쇠사슬 길의 투장|북문을 긁는 우두머리|돌아올 길을 막는 자",
            "북문을 노리는|방패 자국을 긁는|귀환로에 웅크린|성벽 밖에서 포효하는"),
        group(HeroClass.ROGUE,
            "기와 들쥐|봉인 나방|골목 거미|은빛 까마귀|지붕 여우|통로 도마뱀|자물쇠 딱정벌레|그림자 박쥐|시장 살쾡이|추격 하이에나",
            "봉인끈 절단자|지붕길 추적자",
            "은빛 동전의 사냥꾼|잘린 봉인의 주인|숨은 통로의 감시자|퇴로를 훔치는 그림자|비밀 문서의 탈취자",
            "기와 아래 숨은|봉인 냄새를 맡은|발자국을 지우는|그림자보다 늦은"),
        group(HeroClass.RANGER,
            "숲 들쥐|깃털 나방|풀잎 거미|오솔길 여우|덫 도마뱀|안개 박쥐|표지석 까마귀|북풍 살쾡이|미끼 하이에나|침묵 뿔토끼",
            "발자국 지우개|덫줄 사냥꾼",
            "새 울음을 삼킨 자|풀잎의 결을 꺾는 자|안개 덫의 파수꾼|묶인 짐승의 포식자|북쪽 오솔길을 막는 자",
            "새 울음을 끊은|흔적을 감춘|덫 주변을 맴도는|북쪽 바람을 거스르는"),
        group(HeroClass.MAGE,
            "수정 들쥐|별빛 나방|기록실 거미|파문 도마뱀|푸른 여우|주문 박쥐|고서 딱정벌레|좌표 살쾡이|망령 하이에나|마력 뿔토끼",
            "별자리 포식자|마력을 먹는 어둠",
            "금 간 수정구의 잔영|지워진 별자리의 주인|첫 주문을 삼키는 자|파문에서 태어난 망령|마지막 좌표의 파괴자",
            "수정 파편에서 나온|기록을 지우는|푸른 파문에 깃든|좌표실을 떠도는"),
        group(HeroClass.CLERIC,
            "예배당 들쥐|등불 나방|계단 거미|전령 여우|피난로 도마뱀|기도 박쥐|빛 딱정벌레|다리 살쾡이|어둠 하이에나|봉투 뿔토끼",
            "전령 추격자|등불을 끄는 자",
            "작은 등불의 포식자|상처 입은 전령의 추격자|빛의 다리를 무너뜨리는 자|피난 행렬을 노린 어둠|구조 요청의 탈취자",
            "등불 그늘에 숨은|전령의 피를 쫓는|피난로를 가로막는|기도의 빛을 먹는"),
        group(HeroClass.PALADIN,
            "인장 들쥐|서약 나방|훈련장 거미|균열 여우|방패 도마뱀|수호 박쥐|빛 딱정벌레|경계 살쾡이|그림자 하이에나|보호막 뿔토끼",
            "서약 파괴자|균열의 집행자",
            "빛바랜 인장의 훼손자|보호막 틈의 주인|방패의 맹세를 시험하는 자|마을로 향하는 그림자|새 수호를 거부하는 자",
            "깨진 인장에서 나온|보호막 틈을 파고드는|서약의 빛을 노리는|마을 경계를 넘보는"),
    )

    private fun group(
        heroClass: HeroClass,
        normalNames: String,
        eliteNames: String,
        bossNames: String,
        adjectives: String,
    ): QuestMonsterGroup {
        val taleId = StarterPrologueCatalog.idFor(heroClass)
        return QuestMonsterGroup(
            taleId = taleId,
            normals = definitions(taleId, "normal", normalNames),
            elites = definitions(taleId, "elite", eliteNames),
            bosses = definitions(taleId, "boss", bossNames),
            adjectives = adjectives.split('|') + listOf(
                "낮게 웅크린", "상처 입은", "눈 밝은", "빠른", "거친 털의", "소리 없는",
                "한쪽 눈의", "흔적을 감춘", "무리를 이탈한", "밤을 노리는", "날카로운", "굶주린",
                "먼지를 뒤집어쓴", "길목을 지키는", "종소리에 이끌린", "새벽을 피하는",
            ),
        )
    }

    private fun definitions(
        taleId: String,
        grade: String,
        names: String,
    ): List<QuestMonsterDefinition> = names.split('|').mapIndexed { index, name ->
        QuestMonsterDefinition(
            id = "$taleId.$grade.${(index + 1).toString().padStart(2, '0')}",
            baseName = name,
        )
    }
}
