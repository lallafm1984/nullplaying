package com.alarmquest.engine

import com.alarmquest.model.AdventureTaleState
import com.alarmquest.model.TaleActState
import com.alarmquest.model.TaleKind
import com.alarmquest.model.TaleVariant

internal data class TaleActDefinition(
    val id: String,
    val titleTemplate: String,
    val bodyTemplate: String,
    val completionTemplate: String,
    val target: Long,
)

internal data class AdventureTaleDefinition(
    val id: String,
    val kind: TaleKind,
    val volumeNumber: Int,
    val chapterNumber: Int,
    val title: String,
    val subtitle: String,
    val openingTemplate: String,
    val endingTemplate: String,
    val nextHookTemplate: String,
    val acts: List<TaleActDefinition>,
    val nextId: String?,
)

/** Authored, immutable story content for the automatic adventure loop. */
internal object AdventureTaleCatalog {
    const val MAIN_TALE_COUNT = 24
    const val ACTS_PER_TALE = 5
    const val MAX_ACT_TARGET = 1_000L
    const val REPEAT_ACT_TARGET = MAX_ACT_TARGET

    private val MAIN_IDS = (1..MAIN_TALE_COUNT).map { chapter ->
        "ash_border.c${chapter.toString().padStart(2, '0')}"
    }
    private val MINOR_ENEMIES = listOf(
        "재먼지 들쥐",
        "수로 슬라임",
        "잿빛 박쥐",
        "유리등 나방",
        "종가루 거미",
        "검댕 뿔토끼",
        "안개 갈퀴새",
        "수문 이끼골렘",
        "잿물 도롱뇽",
        "북풍 송곳니",
        "묘표 딱정벌레",
        "등불 포식자",
    )
    private val COMPLETION_BY_TITLE = mapOf(
        // 1장
        "빈 막사" to "빈 막사에서 끌려간 때와 인원을 확인했다.",
        "거꾸로 난 발자국" to "안쪽 발자국에서 내부자의 개입을 확인했다.",
        "부러진 창" to "창날의 국경군 칼자국이 순찰대의 매복에 내부자가 가담했음을 증명했다.",
        "살아남은 목소리" to "로웬을 구하고 누군가 순찰대를 괴물에게 몰았다는 증언을 얻었다.",
        "재로 그린 지도" to "로웬의 재 지도에서 폐쇄된 우물과 세 개의 검은 원을 다음 단서로 확보했다.",
        // 2장
        "검어진 물" to "우물의 재가 자연 현상이 아니며 마을 아래에서 올라오고 있음을 확인했다.",
        "우물 아래의 종소리" to "밑줄에 답하는 종소리를 따라 잠긴 지하 수로의 입구를 찾았다.",
        "잠긴 수로" to "{minorEnemy}을 몰아내고 버려진 주조장으로 통하는 수로를 다시 열었다.",
        "버려진 주조장" to "떼어 낸 청동 자국을 비교해 누군가 새벽종을 해체했음을 밝혀냈다.",
        "첫 번째 종혀" to "첫 번째 종혀와 그 위의 오르반이라는 이름을 확보했다.",
        // 3장
        "재 속의 종이 새" to "비에도 지워지지 않는 종이 새에서 봉인된 군 공문을 얻었다.",
        "지워진 수신인" to "긁힌 이름 뒤에 남은 로웬의 계급장으로 편지의 수신인을 특정했다.",
        "죽은 자의 명령" to "죽은 순찰대원에게 다음 날 출정을 명령한 공문이 조작됐음을 밝혀냈다.",
        "쫓기는 전령" to "편지를 탈취하려는 추격자들을 떨쳐 내고 마지막 봉인을 지켰다.",
        "종지기 오르반" to "봉인 아래의 문장에서 오르반과 '새벽은 아직 죽지 않았다'는 암호를 얻었다.",
        // 4장
        "벽뿐인 예배당" to "예배당 벽의 홈이 종혀를 위한 열쇠 구멍임을 확인했다.",
        "종혀가 여는 길" to "첫 종혀로 비밀 계단을 열어 오르반이 숨은 지하 예배실에 도착했다.",
        "세 개의 새벽종" to "세 종이 재의 문을 여는 무기가 아니라 문을 잠그는 열쇠임을 알아냈다.",
        "침묵의 찬가" to "오르반의 침묵 찬가에서 세 종혀가 모두 도난당했다는 사실을 들었다.",
        "울리면 안 되는 종" to "세 종을 따로 울리면 문이 열리고 함께 울리면 새벽이 온다는 원리를 해독했다.",
        // 5장
        "훔쳐 간 지도" to "창가의 은빛 단추로 지도를 가져간 이가 길잡이 니아임을 알아냈다.",
        "니아의 값" to "니아와 사라진 동료를 찾는 대신 지도를 되돌려받기로 약속했다.",
        "세관 아래의 길" to "세관 바닥에서 군수품을 옮겨 나른 군용 비밀 통로를 찾았다.",
        "돌아오지 못한 밀수꾼" to "니아의 동료와 국경군 보급함이 함께 묻힌 자리를 찾아 냈다.",
        "정직한 거짓말" to "밀수로로 알려진 길이 세 종탑과 성채를 잇는 군용 지하로임을 밝혀냈다.",
        // 6장
        "너무 조용한 적진" to "적진이 전투가 아니라 갑작스런 대피로 비었음을 확인했다.",
        "같은 재의 흔적" to "양쪽 군대가 같은 재의 무리에게 쫓겨났다는 공통 흔적을 찾았다.",
        "마지막 경계병" to "적 경계병을 구하고 괴물이 성채에서 왔다는 증언과 군패를 얻었다.",
        "빌린 깃발" to "적의 깃발로 재의 무리를 유인해 갇힌 양군의 생존자들을 구출했다.",
        "안쪽을 향한 화살" to "화살촉의 국경사령부 표식으로 전쟁의 발화점이 국경 안쪽임을 확인했다.",
        // 7장
        "재의 파도" to "재의 무리가 빛을 먹으며 서문으로 집결하는 규칙을 파악했다.",
        "서문을 지킨 이름들" to "로웬과 생존자들이 서문을 버티는 사이 무너진 종탑으로 통하는 길을 열었다.",
        "금 간 종" to "금 간 종에 한 번의 울림만 남았음을 알고 마지막 시각을 정했다.",
        "첫 번째 울림" to "금 간 종을 울려 재의 무리를 멈추고 성벽의 사람들을 구했다.",
        "새벽 한 조각" to "성벽을 지켰지만 나머지 두 종이 성채로 옮겨지고 있음을 보았다.",
        // 8장
        "같은 날의 명령서" to "서로 모순된 두 명령서가 같은 인장으로 발행됐음을 확인했다.",
        "다른 잉크" to "철수 명령서의 잉크에만 우물의 검은 재가 섞여 있음을 찾았다.",
        "사라진 서기관" to "기록실 벽에서 서기관을 구하고 베른이 명령을 내렸다는 증언을 확보했다.",
        "보호라는 변명" to "베른의 기록에서 국경을 지킨다는 명분과 삭제된 피해 계산을 찾았다.",
        "성채로 향한 이름" to "모든 조작 명령이 베른에게 닿으며 그가 성채에서 문을 열려 함을 알았다.",
        // 9장
        "장례 행렬" to "관처럼 꾸민 수레들이 장례지가 아닌 성채로 향함을 확인했다.",
        "천 아래의 종" to "수레 안의 종들이 재와 쇠사슬로 봉인된 채 운반되고 있음을 밝혀냈다.",
        "끊어진 추격로" to "니아가 연 밀수로로 끊어진 다리를 우회해 행렬 앞을 막았다.",
        "두 번째 종혀" to "전복된 수레에서 두 번째 종혀를 되찾았지만 마지막 수레를 놓쳤다.",
        "한 번 울린 흔적" to "성채에서 한 번 울린 종이 하늘의 남은 빛을 껐다는 결과를 목격했다.",
        // 10장
        "닫히지 않는 문" to "성채 정문 대신 안쪽에서 열린 지하로를 통해 성채에 진입했다.",
        "빈 병영" to "병사들이 도망친 것이 아니라 종소리에 이끌려 깊은 홀로 갔음을 알아냈다.",
        "베른의 고백" to "베른이 왕도의 지원 거절 후 잠든 파수자를 깨우려 했다는 고백을 들었다.",
        "잘못된 수호자" to "잠든 존재가 수호자가 아니라 국경을 재로 만든 왕이라는 오르반의 경고를 확인했다.",
        "마지막 종혀" to "베른이 마지막 종혀를 끼워 재의 왕을 깨울 준비를 끝냈다.",
        // 11장
        "꺼진 하늘" to "세 번째 종소리와 함께 새벽이 사라지고 재의 왕이 깨어났다.",
        "되돌아온 길들" to "여정 동안 열어 둔 수로·지하로·성벽길을 하나의 피난로로 연결했다.",
        "세 종의 답" to "오르반이 두 종소리를 한 박자로 묶어 {hero}가 마지막 종에 닿을 틈을 만들었다.",
        "재의 왕" to "{hero}가 재의 왕 갑옷을 갈라 그 안의 잊힌 국경 사람들의 이름을 해방했다.",
        "베른의 마지막 명령" to "베른이 문을 붙들고 모든 이름을 남긴 첫 철수 명령으로 사람들을 살렸다.",
        // 12장
        "세 개의 종혀" to "흩어진 세 종혀를 모으고 오르반이 상처 난 세 종의 음을 맞춰 냈다.",
        "세 탑의 불빛" to "로웬·니아·{hero}가 성벽·지하로·성채 종탑에서 동시 울림을 준비했다.",
        "함께 울린 종" to "세 종을 함께 울려 재를 땅으로 돌려보내고 재의 문을 닫았다.",
        "돌아온 새벽" to "물 긋는 소리와 문 여는 소리가 돌아오며 국경의 온전한 아침이 시작됐다.",
        "유리로 된 잎" to "베른의 상자에서 유리 잎과 북쪽 숲 지도를 발견해 다음 모험의 단서로 보존했다.",
        // Repeatable epilogues
        "도착한 소식" to "국경 사람들의 소식에서 다시 살펴야 할 일과 길을 정했다.",
        "남겨진 흔적" to "오늘 생긴 흔적을 따라 위협의 발생지와 사라진 사람의 행선을 찾았다.",
        "길 위의 조우" to "{minorEnemy}을 몰아내 지나는 사람들이 다시 길을 이용할 수 있게 했다.",
        "끝내야 할 작은 일" to "남은 잔해와 위험을 정리해 그 일이 다시 사람을 해치지 않게 했다.",
        "다시 켜진 불빛" to "불빛과 일상이 돌아온 길을 확인하고 후일담 하나를 마무리했다.",
    )

