package com.nullplaying.engine

import com.nullplaying.model.TaleKind

/** 여섯 환경이 순환하고, 깊이 규칙은 [LabyrinthProgression]이 적용하는 무한 미궁. */
internal object LabyrinthTaleCatalog {
    val tales: List<AdventureTaleDefinition> = listOf(
        labyrinth(
            "root_gate",
            "뿌리문의 회랑",
            "기억나무 뿌리가 길을 바꾸는 회랑",
            "미궁 제{depth}구역의 문은 거대한 뿌리 사이에서 열렸다. 조금 전까지 있던 길이 나이테 안으로 접혀 들어갔다.",
            "{heroTopic} 움직이는 뿌리에 귀환 매듭을 남기고 제{depth}구역의 중심문을 열었다.",
            "다음 구역: 물에 잠긴 기록수로가 더 깊은 길을 비춘다.",
            act("root_threshold", "움직이는 문턱", "발을 디딜 때마다 뿌리문이 다른 방향을 향했다.", "새벽종의 박자에 맞춰 멈추는 문턱을 찾아 첫 안전 표식을 남겼다."),
            act("ring_corridor", "나이테 회랑", "벽의 나이테에는 아직 일어나지 않은 원정의 흠집까지 새겨져 있었다.", "현재 원정의 표식만 푸른 실로 구분해 거짓 길을 걷어 냈다."),
            act("root_nest", "뿌리 틈의 둥지", "{minorEnemySubject} 귀환 매듭을 모아 회랑 안쪽에 둥지를 만들었다.", "둥지를 치우고 끊긴 매듭을 다시 이어 지상 신호를 회복했다."),
            act("memory_sap", "기억을 품은 수액", "투명한 수액 속에서 오래전 수호대의 그림자가 같은 길을 반복했다.", "그림자를 따라가지 않고 수액 흐름 반대편의 실제 통로를 찾았다."),
            act("root_core_gate", "뿌리 중심문", "다섯 갈래 뿌리가 한 문을 감싸며 서로 다른 방향으로 당겼다.", "다섯 귀환종을 차례로 울려 중심문을 열고 다음 구역으로 내려갔다."),
        ),
        labyrinth(
            "drowned_archive",
            "잠긴 기록수로",
            "지워진 원정 기록이 물 위로 떠오르는 수로",
            "미궁 제{depth}구역에는 천장까지 물이 찬 기록실과 숨을 쉴 수 있는 좁은 길 하나만 남아 있었다.",
            "{heroTopic} 떠다니는 기록을 모아 수문의 순서를 복원하고 제{depth}구역의 수위를 안전한 높이까지 낮췄다.",
            "다음 구역: 물 아래 드러난 유리맥 동굴에서 푸른 박동이 이어진다.",
            act("floating_pages", "떠오른 기록", "젖지 않는 종이들이 물결을 거슬러 같은 책장으로 모였다.", "종이의 번호를 맞춰 이 구역의 수문 개방 순서를 복원했다."),
            act("breathing_path", "숨 쉬는 길", "수면 위 좁은 공기층이 수문 진동에 따라 사라졌다 나타났다.", "진동 간격을 재어 원정대가 멈추지 않고 이동할 시간을 확보했다."),
            act("archive_predator", "기록을 먹는 것", "{minorEnemySubject} 이름과 숫자가 적힌 부분만 골라 갉아먹었다.", "남은 문장과 이전 구역 기록을 대조해 사라진 수문 번호를 되찾았다."),
            act("inverse_sluice", "거꾸로 열린 수문", "첫 수문을 열자 물이 빠지지 않고 더 깊은 기록실에서 밀려왔다.", "개방 순서를 뒤집어 역류를 멈추고 아래층의 압력을 풀었다."),
            act("drained_archive", "드러난 서고", "물이 낮아지자 바닥 전체를 덮은 미궁 깊이 지도가 모습을 드러냈다.", "현재 위치와 다음 유리맥을 지도에 표시하고 기록수로를 통과했다."),
        ),
        labyrinth(
            "glass_cavern",
            "유리맥 동굴",
            "빛과 발소리를 수백 갈래로 복제하는 투명 동굴",
            "미궁 제{depth}구역의 벽은 속까지 투명했고 원정대의 모습이 수백 갈래 길을 동시에 걷고 있었다.",
            "{heroTopic} 빛이 아니라 바닥의 진동을 따라 진짜 유리맥을 찾아 제{depth}구역의 공명핵을 잠재웠다.",
            "다음 구역: 공명핵 아래에서 종 없는 주조장의 망치 소리가 들린다.",
            act("hundred_reflections", "백 갈래 모습", "유리벽마다 조금 다른 {heroSubject} 서로 다른 길을 가리켰다.", "모습 대신 귀환줄의 무게를 따라 실제 통로 하나를 구분했다."),
            act("light_false_path", "빛이 만든 거짓길", "푸른 별빛이 밝을수록 막힌 벽이 열린 통로처럼 보였다.", "등불을 낮추고 바닥 균열의 바람으로 통과 가능한 길을 찾았다."),
            act("glass_vein_swarm", "유리맥의 무리", "{minorEnemySubject} 투명한 벽 안팎을 오가며 공명음을 흐트러뜨렸다.", "무리를 몰아내고 깨진 공명 기둥을 제 박자에 맞췄다."),
            act("true_resonance", "진짜 공명", "수백 반향 가운데 한 음만 발밑에서 곧게 올라왔다.", "그 음을 새벽종으로 되울려 공명핵이 숨은 중심을 드러냈다."),
            act("quieted_core", "잠든 공명핵", "공명핵이 깨어날 때마다 동굴의 길과 벽이 서로 자리를 바꿨다.", "세 종의 합음을 맞춰 핵을 잠재우고 고정된 하강로를 열었다."),
        ),
        labyrinth(
            "bell_forge",
            "종 없는 주조장",
            "울림만 남아 스스로 무기를 찍어 내는 지하 공방",
            "미궁 제{depth}구역의 거대한 모루 위에서는 망치도 장인도 없이 붉은 불꽃과 쇳소리만 튀었다.",
            "{heroTopic} 빈 주조장이 만들어 낸 미완성 문지기를 멈추고 제{depth}구역의 화로를 귀환종에서 옮긴 불꽃으로 봉인했다.",
            "다음 구역: 식은 쇳물이 재가 피는 정원 쪽으로 흐른다.",
            act("empty_anvil", "빈 모루", "보이지 않는 망치가 모루를 내리칠 때마다 쇳조각이 생겨났다.", "타격 간격 사이로 지나가 모루 아래의 동력관을 찾아냈다."),
            act("walking_scrap", "걸어 다니는 쇳조각", "버려진 조각들이 서로 붙어 원정대의 장비 모양을 흉내 냈다.", "가짜 장비의 이음새를 끊고 회수 가능한 안전 금속만 분리했다."),
            act("forge_scavenger", "화로의 약탈자", "{minorEnemySubject} 푸른 불씨를 물어 나르며 꺼진 화로를 다시 깨웠다.", "약탈자를 몰아내고 불씨를 귀환등에 옮겨 화로의 열을 낮췄다."),
            act("unfinished_keeper", "미완성 문지기", "머리도 이름도 없는 금속 형상이 하강문 앞에서 몸을 세웠다.", "박자가 어긋난 관절을 끊어 문지기를 멈추고 봉인문을 확보했다."),
            act("sealed_furnace", "봉인된 화로", "중심 화로는 종소리를 불꽃으로 바꾸며 계속 쇳물을 토해 냈다.", "귀환종을 거꾸로 걸어 울림을 되돌리고 화로를 다음 원정까지 봉인했다."),
        ),
        labyrinth(
            "ash_garden",
            "재가 피는 정원",
            "쓰러진 적의 흔적이 검은 꽃으로 되살아나는 정원",
            "미궁 제{depth}구역에는 흙 대신 고운 재가 깔렸고 지나온 괴물들의 흔적이 꽃처럼 피어났다.",
            "{heroTopic} 되살아나는 흔적의 뿌리를 끊고 제{depth}구역에 지상 흙으로 만든 안전 원을 남겼다.",
            "다음 구역: 정원 중심의 씨앗문이 별 없는 나선계단으로 열린다.",
            act("footprint_bloom", "발자국에서 핀 꽃", "한 걸음을 뗄 때마다 바로 뒤에서 검은 꽃이 피어 길을 덮었다.", "지상 흙을 얇게 뿌려 돌아갈 발자국이 사라지지 않게 했다."),
            act("ash_pollen", "기억을 흐리는 꽃가루", "꽃가루를 마신 순찰대원들이 방금 지나온 막의 순서를 잊었다.", "귀환종을 짧게 울려 꽃가루를 가라앉히고 다섯 매듭을 다시 확인했다."),
            act("garden_scavenger", "재꽃을 지키는 것", "{minorEnemySubject} 쓰러진 흔적을 모아 더 큰 꽃봉오리를 키웠다.", "정원의 위협을 몰아내고 흔적이 모이는 검은 뿌리를 드러냈다."),
            act("borrowed_faces", "꽃 속의 얼굴", "큰 꽃마다 지나온 문지기의 얼굴이 잠깐씩 되살아났다.", "얼굴에 응답하지 않고 뿌리의 박동만 따라 중심 씨앗문에 도착했다."),
            act("earth_circle", "지상 흙의 원", "씨앗문은 재 위에 선 누구도 다음 길로 보내지 않았다.", "지상 흙으로 안전 원을 만들고 그 안에서 문을 열어 정원을 통과했다."),
        ),
        labyrinth(
            "starless_stair",
            "별 없는 나선계단",
            "오를수록 더 깊어지고 내려갈수록 입구가 가까워지는 계단",
            "미궁 제{depth}구역의 나선계단에는 위와 아래가 따로 없었고 푸른 별빛마저 방향을 잃었다.",
            "{heroTopic} 귀환종의 무게로 깊이를 재고 제{depth}구역의 끝문을 통과했다. 계단 너머에서 다시 뿌리문이 자라기 시작했다.",
            "다음 구역: 미궁은 새로운 뿌리문의 회랑으로 이어지며 원정을 계속 부른다.",
            act("directionless_step", "방향을 잃은 첫 단", "한 단을 내려가자 입구가 아래쪽에서 나타났다.", "빛 대신 귀환줄이 당기는 방향을 지상으로 정해 기준을 세웠다."),
            act("weight_of_depth", "깊이의 무게", "같은 계단도 들고 있는 물건에 따라 서로 다른 곳으로 이어졌다.", "귀환종 하나만 들고 이동해 변하지 않는 원정 경로를 찾았다."),
            act("stair_lurker", "계단 사이의 것", "{minorEnemySubject} 위아래가 바뀌는 순간마다 안전줄을 끊으려 달려들었다.", "위협을 몰아내고 매듭마다 작은 무게추를 달아 방향 변화를 표시했다."),
            act("blue_star_out", "꺼진 푸른 별", "원정을 이끌던 푸른 빛이 한순간 사라져 모든 계단이 같아 보였다.", "세 새벽종의 잔향으로 별빛 없이도 끝문이 있는 단을 찾아냈다."),
            act("spiral_end_gate", "나선의 끝문", "끝문 너머에는 처음 본 것과 닮았지만 더 굵은 뿌리가 자라고 있었다.", "현재 깊이를 장부와 귀환종에 새기고 다음 회랑으로 안전하게 넘어갔다."),
        ),
    ).mapIndexed { index, tale -> tale.copy(chapterNumber = index + 1) }

    fun definitionForDepth(depth: Long): AdventureTaleDefinition {
        val safeDepth = depth.coerceAtLeast(1L)
        val index = ((safeDepth - 1L) % tales.size.toLong()).toInt()
        return tales[index]
    }

    private fun labyrinth(
        suffix: String,
        title: String,
        subtitle: String,
        opening: String,
        ending: String,
        hook: String,
        vararg acts: TaleActDefinition,
    ) = AdventureTaleDefinition(
        id = "labyrinth.$suffix",
        kind = TaleKind.LABYRINTH,
        volumeNumber = 4,
        chapterNumber = 0,
        title = title,
        subtitle = subtitle,
        openingTemplate = opening,
        endingTemplate = ending,
        nextHookTemplate = hook,
        acts = acts.toList(),
        nextId = null,
    )

    private fun act(
        id: String,
        title: String,
        body: String,
        completion: String,
    ) = TaleActDefinition(
        id = id,
        titleTemplate = title,
        bodyTemplate = body,
        completionTemplate = completion,
        // Instantiation replaces this authored placeholder with the current depth target.
        target = AdventureTaleCatalog.REPEAT_ACT_TARGET,
    )
}
