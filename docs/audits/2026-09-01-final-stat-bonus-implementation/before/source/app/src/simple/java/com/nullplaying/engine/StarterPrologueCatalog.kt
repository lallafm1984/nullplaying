package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.TaleKind

/**
 * Class-authored opening quests that use the same five-act combat progression as every tale.
 * Their smaller targets make the opening substantial without delaying access to chapter one.
 */
internal object StarterPrologueCatalog {
    private val actTargets = listOf(7L, 8L, 9L, 10L, 12L)

    val tales: List<AdventureTaleDefinition> = listOf(
        prologue(
            heroClass = HeroClass.WARRIOR,
            title = "부러진 성문의 파수꾼",
            subtitle = "돌아올 이들을 위해 다시 세운 북문",
            opening = "{heroTopic} 무너진 북문 앞에서 손잡이가 닳은 검을 발견했다. 종소리는 성문 밖에서 들리는데, 오늘은 아무도 그 문을 지키지 않았다.",
            openingSlides = listOf(
                "{heroTopic} 무너진 북문 앞에서 손잡이가 닳은 검을 발견했다.",
                "종소리는 성문 밖에서 들려왔지만, 오늘은 아무도 그 문을 지키지 않았다.",
                "{heroTopic} 검을 들어 올리고 북문 밖에 남은 흔적을 따라갔다.",
            ),
            ending = "{heroTopic} 성문을 위협하던 무리를 몰아내고 북문에 검집의 끈을 묶었다. 돌아올 이들이 있을 때까지 길을 지키겠다는 첫 맹세였다.",
            hook = "첫 정식 임무: 새벽이 되어도 돌아오지 않은 국경 순찰대를 찾아라.",
            act("broken_gate", "부러진 성문", "문밖의 발자국을 따라 북문을 에워싼 짐승들을 밀어낸다.", "북문을 가로막던 짐승을 몰아내고 성문 밖으로 이어진 발자국을 찾았다."),
            act("names_on_shields", "방패에 남은 이름", "버려진 방패를 지키는 포식자들 사이에서 사라진 문지기들의 이름을 확인한다.", "긁힌 방패에서 문지기들의 이름과 북쪽으로 끌려간 흔적을 확인했다."),
            act("blade_opens_road", "검이 여는 길", "무너진 수레와 쇠사슬을 점거한 무리를 쓰러뜨려 귀환로를 연다.", "쇠사슬을 끊고 도망친 사람들이 돌아올 수 있는 길을 다시 열었다."),
            act("roar_beyond_gate", "성문 밖의 포효", "성벽을 긁는 우두머리와 그 무리를 정면에서 막아선다.", "북문을 노리던 우두머리를 꺾고 성벽 안의 사람들을 지켜 냈다."),
            act("oath_of_return", "돌아올 길의 맹세", "마지막 습격을 막고 국경 수비대의 표식을 되찾는다.", "수비대의 표식을 되찾아 북문에 걸고 첫 국경 의뢰를 받아들였다."),
        ),
        prologue(
            heroClass = HeroClass.ROGUE,
            title = "은빛 동전의 흔적",
            subtitle = "지붕길 끝에서 되찾은 비밀 봉인",
            opening = "{heroSubject} 새벽 시장의 지붕에서 눈을 뜨자 손바닥에 낯선 은빛 동전이 놓여 있었다. 동전이 굴러간 곳에는 잘린 봉인끈과 급히 지운 발자국이 남아 있었다.",
            openingSlides = listOf(
                "{heroTopic} 새벽 시장의 지붕에서 눈을 떴다.",
                "손바닥에는 처음 보는 은빛 동전 하나가 놓여 있었다.",
                "동전이 굴러간 곳에는 잘린 봉인끈과 지워진 발자국이 남아 있었다.",
            ),
            ending = "{heroTopic} 봉인을 훔친 그림자의 퇴로를 끊고 관측소의 비밀 문서를 되찾았다. 이름을 남기지 않은 첫 의뢰가 국경으로 이어졌다.",
            hook = "봉인된 지령: 실종된 순찰대가 남긴 내부자의 흔적을 추적하라.",
            act("silver_coin", "지붕 위의 은빛 동전", "동전을 노리는 골목 무리를 피해 지붕길의 첫 흔적을 확보한다.", "추격자들을 따돌리고 동전 뒷면에 새겨진 관측소 표식을 찾아냈다."),
            act("cut_seal", "잘린 봉인끈", "기와 사이의 흔적을 지우는 자들을 쫓아 도난 경로를 밝힌다.", "잘린 봉인끈과 검은 재가 같은 도둑에게서 나온 흔적임을 확인했다."),
            act("another_way", "닫힌 창의 다른 길", "숨은 통로를 점거한 감시자들을 제거하고 잠금장치를 해제한다.", "낡은 잠금장치를 풀어 시장 아래의 비밀 통로를 열었다."),
            act("faster_than_shadow", "그림자보다 빠르게", "봉인을 든 도둑의 앞길을 끊으며 추격자 무리를 각개격파한다.", "도둑의 퇴로를 막고 빼앗긴 관측소 봉인을 되찾았다."),
            act("nameless_request", "이름 없는 의뢰", "봉인의 주인을 노리는 마지막 추적자들을 처리하고 문서를 연다.", "비밀 문서를 지켜 내고 누구보다 먼저 국경의 실종 사건을 맡았다."),
        ),
        prologue(
            heroClass = HeroClass.RANGER,
            title = "침묵한 숲의 길",
            subtitle = "바람과 발자국이 가리킨 북쪽",
            opening = "{heroSubject} 숲 가장자리에 닿자 익숙한 새의 울음이 갑자기 끊겼다. 역풍인데도 종소리와 짐승의 피 냄새가 같은 방향에서 흘러왔다.",
            openingSlides = listOf(
                "{heroSubject} 숲 가장자리에 닿자 익숙한 새의 울음이 끊겼다.",
                "역풍 속에서도 종소리와 짐승의 피 냄새가 같은 방향에서 흘러왔다.",
                "{heroTopic} 꺾인 풀잎을 살피며 침묵이 시작된 곳으로 향했다.",
            ),
            ending = "{heroTopic} 덫에 묶인 짐승을 구하고 사라진 북쪽 오솔길을 되찾았다. 숲은 실종된 순찰대가 지나간 국경 방향을 가리켰다.",
            hook = "새로 드러난 길: 북쪽 오솔길을 따라 실종된 순찰대의 행방을 확인하라.",
            act("broken_birdsong", "끊긴 새의 울음", "숲의 소리를 삼키는 무리를 추적해 첫 흔적을 찾는다.", "숲의 침묵을 만든 무리를 몰아내고 피 묻은 깃털을 발견했다."),
            act("path_without_tracks", "발자국이 아닌 길", "지워진 발자국 주변의 짐승들을 물리치며 풀잎의 결을 읽는다.", "풀잎과 부러진 가지로 누군가 북쪽 흔적을 고의로 지웠음을 밝혀냈다."),
            act("arrow_tests_wind", "바람을 시험하는 화살", "안개 속 덫을 지키는 무리를 원거리에서 제거해 안전한 길을 만든다.", "화살로 덫줄을 끊고 고립된 이들이 건널 수 있는 길을 표시했다."),
            act("beast_in_trap", "덫에 묶인 짐승", "미끼를 노리는 포식자들을 막아 붙잡힌 짐승을 구한다.", "덫의 우두머리를 쓰러뜨리고 북쪽 길을 아는 짐승을 풀어 주었다."),
            act("road_to_border", "숲이 가리킨 국경", "사라진 오솔길의 마지막 방해물을 걷어 내고 국경 표지석에 도달한다.", "북쪽 오솔길과 순찰대의 낡은 표식을 찾아 첫 추적 의뢰를 받았다."),
        ),
        prologue(
            heroClass = HeroClass.MAGE,
            title = "금 간 수정구의 파문",
            subtitle = "기록에서 사라진 별자리의 좌표",
            opening = "관측소의 수정구가 {hero} 앞에서 금이 간 채 푸른 빛을 뿜었다. 종소리는 소리가 아니라 마력이 만든 파문이었고, 그 파문이 마을의 경계를 흔들었다.",
            openingSlides = listOf(
                "관측소의 수정구가 {hero} 앞에서 금이 간 채 푸른 빛을 뿜었다.",
                "울려 퍼진 종소리는 소리가 아니라 마력이 만든 파문이었다.",
                "기록에서 지워진 별자리 한 줄이 파문의 방향을 가리켰다.",
            ),
            ending = "{heroTopic} 파문 속 망령을 해산하고 지워진 별자리의 마지막 좌표를 복원했다. 좌표는 국경의 폐쇄된 관측소를 가리켰다.",
            hook = "복원된 좌표: 국경 관측소와 사라진 순찰대의 기록을 조사하라.",
            act("cracked_orb", "금 간 수정구", "수정구의 파편에서 태어난 마력 생물을 정리해 파문의 결을 읽는다.", "불안정한 파편을 봉인하고 파문이 북쪽에서 역류한다는 사실을 알아냈다."),
            act("erased_constellation", "사라진 별자리", "기록실을 잠식한 마물들을 몰아내며 지워진 문장을 복원한다.", "고서의 빈자리에서 국경 관측소로 이어진 별자리 한 줄을 되살렸다."),
            act("first_spell", "첫 주문의 불꽃", "마력을 먹는 어둠을 주문으로 걷어 내고 좌표실로 향한다.", "첫 주문으로 어둠을 밀어내고 봉인된 좌표실의 문을 열었다."),
            act("wraith_in_ripple", "파문 속의 망령", "좌표실에서 태어난 망령들과 싸우며 파문의 중심을 안정시킨다.", "파문의 핵을 꿰뚫어 기록에 없던 망령의 발생을 멈췄다."),
            act("question_from_stars", "별이 남긴 질문", "마지막 좌표를 삼키려는 존재를 물리치고 관측 기록을 완성한다.", "국경 관측소를 가리키는 좌표와 첫 조사 의뢰를 확보했다."),
        ),
        prologue(
            heroClass = HeroClass.CLERIC,
            title = "꺼지지 않은 등불",
            subtitle = "상처 입은 전령이 전한 구조 요청",
            opening = "{heroSubject} 비어 있는 예배당에 들어서자 작은 등불 하나만이 꺼지지 않고 흔들렸다. 돌계단 아래에서는 상처 입은 전령이 봉투를 품고 도움을 청했다.",
            openingSlides = listOf(
                "{heroSubject} 비어 있는 예배당에 들어서자 작은 등불 하나가 흔들렸다.",
                "꺼지지 않은 불빛 아래에서 누군가의 희미한 신음이 들려왔다.",
                "돌계단 아래에는 상처 입은 전령이 봉투를 품고 쓰러져 있었다.",
            ),
            ending = "{heroTopic} 피난민을 노린 어둠을 물리치고 전령의 봉투를 열었다. 국경 마을의 구조 요청이 첫 사명이 되었다.",
            hook = "전령의 부탁: 돌아오지 않은 순찰대를 찾아 국경 마을의 피난로를 열어라.",
            act("undying_lamp", "꺼지지 않은 작은 등불", "예배당을 덮친 어둠을 밀어내 등불과 사람들의 길을 지킨다.", "등불을 노리던 어둠을 걷어 내고 계단 아래의 구조 신호를 발견했다."),
            act("wounded_messenger", "상처 입은 전령", "전령을 노리는 추격자들을 막으며 치료할 시간을 번다.", "추격자들을 물리치고 전령이 다시 말할 수 있을 만큼 상처를 돌봤다."),
            act("bridge_of_prayer", "기도가 만든 다리", "무너진 다리의 마물들을 정화해 피난민이 건널 빛의 길을 만든다.", "흩어진 빛을 모아 고립된 사람들이 건널 수 있는 길을 열었다."),
            act("darkness_after_lamp", "등불을 노린 어둠", "피난 행렬을 덮치는 어둠 앞에 서서 끝까지 사람들을 보호한다.", "어둠의 우두머리를 정화하고 모든 피난민을 예배당으로 이끌었다."),
            act("in_every_name", "모두의 이름으로", "봉투를 빼앗으려는 마지막 무리를 물리치고 구조 요청을 지킨다.", "국경 마을의 구조 요청을 받아 단 한 사람도 남기지 않겠다고 맹세했다."),
        ),
        prologue(
            heroClass = HeroClass.PALADIN,
            title = "깨진 서약의 인장",
            subtitle = "스스로 선택한 첫 번째 수호",
            opening = "오래된 훈련장 바닥에서 {hero}의 손길에 빛바랜 서약의 인장이 깨어났다. 축복 같은 종소리 아래에는 누군가 인장을 깨뜨린 흔적이 남아 있었다.",
            openingSlides = listOf(
                "오래된 훈련장 바닥에서 {hero}의 손길에 빛바랜 인장이 깨어났다.",
                "축복 같은 종소리 아래에는 누군가 서약을 깨뜨린 흔적이 남아 있었다.",
                "인장의 균열 너머로 마을의 보호막이 무너지고 있었다.",
            ),
            ending = "{heroTopic} 보호막의 틈을 봉인하고 마을로 향하던 그림자를 저지했다. 물려받은 말이 아닌 스스로 선택한 수호의 서약이었다.",
            hook = "되살아난 인장의 지시: 국경의 더 큰 균열과 실종된 순찰대를 조사하라.",
            act("faded_seal", "빛바랜 서약의 인장", "깨진 인장 주변의 마물들을 물리쳐 훼손된 흔적을 조사한다.", "인장을 더럽힌 무리를 몰아내고 보호막이 북쪽부터 약해졌음을 확인했다."),
            act("border_to_protect", "지켜야 할 경계", "보호막의 틈으로 들어오는 적을 막으며 균열의 위치를 찾는다.", "마을로 이어진 세 균열을 찾아 수호 표식으로 봉쇄했다."),
            act("shield_and_oath", "방패와 맹세", "방패에 인장의 빛을 옮겨 쏟아지는 어둠의 무리를 받아낸다.", "빛의 방패로 피난 시간을 벌고 가장 큰 틈 앞에 홀로 남았다."),
            act("shadow_tests_oath", "서약을 시험하는 그림자", "마을로 달려드는 그림자와 그 추종자들을 한 걸음도 통과시키지 않는다.", "그림자의 우두머리를 저지해 마을의 보호막을 다시 세웠다."),
            act("new_guardianship", "새로운 수호의 시작", "인장을 파괴하려는 마지막 존재를 물리치고 국경의 균열을 비춘다.", "되살아난 인장 앞에서 스스로 선택한 수호를 맹세하고 첫 의뢰를 받았다."),
        ),
    )

    private val byClass = tales.associateBy { tale ->
        HeroClass.entries.first { heroClass -> tale.id == idFor(heroClass) }
    }

    fun forClass(heroClass: HeroClass): AdventureTaleDefinition = requireNotNull(byClass[heroClass])

    fun idFor(heroClass: HeroClass): String = "prologue.${heroClass.name.lowercase()}"

    private fun prologue(
        heroClass: HeroClass,
        title: String,
        subtitle: String,
        opening: String,
        openingSlides: List<String>,
        ending: String,
        hook: String,
        vararg acts: TaleActDefinition,
    ) = AdventureTaleDefinition(
        id = idFor(heroClass),
        kind = TaleKind.PROLOGUE,
        volumeNumber = 0,
        chapterNumber = 0,
        title = title,
        subtitle = subtitle,
        openingTemplate = opening,
        endingTemplate = ending,
        nextHookTemplate = hook,
        acts = acts.mapIndexed { index, act -> act.copy(target = actTargets[index]) },
        nextId = "ash_border.c01",
        openingSlideTemplates = openingSlides,
    )

    private fun act(
        id: String,
        title: String,
        body: String,
        completion: String,
    ) = TaleActDefinition(id, title, body, completion, target = 1L)
}