    val mainTales: List<AdventureTaleDefinition> = listOf(
        chapter(
            1,
            "돌아오지 않은 순찰대",
            "성벽 안쪽에서 시작된 발자국",
            "동이 텄는데 순찰대의 침상은 차갑고, 식지 않은 등불만 주인을 기다렸다.",
            "{hero}는 로웬과 순찰대의 생존자를 구했다. 순찰대를 괴물에게 몰아넣은 자는 국경 안에 있었고, 로웬의 지도는 폐쇄된 우물을 가리켰다.",
            "남은 단서: 폐쇄된 우물과 세 개의 검은 원.",
            act("empty_barracks", "빈 막사", "동이 텄는데 순찰대의 침상은 차갑고, 식지 않은 등불만 주인을 기다렸다."),
            act("inward_tracks", "거꾸로 난 발자국", "{hero}는 국경 밖이 아니라 성벽 안쪽에서 시작된 발자국을 찾아냈다."),
            act("broken_spear", "부러진 창", "창날에 괴물이 아닌 국경군의 칼자국이 남았다."),
            act("survivor_voice", "살아남은 목소리", "재더미 아래서 구한 로웬은 누군가 순찰대를 괴물에게 몰아넣었다고 말했다."),
            act("ash_map", "재로 그린 지도", "로웬이 쥔 지도에는 폐쇄된 우물과 세 개의 검은 원이 재로 표시돼 있었다."),
        ),
        chapter(
            2,
            "재가 내리는 우물",
            "물 아래에서 울린 첫 번째 종소리",
            "마을 우물에서 물 대신 미지근한 재가 솟자 닫힌 창문마다 빈 물통이 늘어섰다.",
            "{hero}는 우물 아래 수로와 버려진 주조장을 지나 첫 번째 종혀를 찾았다. 종혀에는 순찰대 지도와 같은 검은 원과 오르반의 이름이 새겨져 있었다.",
            "남은 단서: 첫 번째 종혀와 종지기 오르반.",
            act("black_water", "검어진 물", "마을 우물에서 물 대신 미지근한 재가 솟자 닫힌 창문마다 빈 물통이 늘어섰다."),
            act("bell_below", "우물 아래의 종소리", "밧줄을 내릴 때마다 물 밑에서 한 번도 울린 적 없는 종소리가 답했다."),
            act("locked_channel", "잠긴 수로", "{hero}는 오래된 수로를 막은 {minorEnemy}을 몰아내고 벽 너머의 바람을 들었다."),
            act("abandoned_foundry", "버려진 주조장", "수로 끝 주조장에는 종을 만들던 틀과 억지로 뜯어낸 청동 조각이 남아 있었다."),
            act("first_clapper", "첫 번째 종혀", "진흙 속 종혀에는 순찰대 지도와 같은 검은 원, 그리고 오르반이라는 이름이 새겨져 있었다."),
        ),
        chapter(
            3,
            "종이 새가 물어온 이름",
            "죽은 자에게 도착한 다음 날의 명령",
            "비에 젖지 않는 종이 새가 날아와 {hero}의 발치에서 스스로 펼쳐졌다.",
            "죽은 순찰대원에게 다시 출정하라는 명령을 지킨 편지는 종지기 오르반의 이름을 전했다. 새벽은 아직 죽지 않았다.",
            "다음 목적지: 종지기 오르반이 숨은 문 없는 예배당.",
            act("paper_bird", "재 속의 종이 새", "비에 젖지 않는 종이 새가 날아와 {hero}의 발치에서 스스로 펼쳐졌다."),
            act("erased_recipient", "지워진 수신인", "편지의 수신인은 칼로 긁혀 있었지만 뒷장에는 로웬의 계급장이 눌려 있었다."),
            act("dead_order", "죽은 자의 명령", "공문은 실종된 순찰대원에게 출정 다음 날 다시 출정하라고 명령하고 있었다."),
            act("hunted_messenger", "쫓기는 전령", "{hero}는 편지를 되찾으려는 자들을 따돌리고 마지막 봉인까지 지켜냈다."),
            act("bell_keeper", "종지기 오르반", "봉인 아래에는 한 문장만 남았다. ‘종지기 오르반을 찾아라. 새벽은 아직 죽지 않았다.’"),
        ),
        chapter(
            4,
            "문 없는 예배당",
            "세 새벽종에 감춰진 진짜 역할",
            "지도 속 예배당에는 창도 문도 없었고, 벽에는 손바닥만 한 홈 하나만 패여 있었다.",
            "오르반은 세 새벽종이 무기가 아니라 재의 문을 잠그는 열쇠라고 밝혔다. 세 종을 따로 울리면 문이 열리고 함께 울리면 새벽이 온다.",
            "남은 문제: 도둑맞은 세 종혀를 되찾아야 한다.",
            act("wall_chapel", "벽뿐인 예배당", "지도 속 예배당에는 창도 문도 없었고, 벽에는 손바닥만 한 홈 하나만 패여 있었다."),
            act("clapper_door", "종혀가 여는 길", "첫 번째 종혀를 홈에 대자 벽이 갈라지며 오래 숨겨진 계단이 모습을 드러냈다."),
            act("three_dawn_bells", "세 개의 새벽종", "지하 벽화는 세 종이 국경을 지키는 무기가 아니라 재의 문을 잠그는 열쇠임을 보여주었다."),
            act("silent_hymn", "침묵의 찬가", "오르반은 소리 없는 찬가를 손끝으로 짚으며 누군가 종혀를 모두 훔쳤다고 털어놓았다."),
            act("forbidden_bell", "울리면 안 되는 종", "마지막 벽에는 경고가 새겨져 있었다. ‘세 종을 따로 울리면 문이 열리고, 함께 울리면 새벽이 온다.’"),
        ),
        chapter(
            5,
            "밀수꾼의 정직한 지도",
            "니아가 훔친 길과 돌려준 진실",
            "예배당을 나온 밤, 지도는 사라졌고 창가에는 은빛 단추 하나만 남았다.",
            "{hero}와 길잡이 니아는 사라진 밀수꾼과 국경군 보급함을 함께 찾아냈다. 밀수로라 불린 길은 세 종탑과 성채를 잇는 군용 지하로였다.",
            "열린 길: 적진 아래로 이어지는 군용 지하로.",
            act("stolen_map", "훔쳐 간 지도", "예배당을 나온 밤, 지도는 사라졌고 창가에는 은빛 단추 하나만 남았다."),
            act("nia_price", "니아의 값", "길잡이 니아는 돈 대신 사라진 동료 한 명을 찾아 달라며 지도를 돌려주겠다고 했다."),
            act("customs_tunnel", "세관 아래의 길", "{hero}는 세관 바닥의 밀도를 두드려 군수품이 오간 비밀 통로를 찾아냈다."),
            act("lost_smuggler", "돌아오지 못한 밀수꾼", "통로 끝에는 니아의 동료와 국경군 보급함이 나란히 묻혀 있었다."),
            act("honest_lie", "정직한 거짓말", "니아의 지도는 밀수로가 아니라 세 종탑과 성채를 잇는 군용 지하로를 가리켰다."),
        ),
        chapter(
            6,
            "적의 깃발 아래",
            "두 군대를 쫓은 하나의 적",
            "국경 밖 적진에는 깃발만 펄럭였고, 식탁과 화살통은 그대로 남아 있었다.",
            "양쪽 군대는 같은 재의 무리에게 쫓기고 있었다. 회수한 화살촉의 국경사령부 표식은 전쟁이 밖이 아니라 안에서 시작됐음을 드러냈다.",
            "남은 경고: 재의 무리가 잠들지 않는 성벽으로 향한다.",
            act("silent_camp", "너무 조용한 적진", "국경 밖 적진에는 깃발만 펄럭였고, 식탁과 화살통은 그대로 남아 있었다."),
            act("same_ash", "같은 재의 흔적", "빈 천막마다 순찰대에서 본 검은 재가 쌓여 있어 양쪽 군대가 같은 적에게 쫓겼음을 드러냈다."),
            act("last_guard", "마지막 경계병", "살아남은 적 경계병은 ‘괴물은 성채에서 왔다’는 말과 함께 부러진 군패를 건넸다."),
            act("borrowed_flag", "빌린 깃발", "{hero}는 적의 깃발을 들어 재의 무리를 유인하고 갇힌 생존자들을 빠져나오게 했다."),
            act("inward_arrow", "안쪽을 향한 화살", "회수한 화살촉에는 국경사령부의 표식이 있었다. 전쟁은 밖이 아니라 안에서 시작되고 있었다."),
        ),
        chapter(
            7,
            "잠들지 않는 성벽",
            "금 간 종이 지켜낸 새벽 한 조각",
            "밤이 오자 재에서 태어난 것들이 성벽 아래로 밀려와 횃불의 빛을 하나씩 삼켰다.",
            "로웬과 생존자들이 서문을 지키는 사이 오르반은 금 간 종의 마지막 울림을 꺼냈다. 성벽은 살아남았지만 다른 두 종은 성채로 옮겨지고 있었다.",
            "남은 시간: 성채로 가는 두 종을 추격해야 한다.",
            act("ash_wave", "재의 파도", "밤이 오자 재에서 태어난 것들이 성벽 아래로 밀려와 횃불의 빛을 하나씩 삼켰다."),
            act("west_gate_names", "서문을 지킨 이름들", "구해 둔 로웬과 생존자들이 서문을 버티는 동안 {hero}는 무너진 종탑으로 향했다."),
            act("cracked_bell", "금 간 종", "오르반은 금 간 종도 한 번은 울릴 수 있지만, 그 한 번이 종의 마지막이라고 말했다."),
            act("first_ring", "첫 번째 울림", "종소리가 퍼지자 재의 무리는 멈췄고, 사람들은 서로의 얼굴을 다시 알아보았다."),
            act("piece_of_dawn", "새벽 한 조각", "성벽은 살아남았지만 하늘에는 손바닥만 한 빛만 남았다. 다른 두 종이 성채로 옮겨지고 있었다."),
        ),
        chapter(
            8,
            "서로 다른 두 개의 명령",
            "같은 인장이 찍힌 상반된 명령서",
            "로웬은 철수 명령을, 오르반은 종을 옮기라는 명령을 받았고 두 문서에는 같은 인장이 찍혀 있었다.",
            "검은 재가 섞인 명령과 숨어 있던 서기관의 증언은 국경사령관 베른을 가리켰다. 베른은 국경을 지키려 했다고 썼지만 실패의 대가는 남기지 않았다.",
            "모든 길은 베른과 재의 성채로 향한다.",
            act("same_day_orders", "같은 날의 명령서", "로웬은 철수 명령을, 오르반은 종을 옮기라는 명령을 받았고 두 문서에는 같은 인장이 찍혀 있었다."),
            act("different_ink", "다른 잉크", "{hero}는 한 문서의 잉크에만 우물의 검은 재가 섞였음을 알아냈다."),
            act("missing_scribe", "사라진 서기관", "명령을 베낀 서기관은 기록실 벽 안에 숨어 있었고, 사령관 베른의 지시였다고 증언했다."),
            act("protection_excuse", "보호라는 변명", "베른은 국경을 지키기 위해 종의 힘이 필요하다고 적었다. 실패의 대가는 한 줄도 없었다."),
            act("name_to_citadel", "성채로 향한 이름", "모든 길과 명령이 베른에게 닿았다. 그러나 마지막 문서에는 ‘문을 열지 않으면 국경은 버려진다’고 적혀 있었다."),
        ),
        chapter(
            9,
            "검은 종의 행렬",
            "재로 채워진 종과 성채로 사라진 마지막 수레",
            "검은 천을 두른 수레들이 관처럼 보였지만, 바퀴 자국은 성채 쪽으로 깊게 파여 있었다.",
            "니아가 열어 준 밀수로를 통해 {hero}는 두 번째 종혀를 되찾았다. 하지만 마지막 수레는 성채로 들어갔고, 한 번 울린 종이 하늘의 빛을 껐다.",
            "마지막 종혀는 재의 성채 안에 있다.",
            act("funeral_procession", "장례 행렬", "검은 천을 두른 수레들이 관처럼 보였지만, 바퀴 자국은 성채 쪽으로 깊게 파여 있었다."),
            act("bells_under_cloth", "천 아래의 종", "수레마다 종 하나가 쇠사슬에 묶여 있었고, 울리지 못하도록 내부를 재로 채워 두었다."),
            act("broken_chase", "끊어진 추격로", "추격대가 다리를 끊자 니아는 밀수로의 출구를 열어 행렬 앞을 가로질렀다."),
            act("second_clapper", "두 번째 종혀", "{hero}는 전복된 수레에서 두 번째 종혀를 되찾았지만 마지막 수레는 성채 문을 통과했다."),
            act("once_rung", "한 번 울린 흔적", "멀리서 낮은 울림이 한 번 번졌다. 하늘의 작은 빛이 꺼지고 재가 위로 떨어지기 시작했다."),
        ),
        chapter(
            10,
            "재의 성채",
            "국경을 지키려 괴물을 왕으로 삼은 베른",
            "성채의 문은 굳게 닫혀 있었지만 지하로의 문은 안쪽에서 급히 열어 둔 채였다.",
            "베른은 왕도가 버린 국경을 지키려고 잠든 파수자를 깨우려 했다. 오르반의 경고에도 마지막 종혀를 끼우며, 버려진 국경에는 괴물이라도 왕이 필요하다고 말했다.",
            "세 번째 종이 울리기 전에 재의 왕을 막아야 한다.",
            act("open_underground", "닫히지 않는 문", "성채의 문은 굳게 닫혀 있었지만 지하로의 문은 안쪽에서 급히 열어 둔 채였다."),
            act("empty_barracks", "빈 병영", "병사들은 도망친 것이 아니라 종소리를 따라 가장 깊은 홀로 걸어간 흔적만 남겼다."),
            act("bern_confession", "베른의 고백", "베른은 왕도에서 지원을 거절당하자 잠든 파수자를 깨워 국경을 지키려 했다고 말했다."),
            act("wrong_guardian", "잘못된 수호자", "오르반은 종 아래 잠든 존재가 수호자가 아니라 오래전 국경을 재로 만든 왕이라고 밝혔다."),
            act("last_clapper", "마지막 종혀", "베른은 경고를 듣고도 마지막 종혀를 끼웠다. ‘버려진 국경에는 괴물이라도 왕이 필요하다.’"),
        ),
        chapter(
            11,
            "새벽을 삼킨 왕",
            "잊힌 이름들로 비어 있던 왕의 갑옷",
            "세 번째 종이 울리자 해가 사라지고 재의 왕이 수천 개의 발자국 소리와 함께 일어났다.",
            "국경에서 이어 온 모든 길이 사람들의 퇴로가 되었다. {hero}가 재의 왕을 깨뜨리자 베른은 처음으로 누구의 이름도 지우지 않은 철수 명령을 내렸다.",
            "마지막 과업: 세 종을 함께 울려 재의 문을 닫는다.",
            act("dark_sky", "꺼진 하늘", "세 번째 종이 울리자 해가 사라지고 재의 왕이 수천 개의 발자국 소리와 함께 일어났다."),
            act("returned_roads", "되돌아온 길들", "우물의 수로, 니아의 지하로, 로웬이 지킨 성벽이 하나의 길이 되어 사람들을 피신시켰다."),
            act("three_answers", "세 종의 답", "오르반은 흩어진 두 종소리를 한 박자로 묶었고 {hero}는 마지막 종까지 닿을 틈을 얻었다."),
            act("ash_king", "재의 왕", "{hero}의 일격에 왕의 갑옷이 갈라지자 그 안에는 몸이 아니라 국경에서 잊힌 이름들이 소용돌이쳤다."),
            act("last_order", "베른의 마지막 명령", "베른은 무너지는 문을 붙들며 처음으로 철수 명령을 내렸다. 이번에는 누구의 이름도 지우지 않았다."),
        ),
        chapter(
            12,
            "국경에 다시 뜬 해",
            "세 개의 상처 난 종이 함께 울린 아침",
            "사람들은 흩어진 종혀를 모았고, 오르반은 서로 다른 상처를 가진 세 종의 음을 맞췄다.",
            "세 종이 함께 울리자 국경의 아침은 물 긷는 소리와 문 여는 소리로 돌아왔다. 베른이 남긴 유리 잎과 북쪽 숲의 지도는 끝난 이야기 너머의 길을 가리켰다.",
            "후일담: 국경의 새 삶과 북쪽에서 온 낯선 징조가 이어진다.",
            act("three_clappers", "세 개의 종혀", "사람들은 흩어진 종혀를 모았고, 오르반은 서로 다른 상처를 가진 세 종의 음을 맞췄다."),
            act("three_towers", "세 탑의 불빛", "로웬은 성벽, 니아는 지하로, {hero}는 성채의 종탑에 올라 같은 시각을 기다렸다."),
            act("bells_together", "함께 울린 종", "세 종이 함께 울리자 재는 처음으로 땅을 향해 내렸고, 닫힌 문에는 새 금이 아닌 빛이 번졌다."),
            act("dawn_returned", "돌아온 새벽", "국경의 아침은 승리의 함성보다 물 긷는 소리와 문 여는 소리로 먼저 돌아왔다."),
            act("glass_leaf", "유리로 된 잎", "베른이 남긴 상자에는 유리로 된 잎 하나와 북쪽 숲의 지도가 있었다. 끝난 이야기는 다음 길을 가리켰다."),
        ),
    ) + NorthernGlassTaleCatalog.tales

    val epilogues: List<AdventureTaleDefinition> = listOf(
        epilogue(
            1,
            "rebuild",
            "다시 열린 국경길",
            "국경과 라움을 잇는 공동 보급로",
            "유리 숲에 봄이 돌아온 뒤에도 국경과 라움을 잇는 보급 수레가 밤마다 사라졌다.",
            "{hero}는 {minorEnemy}의 둥지를 치우고 국경과 라움의 끊긴 길을 다시 이었다. 두 지역의 첫 공동 보급대가 같은 표지석을 지나갔다.",
            "남은 소식: 공동 순찰로의 오래된 종이 북방 구조 신호를 울렸다.",
            repeatAct("missing_manifest", "끊긴 보급표", "도착해야 할 보급 수레 세 대가 장부에서만 국경을 건넜다.", "장부의 빈 시각을 맞춰 수레가 사라진 밤과 길목을 특정했다."),
            repeatAct("wheel_tracks", "바퀴자국", "{hero}는 굳은 진흙 아래 겹쳐진 오래된 바퀴자국을 따라갔다.", "덧씌운 자국을 걷어 내고 수레가 끌려간 샛길을 찾아냈다."),
            repeatAct("road_nest", "길목의 둥지", "{minorEnemy}이 무너진 표지석 아래 둥지를 틀어 길을 막고 있었다.", "둥지를 치우고 빼앗긴 보급품을 온전히 되찾았다."),
            repeatAct("broken_bridge", "무너진 다리", "싸움이 끝난 뒤에도 부서진 다리는 사람들을 국경 밖에 세워 두었다.", "병사와 상인이 함께 건널 수 있도록 임시 다리를 놓았다."),
            repeatAct("first_cart", "첫 수레", "새로 놓인 길 앞에서 첫 수레의 마부가 오래 망설였다.", "첫 보급 수레가 성문을 통과하고 밤의 등불이 다시 켜졌다."),
        ),
        epilogue(
            2,
            "old_bell",
            "한 번 늦게 울린 종",
            "북방에 닿지 못한 옛 구조 신호",
            "공동 순찰로의 폐허 초소에서 아무도 당기지 않은 작은 종이 북쪽을 향해 한 번 울렸다.",
            "{hero}는 {minorEnemy}을 몰아내고 국경 전쟁 때 북방에 닿지 못한 구조 표식을 마엘과 로웬에게 전했다. 오래된 요청이 마침내 기록됐다.",
            "남은 소식: 기억나무의 순찰로가 지난 순환과 다른 방향으로 열렸다.",
            repeatAct("empty_post", "빈 초소", "폐허의 먼지는 두꺼웠지만 종 아래의 발자국만은 새것이었다.", "빈 초소를 살펴 종을 울린 이가 사람이 아님을 확인했다."),
            repeatAct("late_bell", "늦은 종소리", "작은 종은 해 질 녘마다 같은 방향을 향해 한 번씩 흔들렸다.", "종 안쪽에서 오래된 순찰대의 귀환 암호를 읽어 냈다."),
            repeatAct("nameless_tag", "이름 없는 군패", "{minorEnemy}이 지키던 틈에서 이름이 닳아 없어진 군패가 나왔다.", "군패의 부대 표식으로 돌아오지 못한 병사의 마지막 임무를 찾았다."),
            repeatAct("last_return", "마지막 귀환로", "{hero}는 지도에서 지워진 초소와 성문 사이의 길을 다시 걸었다.", "묻힌 표지석을 세우고 끊겼던 귀환로를 기록에 되돌렸다."),
            repeatAct("delivered_mark", "전해진 표식", "로웬은 이름 없는 군패 앞에서 오래된 순찰 명부를 펼쳤다.", "귀환 표식을 유족에게 전하고 끝나지 않았던 임무를 마쳤다."),
        ),
        epilogue(
            3,
            "glass_leaf",
            "유리 잎이 가리킨 곳",
            "순환마다 달라지는 기억나무 순찰로",
            "봄을 되찾은 기억나무의 유리 잎이 지난 원정과 다른 공동 순찰로를 비췄다.",
            "{hero}는 {minorEnemy}의 흔적을 걷어 내고 기억나무가 새로 만든 길을 기록했다. 국경과 라움의 지도에 안전로 하나가 더해졌다.",
            "남은 소식: 공동 수로에서 재와 백야의 잔재가 함께 발견됐다.",
            repeatAct("lit_leaf", "빛을 머금은 잎", "기억나무의 유리 잎은 순환이 바뀔 때마다 조금 다른 공동 순찰로를 가리켰다.", "빛의 각도를 기록해 이번 순환에 열린 안전한 길을 계산했다."),
            repeatAct("north_marker", "북쪽 이정표", "지난 지도와 달라진 이정표에는 국경과 라움의 새 표식이 함께 새겨져 있었다.", "새 표식이 물길과 안전한 발판을 표시한다는 것을 확인했다."),
            repeatAct("glass_edge", "유리 숲 가장자리", "나무껍질이 투명하게 굳은 곳에서 {minorEnemy}의 무리가 길을 에워쌌다.", "무리를 몰아내고 유리 숲을 훼손하지 않는 통로를 열었다."),
            repeatAct("unknown_camp", "공동 야영지", "공동 순찰대의 모닥불은 꺼져 있었지만 급히 북쪽으로 이동한 기록이 남아 있었다.", "야영 기록에서 기억나무 순찰로를 가로막은 위험의 위치를 찾았다."),
            repeatAct("three_replies", "세 번의 안전 답신", "{hero}는 종탑 거울로 라움에 약속한 짧은 빛을 보냈다.", "라움에서 세 번의 불빛이 돌아와 이번 순환의 안전을 확인했다."),
        ),
        epilogue(
            4,
            "waterway",
            "물길 아래 남은 재",
            "재와 백야의 잔재가 만난 공동 수로",
            "국경과 라움의 물길이 만나는 수문에 검은 재와 흰 유리가 함께 굳어 역류를 만들었다.",
            "{hero}는 물밑의 재와 유리 뿌리를 걷어 내고 두 지역의 공동 수로를 다시 연결했다. 흐르는 물이 같은 아침을 알렸다.",
            "남은 소식: 공동묘지 아래에서 북방 실종자의 군패가 발견됐다.",
            repeatAct("dark_well", "다시 검어진 우물", "우물의 검은 띠는 번지는 대신 일정한 높이에서 멈춰 있었다.", "오염이 아니라 막힌 수로의 역류가 검은 띠를 만들었음을 확인했다."),
            repeatAct("closed_gate", "막힌 수문", "녹슨 수문 뒤에서 물보다 무거운 무언가가 문을 누르고 있었다.", "수문 장치를 되살리고 아래쪽 점검로를 열었다."),
            repeatAct("bell_dust", "물밑의 종가루", "{minorEnemy} 사이로 옛 종의 금속 가루가 강바닥처럼 쌓여 있었다.", "종가루를 거둬 물길을 막은 재 덩어리의 위치를 드러냈다."),
            repeatAct("ash_pool", "재의 웅덩이", "성채에서 흘러든 마지막 재가 지하 웅덩이에서 단단히 굳어 있었다.", "굳은 재를 부수고 갇혀 있던 물을 안전하게 흘려보냈다."),
            repeatAct("clear_channel", "맑아진 수로", "세 갈래 물길이 다시 움직였지만 어느 마을부터 열지 정해야 했다.", "가장 낮은 마을부터 차례로 수문을 열어 세 곳에 같은 아침물을 보냈다."),
        ),
        epilogue(
            5,
            "memorial",
            "이름을 되찾은 묘표",
            "국경과 북방의 공동 명부",
            "긴 비가 지나간 공동묘지에서 국경군과 북방 실종자의 묘표가 뒤섞여 드러났다.",
            "{hero}는 로웬과 마엘의 명부를 맞춰 이름 없는 묘표에 주인을 돌려주었다. 두 지역은 돌아오지 못한 사람을 하나의 기록에 남겼다.",
            "남은 소식: 국경과 라움 사이의 정기 안전 신호가 한 박자 늦었다.",
            repeatAct("exposed_markers", "비에 드러난 묘표", "흙이 씻겨 내려가자 서로 다른 부대의 묘표가 뒤섞여 나타났다.", "묘표의 재질과 새김법을 나눠 매장 시기와 부대를 구분했다."),
            repeatAct("erased_roster", "지워진 명단", "기록실 명부에는 같은 날 비어 버린 줄이 여러 장 이어졌다.", "남은 잉크 자국을 대조해 지워진 이름들의 순서를 복원했다."),
            repeatAct("patrol_keepsakes", "순찰대의 유품", "{minorEnemy}이 끌어 모은 금속 조각 사이에 군패와 편지 고리가 섞여 있었다.", "유품을 되찾아 묘표와 명부의 이름을 하나씩 연결했다."),
            repeatAct("carving_night", "이름을 새기는 밤", "돌공들은 해가 지기 전 모든 이름을 새길 수 없다고 말했다.", "성벽의 등불을 옮겨 밤새 묘표에 빠진 이름을 새겼다."),
            repeatAct("memory_lights", "기억의 등불", "새 이름 앞에 놓인 등불은 바람이 불어도 오래 꺼지지 않았다.", "유족과 순찰대가 함께 명부를 읽어 이름 없는 무덤을 남기지 않기로 했다."),
        ),
        epilogue(
            6,
            "three_lights",
            "북쪽에서 온 세 불빛",
            "국경과 라움의 정기 안전 신호",
            "라움에서 보내는 세 번의 안전 불빛이 약속한 시각보다 한 박자 늦게 나타났다.",
            "{hero}는 {minorEnemy}이 어지럽힌 신호를 바로잡고 공동 순찰대와 안전 기록을 교환했다. 열린 길은 매 순환 새로 점검됐다.",
            "다음 순환: 국경과 유리 숲 사이에 남은 길과 이름을 계속 지킨다.",
            repeatAct("first_north_light", "정시의 첫 불빛", "라움의 첫 불빛은 공동 순찰대가 출발할 시각을 알리는 정기 신호였다.", "빛의 간격을 확인해 다음 신호가 나타날 장소와 시각을 점검했다."),
            repeatAct("second_reply", "두 번째 안전 답신", "국경의 거울이 흐려져 보낸 정기 답신이 숲에서 흩어졌다.", "종탑 거울을 닦고 약속된 두 번째 안전 답신을 정확히 돌려보냈다."),
            repeatAct("beyond_tracks", "국경 너머 발자국", "{minorEnemy}의 발자국과 낯선 장화 자국이 같은 골짜기에서 엇갈렸다.", "괴물의 흔적을 걷어 내 사절이 남긴 안전한 접근로를 구분했다."),
            repeatAct("night_council", "밤의 점검 회담", "국경과 라움의 순찰대는 늦어진 신호와 새 실종자 기록을 한 줄씩 맞췄다.", "공동 명부와 안전 신호를 교환해 어긋난 순찰 구간을 찾아냈다."),
            repeatAct("open_path", "지켜 낸 길", "기억나무가 바꾼 새 길을 이번 순환에도 공동 순찰로로 남길지 점검했다.", "감시와 구조에 쓰는 공동 길로 확인하고 양쪽 표지석을 다시 세웠다."),
        ),
    )

    val all: List<AdventureTaleDefinition> = mainTales + epilogues
    val firstMain: AdventureTaleDefinition get() = mainTales.first()

    fun find(id: String): AdventureTaleDefinition? = all.firstOrNull { it.id == id }
    fun requireDefinition(id: String): AdventureTaleDefinition =
        requireNotNull(find(id)) { "Unknown tale definition: $id" }

    fun nextDefinition(current: AdventureTaleDefinition, taleSequence: Long): AdventureTaleDefinition {
        current.nextId?.let { return requireDefinition(it) }
        val index = ((taleSequence - MAIN_TALE_COUNT).coerceAtLeast(0L) % epilogues.size).toInt()
        return epilogues[index]
    }

    fun postgameRun(sequence: Long): Long =
        (sequence - MAIN_TALE_COUNT.toLong()).coerceAtLeast(1L)

    fun postgameCycle(sequence: Long): Long =
        ((postgameRun(sequence) - 1L) / epilogues.size.toLong()) + 1L

    fun variantAt(index: Int): TaleVariant = TaleVariant(MINOR_ENEMIES[index % MINOR_ENEMIES.size])
    fun variantCount(): Int = MINOR_ENEMIES.size

    internal fun mainActTarget(chapterNumber: Int, actIndex: Int): Long {
        val globalActIndex = (chapterNumber - 1).coerceAtLeast(0) * ACTS_PER_TALE +
            actIndex.coerceIn(0, ACTS_PER_TALE - 1)
        val index = globalActIndex.toLong()
        val formerTarget = 100L + 20L * index + 3L * index * index
        return scalePacingTarget(formerTarget).coerceAtMost(MAX_ACT_TARGET)
    }

    internal fun targetFor(definitionId: String, actIndex: Int): Long =
        requireDefinition(definitionId).acts[actIndex.coerceIn(0, ACTS_PER_TALE - 1)].target

    fun instantiate(
        definition: AdventureTaleDefinition,
        sequence: Long,
        heroName: String,
        heroLevel: Long,
        variant: TaleVariant,
    ): AdventureTaleState {
        fun render(template: String): String = TOKEN_REGEX.replace(template) { match ->
            when (match.groupValues[1]) {
                "hero" -> heroName
                "minorEnemy" -> variant.minorEnemy
                else -> error("Unsupported tale token: ${match.value}")
            }
        }
        val postgameCycle = postgameCycle(sequence)
        return AdventureTaleState(
            definitionId = definition.id,
            sequence = sequence,
            kind = definition.kind,
            volumeNumber = definition.volumeNumber,
            volumeTitle = when {
                definition.kind == TaleKind.EPILOGUE -> "국경 수호록"
                definition.volumeNumber == 1 -> "잿빛 국경"
                else -> "유리 숲의 백야"
            },
            chapterNumber = definition.chapterNumber,
            title = if (definition.kind == TaleKind.MAIN) {
                definition.title
            } else {
                "제${postgameCycle}순환 · ${definition.title}"
            },
            subtitle = if (definition.kind == TaleKind.MAIN) {
                definition.subtitle
            } else {
                "반복 원정 ${postgameRun(sequence)}편 · ${definition.subtitle}"
            },
            opening = render(definition.openingTemplate),
            ending = render(definition.endingTemplate),
            nextHook = render(definition.nextHookTemplate),
            variant = variant,
            acts = definition.acts.mapIndexed { index, act ->
                TaleActState(
                    id = act.id,
                    number = index + 1,
                    title = act.titleTemplate,
                    body = render(act.bodyTemplate),
                    completionBody = render(act.completionTemplate),
                    progress = 0L,
                    target = act.target,
                    rewardExperience = scalePacingTarget(
                        safeAdd(24L, safeMul(heroLevel, 8L)),
                    ),
                    rewardGold = safeAdd(12L, safeMul(heroLevel, 3L)),
                )
            }.toMutableList(),
        )
    }

    fun validationErrors(): List<String> = buildList {
        if (mainTales.size != MAIN_TALE_COUNT) add("main tale count must be $MAIN_TALE_COUNT")
        if (epilogues.isEmpty()) add("epilogue pool must not be empty")
        val duplicateIds = all.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) add("duplicate tale ids: $duplicateIds")
        all.forEach { tale ->
            if (tale.title.isBlank() || tale.subtitle.isBlank()) add("${tale.id}: blank title")
            if (tale.openingTemplate.isBlank() || tale.endingTemplate.isBlank()) add("${tale.id}: blank narrative")
            if (tale.acts.size != ACTS_PER_TALE) add("${tale.id}: acts must be $ACTS_PER_TALE")
            if (tale.acts.map { it.id }.distinct().size != tale.acts.size) add("${tale.id}: duplicate act id")
            tale.acts.forEach { act ->
                if (
                    act.titleTemplate.isBlank() || act.bodyTemplate.isBlank() ||
                    act.completionTemplate.isBlank()
                ) {
                    add("${tale.id}:${act.id}: blank act")
                }
                if (act.bodyTemplate == act.completionTemplate) {
                    add("${tale.id}:${act.id}: completion repeats body")
                }
                if (act.target <= 0L) add("${tale.id}:${act.id}: non-positive target")
            }
            templates(tale).forEach { template ->
                TOKEN_REGEX.findAll(template).forEach { match ->
                    if (match.groupValues[1] !in ALLOWED_TOKENS) add("${tale.id}: unsupported ${match.value}")
                }
            }
            tale.nextId?.let { if (find(it) == null) add("${tale.id}: missing next $it") }
        }
        if (mainTales.map { it.id } != MAIN_IDS) add("main tale ids or order are invalid")
        mainTales.forEachIndexed { index, tale ->
            val expected = MAIN_IDS.getOrNull(index + 1)
            if (tale.nextId != expected) add("${tale.id}: invalid next id")
        }
        addAll(QuestMonsterCatalog.validationErrors(all.map { it.id }.toSet()))
    }

    private fun chapter(
        number: Int,
        title: String,
        subtitle: String,
        opening: String,
        ending: String,
        hook: String,
        vararg acts: TaleActDefinition,
    ) = AdventureTaleDefinition(
        id = MAIN_IDS[number - 1],
        kind = TaleKind.MAIN,
        volumeNumber = 1,
        chapterNumber = number,
        title = title,
        subtitle = subtitle,
        openingTemplate = opening,
        endingTemplate = ending,
        nextHookTemplate = hook,
        acts = acts.mapIndexed { index, act -> act.copy(target = mainActTarget(number, index)) },
        nextId = MAIN_IDS.getOrNull(number),
    )

    private fun epilogue(
        number: Int,
        suffix: String,
        title: String,
        subtitle: String,
        opening: String,
        ending: String,
        hook: String,
        vararg acts: TaleActDefinition,
    ) = AdventureTaleDefinition(
        id = "ash_border.epilogue_$suffix",
        kind = TaleKind.EPILOGUE,
        volumeNumber = 3,
        chapterNumber = number,
        title = title,
        subtitle = subtitle,
        openingTemplate = opening,
        endingTemplate = ending,
        nextHookTemplate = hook,
        acts = acts.map { act -> act.copy(target = REPEAT_ACT_TARGET) },
        nextId = null,
    )

    private fun repeatAct(
        id: String,
        title: String,
        body: String,
        completion: String,
    ) = TaleActDefinition(
        id = id,
        titleTemplate = title,
        bodyTemplate = body,
        completionTemplate = completion,
        target = 5L,
    )

    private fun act(id: String, title: String, body: String) = TaleActDefinition(
        id = id,
        titleTemplate = title,
        bodyTemplate = body,
        completionTemplate = requireNotNull(COMPLETION_BY_TITLE[title]) {
            "Missing authored completion for $title"
        },
        target = 5L,
    )

    private fun templates(tale: AdventureTaleDefinition) = listOf(
        tale.openingTemplate,
        tale.endingTemplate,
        tale.nextHookTemplate,
    ) + tale.acts.flatMap { listOf(it.bodyTemplate, it.completionTemplate) }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun safeMul(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    /** Keeps six-month progression after a normal hunt falls from 18 to 10 seconds. */
    private fun scalePacingTarget(value: Long): Long {
        if (value <= 0L) return 0L
        val whole = safeMul(value / 20L, 29L)
        val remainder = safeAdd(safeMul(value % 20L, 29L), 10L) / 20L
        return safeAdd(whole, remainder)
    }

    private val TOKEN_REGEX = Regex("\\{([A-Za-z]+)\\}")
    private val ALLOWED_TOKENS = setOf("hero", "minorEnemy")
}
