package com.nullplaying.engine

import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.MonsterGrade

/** Authored chance meetings. Selection is local; every named counterpart comes from a server roster snapshot. */
object AdventureRelationshipCatalog {
    private enum class ApproachSet { ROAD, AID, MYSTERY, TRADE, CAMP, CONTEST, HUNT, RETURN, TOWN, DUEL }

    private data class Seed(
        val id: String,
        val koTitle: String,
        val enTitle: String,
        val jaTitle: String,
        val koScene: String,
        val enScene: String,
        val jaScene: String,
        val koSuccess: String,
        val enSuccess: String,
        val jaSuccess: String,
        val set: ApproachSet,
        val reunionRule: AdventureRelationshipReunionRule = AdventureRelationshipReunionRule.ANY,
        val allowedTiers: Set<AdventureRelationshipTier> = AdventureRelationshipTier.entries.toSet(),
        val battleRule: AdventureRelationshipBattleRule? = null,
    )

    private data class OutcomeCopies(
        val partialKo: String,
        val failureKo: String,
        val partialEn: String,
        val failureEn: String,
        val partialJa: String,
        val failureJa: String,
    )

    private fun t(ko: String, en: String, ja: String) = AdventureText(ko, en, ja)
    private fun a(
        id: String,
        ko: String,
        en: String,
        ja: String,
        primary: AdventureEventStat,
        secondary: AdventureEventStat,
        affinity: Int,
        vararg signals: AdventureBehaviorSignal,
    ) = AdventureRelationshipApproach(id, t(ko, en, ja), primary, secondary, affinity, signals.toSet())

    private val approaches = mapOf(
        ApproachSet.ROAD to listOf(
            a("read_signs", "흔적을 함께 읽는다", "Read the signs together", "痕跡を一緒に読む", AdventureEventStat.INT, AdventureEventStat.WIS, 4, AdventureBehaviorSignal.COOPERATE, AdventureBehaviorSignal.CHECK_SAFETY),
            a("scout_ahead", "앞길을 먼저 살핀다", "Scout the way ahead", "先の道を偵察する", AdventureEventStat.DEX, AdventureEventStat.CON, 2, AdventureBehaviorSignal.ACT_ALONE, AdventureBehaviorSignal.WILDERNESS_COMFORT),
            a("choose_boldly", "빠른 길을 단호히 고른다", "Choose the faster path", "速い道を迷わず選ぶ", AdventureEventStat.STR, AdventureEventStat.CHA, -2, AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.SPEAK_DIRECT)),
        ApproachSet.AID to listOf(
            a("offer_help", "필요한 도움부터 건넨다", "Offer the needed help", "必要な助けを差し出す", AdventureEventStat.WIS, AdventureEventStat.CHA, 5, AdventureBehaviorSignal.HELP_OTHERS, AdventureBehaviorSignal.SPEAK_GENTLE),
            a("work_together", "역할을 나눠 함께 움직인다", "Split the work", "役割を分けて動く", AdventureEventStat.STR, AdventureEventStat.DEX, 4, AdventureBehaviorSignal.COOPERATE, AdventureBehaviorSignal.HELP_OTHERS),
            a("secure_route", "내 여정부터 안전하게 지킨다", "Secure the journey first", "自分の旅を先に守る", AdventureEventStat.CON, AdventureEventStat.INT, -2, AdventureBehaviorSignal.SELF_PRIORITY, AdventureBehaviorSignal.CHECK_SAFETY)),
        ApproachSet.MYSTERY to listOf(
            a("study_clue", "단서를 차분히 맞춘다", "Piece the clues together", "手がかりを組み合わせる", AdventureEventStat.INT, AdventureEventStat.WIS, 4, AdventureBehaviorSignal.SEEK_NOVELTY, AdventureBehaviorSignal.COOPERATE),
            a("test_clue", "위험을 감수하고 직접 시험한다", "Test the clue directly", "危険を承知で試す", AdventureEventStat.DEX, AdventureEventStat.CON, 0, AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.PERSIST),
            a("leave_clue", "확실한 단서만 챙겨 물러난다", "Keep only the sure clue", "確かな手がかりだけ持ち帰る", AdventureEventStat.WIS, AdventureEventStat.CHA, 1, AdventureBehaviorSignal.CHECK_SAFETY, AdventureBehaviorSignal.MOVE_ON)),
        ApproachSet.TRADE to listOf(
            a("negotiate", "조건을 솔직하게 맞춘다", "Set clear terms", "条件を率直に合わせる", AdventureEventStat.CHA, AdventureEventStat.INT, 2, AdventureBehaviorSignal.SPEAK_DIRECT, AdventureBehaviorSignal.TOWN_COMFORT),
            a("inspect", "물건과 사정을 꼼꼼히 살핀다", "Inspect goods and motives", "品と事情を確かめる", AdventureEventStat.WIS, AdventureEventStat.DEX, 1, AdventureBehaviorSignal.CHECK_SAFETY, AdventureBehaviorSignal.INSPECT_NEW_GEAR),
            a("share_fairly", "서로 납득할 몫을 나눈다", "Divide it fairly", "納得できるよう分ける", AdventureEventStat.CHA, AdventureEventStat.WIS, 5, AdventureBehaviorSignal.COOPERATE, AdventureBehaviorSignal.SPEAK_GENTLE)),
        ApproachSet.CAMP to listOf(
            a("share_story", "작은 이야기를 먼저 건넨다", "Share a small story", "小さな話を先にする", AdventureEventStat.CHA, AdventureEventStat.WIS, 5, AdventureBehaviorSignal.SPEAK_GENTLE, AdventureBehaviorSignal.COOPERATE),
            a("tend_camp", "말없이 야영 일을 돕는다", "Help tend the camp", "黙って野営を手伝う", AdventureEventStat.CON, AdventureEventStat.DEX, 3, AdventureBehaviorSignal.HELP_OTHERS, AdventureBehaviorSignal.WILDERNESS_COMFORT),
            a("rest_alone", "거리를 두고 조용히 쉰다", "Rest at a distance", "距離を置いて休む", AdventureEventStat.INT, AdventureEventStat.CON, -1, AdventureBehaviorSignal.ACT_ALONE, AdventureBehaviorSignal.SELF_PRIORITY)),
        ApproachSet.CONTEST to listOf(
            a("accept_challenge", "정면으로 도전을 받아들인다", "Accept the challenge", "正面から挑戦を受ける", AdventureEventStat.STR, AdventureEventStat.DEX, 1, AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.SPEAK_DIRECT),
            a("plan_turn", "상대의 수를 읽고 움직인다", "Read the other move", "相手の手を読んで動く", AdventureEventStat.INT, AdventureEventStat.WIS, 2, AdventureBehaviorSignal.CHECK_SAFETY, AdventureBehaviorSignal.PERSIST),
            a("keep_friendly", "승부보다 분위기를 살핀다", "Keep the contest friendly", "勝負より空気を大切にする", AdventureEventStat.CHA, AdventureEventStat.CON, 5, AdventureBehaviorSignal.SPEAK_GENTLE, AdventureBehaviorSignal.COOPERATE)),
        ApproachSet.HUNT to listOf(
            a("track_together", "흔적을 나눠 추적한다", "Track it together", "痕跡を分けて追う", AdventureEventStat.DEX, AdventureEventStat.WIS, 4, AdventureBehaviorSignal.COOPERATE, AdventureBehaviorSignal.WILDERNESS_COMFORT),
            a("set_lure", "과감한 미끼를 놓는다", "Set a daring lure", "大胆な囮を置く", AdventureEventStat.INT, AdventureEventStat.CHA, 0, AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.PERSIST),
            a("guard_partner", "상대의 빈틈을 지킨다", "Guard the other adventurer", "相手の隙を守る", AdventureEventStat.CON, AdventureEventStat.STR, 5, AdventureBehaviorSignal.HELP_OTHERS, AdventureBehaviorSignal.CHECK_SAFETY)),
        ApproachSet.RETURN to listOf(
            a("hurry_together", "보폭을 맞춰 귀환을 재촉한다", "Hurry home together", "歩調を合わせて帰還を急ぐ", AdventureEventStat.DEX, AdventureEventStat.CON, 3, AdventureBehaviorSignal.HURRY_HOME, AdventureBehaviorSignal.COOPERATE),
            a("search_return", "돌아가는 길의 단서를 살핀다", "Search the way home", "帰り道の手がかりを探す", AdventureEventStat.WIS, AdventureEventStat.INT, 2, AdventureBehaviorSignal.LINGER_RETURN, AdventureBehaviorSignal.SEEK_NOVELTY),
            a("carry_load", "상대의 짐을 나눠 든다", "Share the load", "相手の荷物を分けて持つ", AdventureEventStat.STR, AdventureEventStat.CHA, 5, AdventureBehaviorSignal.HELP_OTHERS, AdventureBehaviorSignal.PREPARE_THOROUGHLY)),
        ApproachSet.TOWN to listOf(
            a("mediate", "말을 고르게 다듬어 중재한다", "Mediate carefully", "言葉を選んで仲裁する", AdventureEventStat.CHA, AdventureEventStat.WIS, 5, AdventureBehaviorSignal.SPEAK_GENTLE, AdventureBehaviorSignal.TOWN_COMFORT),
            a("investigate", "사실부터 빠르게 확인한다", "Check the facts", "事実から確かめる", AdventureEventStat.INT, AdventureEventStat.DEX, 2, AdventureBehaviorSignal.SEEK_NOVELTY, AdventureBehaviorSignal.TOWN_COMFORT),
            a("take_charge", "내 판단으로 일을 정리한다", "Take charge", "自分の判断で片付ける", AdventureEventStat.STR, AdventureEventStat.CON, -1, AdventureBehaviorSignal.ACT_ALONE, AdventureBehaviorSignal.SPEAK_DIRECT)),
        ApproachSet.DUEL to listOf(
            a("salute", "예를 갖춰 승부를 청한다", "Offer a respectful challenge", "礼を尽くして勝負を挑む", AdventureEventStat.CHA, AdventureEventStat.STR, 4, AdventureBehaviorSignal.SPEAK_DIRECT, AdventureBehaviorSignal.COOPERATE),
            a("measure_skill", "기술을 살피며 빈틈을 잰다", "Measure each technique", "技を見て隙を測る", AdventureEventStat.DEX, AdventureEventStat.WIS, 2, AdventureBehaviorSignal.CHECK_SAFETY, AdventureBehaviorSignal.PERSIST),
            a("raise_stakes", "과감하게 승부 조건을 건다", "Raise the stakes", "大胆な条件を賭ける", AdventureEventStat.INT, AdventureEventStat.CON, -2, AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.SELF_PRIORITY)),
    )

    private fun battle(
        kind: AdventureRelationshipBattleKind,
        vararg outcomes: AdventureEventOutcome,
    ) = AdventureRelationshipBattleRule(kind, outcomes.toSet(), MonsterGrade.NORMAL)

    private val negativeTiers = setOf(
        AdventureRelationshipTier.BAD,
        AdventureRelationshipTier.VERY_BAD,
        AdventureRelationshipTier.HOSTILE,
    )
    private val positiveTiers = setOf(AdventureRelationshipTier.CLOSE, AdventureRelationshipTier.VERY_CLOSE)

    private val seeds = listOf(
        Seed("directions", "길 위의 이정표", "A Trail Marker", "旅路の道しるべ", "다른 모험가의 곁에서 흐릿한 이정표를 살핀다.", "A faded trail marker stands beside another adventurer.", "別の冒険者のそばで薄れた道しるべを調べる。", "두 사람이 오래된 표식을 해독해 막힌 길을 열었다.", "Together, they deciphered the old marker and opened the blocked route.", "二人で古い標を読み解き、塞がれていた道を開いた。", ApproachSet.ROAD),
        Seed("broken_bridge", "끊어진 다리의 밧줄", "Rope at the Broken Bridge", "壊れた橋のロープ", "끊어진 다리 앞에서 밧줄을 든 모험가와 마주쳤다.", "An adventurer with a rope waits at a broken bridge.", "壊れた橋でロープを持つ冒険者と出会った。", "서로 밧줄을 당겨 안전한 건널목을 만들었다.", "Working the rope together, they made a safe crossing.", "二人でロープを引き、安全な渡り道を作った。", ApproachSet.ROAD),
        Seed("river_stones", "불어난 강의 징검돌", "Stones in a Swollen River", "増水した川の飛び石", "불어난 강 앞에서 건널 순서를 두고 다른 모험가와 고민한다.", "Two adventurers study the crossing order at a swollen river.", "増水した川で渡る順番を考える。", "흐름을 읽어 둘 다 젖지 않고 강을 건넜다.", "Reading the current, both crossed the river without getting wet.", "流れを読み、二人とも濡れずに川を渡った。", ApproachSet.ROAD),
        Seed("rockslide_path", "낙석 아래의 샛길", "Path Beneath the Rockslide", "落石の下の小道", "낙석으로 길이 막힌 곳에서 희미한 샛길을 함께 찾는다.", "A faint side path may bypass a rockslide.", "落石を避ける細い道を一緒に探す。", "낙석 소리를 맞춰 들으며 안전한 틈을 찾아냈다.", "Listening for falling rocks together, they found a safe opening.", "落石の音を聞き分け、安全に通れる隙間を見つけた。", ApproachSet.ROAD),
        Seed("fog_bell", "안개 속 종소리", "Bell in the Fog", "霧の中の鐘", "짙은 안개 속에서 다른 모험가가 울리는 작은 종을 들었다.", "Another adventurer rings a small bell in the thick fog.", "深い霧の中で別の冒険者の鈴が鳴る。", "종소리와 발소리를 맞춰 안개를 빠져나왔다.", "Matching the bell to their footsteps, they found their way out of the fog.", "鈴と足音を合わせ、霧を抜けた。", ApproachSet.ROAD),
        Seed("washed_map", "빗물에 번진 지도", "Rain-Smeared Map", "雨ににじんだ地図", "비에 번진 두 장의 지도를 나란히 놓고 남은 선을 맞춘다.", "Two rain-smeared maps still hold different useful lines.", "雨ににじんだ二枚の地図を重ねる。", "남은 선을 이어 새로운 길 하나를 복원했다.", "By joining the remaining lines, they restored a new route.", "残った線をつなぎ、新たな道を一本復元した。", ApproachSet.ROAD),
        Seed("cliff_ladder", "절벽의 낡은 사다리", "Old Ladder on the Cliff", "崖の古い梯子", "낡은 사다리를 먼저 오를 사람을 두고 잠시 눈이 마주쳤다.", "Two adventurers consider who should climb the old ladder first.", "古い梯子を誰が先に登るか考える。", "서로 사다리를 받쳐 무사히 절벽을 올랐다.", "They braced the ladder for each other and climbed the cliff safely.", "互いに梯子を支え、無事に崖を登った。", ApproachSet.ROAD),
        Seed("night_crossroads", "달 없는 갈림길", "Moonless Crossroads", "月のない分かれ道", "달빛 없는 갈림길에서 두 사람의 기억이 서로 다른 방향을 가리킨다.", "At a moonless crossroads, their memories point in different directions.", "月のない分かれ道で記憶が違う方向を示す。", "바람과 이끼를 함께 읽어 올바른 길을 골랐다.", "Reading the wind and moss together, they chose the right path.", "風と苔を一緒に読み、正しい道を選んだ。", ApproachSet.ROAD),
        Seed("storm_shelter", "폭풍 전의 돌지붕", "Stone Roof Before the Storm", "嵐の前の石屋根", "폭풍이 닥치기 전 작은 돌지붕 아래 자리를 나눠야 한다.", "A small stone roof must shelter two before the storm.", "嵐の前に小さな石屋根を二人で分ける。", "서로 자리를 양보해 폭풍을 무사히 넘겼다.", "They made room for each other and weathered the storm safely.", "場所を譲り合い、無事に嵐をしのいだ。", ApproachSet.CAMP),
        Seed("ferry_signal", "건너편의 횃불 신호", "Torch Signal Across the Water", "対岸への松明の合図", "멈춘 나룻배 건너편에 신호를 보낼 방법을 함께 찾는다.", "The stopped ferry needs a signal from across the water.", "止まった渡し舟へ合図を送る方法を探す。", "두 사람의 빛 신호가 사공에게 정확히 닿았다.", "Their combined light signal reached the ferryman clearly.", "二人の光の合図は、船頭に正しく届いた。", ApproachSet.ROAD),

        Seed("trapped_cart", "진흙에 빠진 수레", "Cart in the Mud", "泥にはまった荷車", "다른 모험가가 진흙에 빠진 상인의 수레를 밀고 있다.", "Another adventurer is pushing a merchant's cart out of the mud.", "別の冒険者が泥にはまった商人の荷車を押している。", "힘과 방향을 맞춰 수레를 길 위로 돌려놓았다.", "Combining their strength and aim, they returned the cart to the road.", "力と方向を合わせ、荷車を道へ戻した。", ApproachSet.AID),
        Seed("injured_hawk", "다친 전령매", "Injured Messenger Hawk", "傷ついた伝令鷹", "발목에 편지를 맨 전령매를 두 사람이 동시에 발견했다.", "Both adventurers find a messenger hawk with a letter tied to its leg.", "二人が手紙をつけた伝令鷹を見つける。", "상처를 돌보고 전령매가 다시 날도록 도왔다.", "They tended the hawk’s wound and helped it take flight again.", "傷を手当てし、伝令鷹が再び飛べるよう助けた。", ApproachSet.AID),
        Seed("runaway_mule", "달아난 짐노새", "Runaway Pack Mule", "逃げた荷運びラバ", "겁먹은 짐노새가 두 모험가 사이를 가로질러 달아난다.", "A frightened pack mule bolts between the adventurers.", "怯えた荷運びラバが二人の間を走り抜ける。", "양쪽에서 길을 좁혀 짐노새를 다치지 않게 붙잡았다.", "Closing in from both sides, they caught the pack mule without hurting it.", "両側から進路を狭め、ラバを傷つけずに捕まえた。", ApproachSet.AID),
        Seed("poisoned_spring", "빛이 탁한 샘", "Clouded Spring", "濁った泉", "목마른 여행자들 앞에서 샘물이 이상하게 빛난다.", "The spring shines strangely before thirsty travelers.", "喉の渇いた旅人の前で泉が怪しく光る。", "두 사람이 원인을 찾아 샘의 오염원을 막았다.", "Together, they found the cause and stopped the source of the contamination.", "二人で原因を突き止め、泉の汚染源を断った。", ApproachSet.MYSTERY),
        Seed("burning_wagon", "불붙은 짐마차", "Burning Wagon", "燃える荷馬車", "길가의 짐마차에 불이 붙자 다른 모험가가 물통을 던진다.", "A roadside wagon catches fire as another adventurer throws a bucket.", "道端の荷馬車が燃え、別の冒険者が水桶を投げる。", "불길을 나눠 막아 화물 대부분을 구했다.", "They contained the flames together and saved most of the cargo.", "手分けして火を食い止め、荷の大半を救った。", ApproachSet.AID),
        Seed("lost_apprentice", "길 잃은 견습생", "Lost Apprentice", "迷子の見習い", "울음을 참는 견습생이 서로 다른 마을 이름을 말한다.", "A lost apprentice gives two different village names.", "迷子の見習いが二つの村名を口にする。", "질문을 나눠 단서를 모아 아이의 스승을 찾았다.", "Dividing the questions and gathering clues, they found the apprentice’s teacher.", "質問を分担して手がかりを集め、見習いの師匠を見つけた。", ApproachSet.AID),
        Seed("bee_passage", "벌떼가 막은 통로", "Passage of Bees", "蜂に塞がれた道", "꽃가루 냄새를 쫓던 벌떼가 좁은 통로를 가득 메웠다.", "A swarm following pollen fills a narrow passage.", "花粉を追う蜂の群れが狭い道を塞ぐ。", "연기와 우회로를 함께 마련해 누구도 쏘이지 않았다.", "They prepared smoke and a detour, and no one was stung.", "煙と迂回路を用意し、誰も刺されずに通り抜けた。", ApproachSet.HUNT),
        Seed("fallen_tree", "바람에 쓰러진 거목", "Wind-Felled Tree", "風で倒れた大木", "거목이 길과 작은 짐승 굴을 동시에 막고 있다.", "A fallen tree blocks both the road and a small animal den.", "倒木が道と小動物の巣穴を塞いでいる。", "길과 굴을 모두 살리는 방향으로 나무를 옮겼다.", "They moved the tree in a way that saved both the road and the den.", "道も巣穴も守れる向きへ木を動かした。", ApproachSet.AID),
        Seed("cold_traveler", "꺼져 가는 손난로", "Fading Hand Warmer", "消えかけた懐炉", "추위에 떠는 여행자를 두 모험가가 같은 순간 발견했다.", "Both adventurers spot a traveler shivering in the cold.", "二人が寒さに震える旅人を同時に見つける。", "불씨와 외투를 나눠 여행자를 마을까지 보냈다.", "Sharing an ember and a cloak, they saw the traveler safely to town.", "火種と外套を分け、旅人を町まで送り届けた。", ApproachSet.AID),
        Seed("flooded_cellar", "물찬 지하 저장고", "Flooded Cellar", "水浸しの地下倉庫", "마을 어귀 저장고에 물이 차고 안에서 두드리는 소리가 난다.", "A flooded cellar near town echoes with knocking.", "町外れの地下倉庫が水浸しになり、中から叩く音がする。", "배수로와 문을 동시에 열어 갇힌 사람을 구했다.", "They opened the drain and door together, rescuing the trapped person.", "排水路と扉を同時に開け、閉じ込められた人を救った。", ApproachSet.AID),

        Seed("sealed_door", "문양으로 잠긴 돌문", "Sealed Stone Door", "紋様で閉じた石扉", "폐허의 돌문 앞에서 다른 모험가가 맞지 않는 문양을 돌리고 있다.", "Another adventurer turns mismatched symbols on a ruined stone door.", "遺跡の石扉で、別の冒険者が合わない紋様を回している。", "서로 본 문양을 합쳐 돌문의 순서를 풀었다.", "Combining the symbols each had seen, they solved the stone door’s sequence.", "互いに見た紋様を合わせ、石扉の仕掛けを解いた。", ApproachSet.MYSTERY),
        Seed("echo_chamber", "대답하는 메아리", "Answering Echo", "答えるこだま", "빈 동굴의 메아리가 두 사람의 이름을 다른 목소리로 되풀이한다.", "An empty cave repeats both names in unfamiliar voices.", "空の洞窟が二人の名を違う声で返す。", "메아리의 규칙을 찾아 숨은 출구를 열었다.", "They discovered the echo’s pattern and opened a hidden exit.", "こだまの法則を見つけ、隠された出口を開いた。", ApproachSet.MYSTERY),
        Seed("clockwork_sentinel", "멈춘 태엽 파수꾼", "Stopped Clockwork Guard", "止まったぜんまい番人", "태엽 파수꾼이 두 사람 사이에서 멈춘 채 손가락만 움직인다.", "A clockwork guard freezes between them, moving only one finger.", "ぜんまい番人が二人の間で指だけ動かす。", "손짓의 암호를 읽어 파수꾼을 안전하게 깨웠다.", "They decoded the gestures and safely reawakened the clockwork guard.", "手振りの暗号を読み解き、番人を安全に目覚めさせた。", ApproachSet.MYSTERY),
        Seed("mural_riddle", "지워지는 벽화", "Vanishing Mural", "消える壁画", "벽화의 색이 사라지기 전에 서로 다른 부분을 기억해야 한다.", "They must remember different parts before the mural fades.", "壁画が消える前に別々の部分を覚える。", "두 기억을 맞춰 벽화가 가리킨 장소를 찾았다.", "Combining their memories, they found the place shown by the mural.", "二人の記憶を合わせ、壁画が示す場所を見つけた。", ApproachSet.MYSTERY),
        Seed("cursed_coin", "뒤집히지 않는 동전", "Coin That Will Not Turn", "裏返らない硬貨", "바닥의 낡은 동전이 어느 쪽으로 던져도 같은 면으로 떨어진다.", "An old coin always lands on the same face.", "古い硬貨がいつも同じ面で落ちる。", "욕심을 내려놓자 동전 아래의 안전한 열쇠가 드러났다.", "When they let go of greed, a safe key appeared beneath the coin.", "欲を捨てると、硬貨の下に安全な鍵が現れた。", ApproachSet.MYSTERY),
        Seed("mirror_corridor", "서로 다른 거울길", "Diverging Mirror Hall", "分かれる鏡の道", "거울마다 두 모험가의 출구가 서로 다르게 비친다.", "Each mirror shows a different exit for each adventurer.", "鏡ごとに二人の出口が違って映る。", "서로의 거울을 설명해 진짜 복도를 가려냈다.", "By describing each other’s mirrors, they identified the true corridor.", "互いの鏡に映るものを説明し、本物の回廊を見抜いた。", ApproachSet.MYSTERY),
        Seed("collapsed_archive", "무너진 기록실", "Collapsed Archive", "崩れた記録室", "먼지 속 장부 한 권을 두 사람이 동시에 붙잡았다.", "Both reach for the same ledger in a collapsed archive.", "崩れた記録室で二人が同じ帳簿をつかむ。", "페이지를 나눠 읽어 잊힌 통행 기록을 복원했다.", "They divided the pages and restored a forgotten passage record.", "ページを分けて読み、忘れられた通行記録を復元した。", ApproachSet.MYSTERY),
        Seed("sleeping_statue", "숨 쉬는 석상", "Breathing Statue", "息をする石像", "이끼 낀 석상의 가슴이 아주 천천히 오르내린다.", "The mossy statue seems to breathe very slowly.", "苔むした石像の胸がゆっくり動く。", "힘이 아닌 노래로 석상을 깨워 길을 물었다.", "They woke the statue with song instead of force and asked it for directions.", "力ではなく歌で石像を目覚めさせ、道を尋ねた。", ApproachSet.MYSTERY),
        Seed("moon_well", "달빛을 담은 우물", "Moonlit Well", "月光を宿す井戸", "낮인데도 우물 속에는 밤하늘이 비친다.", "Though it is day, the well reflects a night sky.", "昼なのに井戸には夜空が映る。", "두 사람의 그림자를 맞춰 우물의 환영을 걷었다.", "Aligning their shadows, they dispelled the illusion in the well.", "二人の影を重ね、井戸の幻を消した。", ApproachSet.MYSTERY),
        Seed("underground_river", "땅속에서 흐르는 지도", "Map Flowing Underground", "地下を流れる地図", "얕은 지하수가 모래 위에 길 모양을 그렸다 지운다.", "Shallow underground water draws and erases a map in sand.", "浅い地下水が砂に地図を描いて消す。", "흐름을 함께 베껴 안전한 지하 통로를 기록했다.", "Together, they copied the flow and recorded a safe underground route.", "流れを一緒に写し取り、安全な地下道を記録した。", ApproachSet.MYSTERY),

        Seed("last_inn_bed", "여관의 마지막 침상", "Last Bed at the Inn", "宿の最後の寝台", "폭우가 쏟아지는 밤, 여관에는 침상 하나만 남았다.", "Only one bed remains at the inn on a stormy night.", "豪雨の夜、宿には寝台が一つしかない。", "공간과 시간을 나눠 둘 다 충분히 쉬었다.", "Sharing the space and time, both got enough rest.", "場所と時間を分け、二人とも十分に休んだ。", ApproachSet.CAMP),
        Seed("smith_queue", "대장간의 긴 줄", "Long Line at the Smithy", "鍛冶屋の長い列", "문 닫기 직전 대장간에서 두 사람의 차례가 겹쳤다.", "Their turns overlap just before the smithy closes.", "閉店前の鍛冶屋で二人の順番が重なる。", "수리 순서를 합리적으로 나눠 둘 다 장비를 손봤다.", "They divided the repair schedule fairly, and both had their gear serviced.", "修理の順番をうまく分け、二人とも装備を整えた。", ApproachSet.TRADE),
        Seed("false_relic", "시장에 나온 가짜 유물", "False Relic at Market", "市場の偽遺物", "상인이 같은 유물을 진품이라며 두 사람에게 권한다.", "A merchant offers the same supposed relic to both.", "商人が同じ遺物を本物だと二人に勧める。", "세부를 비교해 가짜 유물의 속임수를 밝혀냈다.", "By comparing details, they exposed the fake relic’s deception.", "細部を比べ、偽の遺物のからくりを暴いた。", ApproachSet.TRADE),
        Seed("missing_purse", "사라진 동전 주머니", "Missing Coin Purse", "消えた硬貨袋", "사라진 주머니 때문에 시장 사람들이 낯선 두 모험가를 의심한다.", "Market folk suspect the two strangers after a purse vanishes.", "袋が消え、市場の人々が二人を疑う。", "서로의 동선을 맞춰 진짜 분실 장소를 찾았다.", "Comparing their movements, they found where the purse was really lost.", "互いの足取りを照らし合わせ、本当の紛失場所を突き止めた。", ApproachSet.TOWN),
        Seed("tavern_rumor", "세 갈래로 퍼진 소문", "Rumor Split Three Ways", "三つに分かれた噂", "주점의 같은 소문이 세 가지 결말로 퍼지고 있다.", "One tavern rumor has spread with three endings.", "酒場の同じ噂に三つの結末がある。", "목격담을 나눠 과장된 부분을 걷어냈다.", "They compared eyewitness accounts and stripped away the exaggerations.", "目撃談を持ち寄り、誇張された部分を取り除いた。", ApproachSet.TOWN),
        Seed("closed_gate", "해 지기 전 닫힌 성문", "Gate Closed Before Sunset", "日没前に閉じた城門", "예정보다 일찍 닫힌 성문 앞에 두 모험가가 발이 묶였다.", "The city gate closes early, stranding both adventurers.", "予定より早く閉じた城門で二人が足止めされる。", "경비대의 사정을 듣고 안전한 야간 출입로를 얻었다.", "After hearing the guards’ circumstances, they gained a safe way into town at night.", "衛兵の事情を聞き、安全な夜間通路を教わった。", ApproachSet.TOWN),
        Seed("festival_lantern", "축제의 마지막 등불", "Last Festival Lantern", "祭りの最後の灯", "바람에 꺼지는 마지막 등불을 서로 다른 방식으로 지키려 한다.", "They try different ways to protect the festival's last lantern.", "風に消えそうな最後の灯を別の方法で守る。", "손과 망토를 함께 써 등불을 광장까지 옮겼다.", "Using their hands and cloaks together, they carried the lantern to the square.", "手と外套で灯を守り、広場まで運んだ。", ApproachSet.CONTEST),
        Seed("healer_queue", "치유소 앞의 두 환자", "Two Patients at the Healer", "治療所前の二人の患者", "치유사는 한 명뿐이고 두 사람 모두 다친 이를 데려왔다.", "One healer faces two adventurers carrying injured travelers.", "治療師は一人で、二人とも負傷者を連れている。", "부상 정도를 함께 살펴 더 급한 이를 먼저 치료했다.", "They assessed the injuries together and treated the more urgent patient first.", "怪我の程度を一緒に確かめ、重い人を先に治療した。", ApproachSet.TOWN),
        Seed("shared_stew", "한 냄비의 저녁", "One-Pot Supper", "一鍋の夕食", "남은 식재료는 제각각이지만 냄비는 하나뿐이다.", "Their ingredients differ, but there is only one pot.", "材料は違うが鍋は一つしかない。", "재료의 순서를 맞춰 뜻밖에 훌륭한 저녁을 만들었다.", "By timing the ingredients together, they made a surprisingly fine supper.", "食材を入れる順番を合わせ、思いがけず素晴らしい夕食を作った。", ApproachSet.CAMP),
        Seed("rooftop_thief", "지붕 위의 붉은 목도리", "Red Scarf on the Roof", "屋根の赤いマフラー", "도둑으로 몰린 붉은 목도리의 아이가 지붕 위로 달아난다.", "A child in a red scarf, accused of theft, flees across the roofs.", "盗人と疑われた赤いマフラーの子が屋根へ逃げる。", "한 사람은 뒤쫓고 한 사람은 사정을 물어 오해를 풀었다.", "One gave chase while the other asked questions, clearing up the misunderstanding.", "一人が追い、一人が事情を聞いて誤解を解いた。", ApproachSet.TOWN),

        Seed("bridge_duel", "좁은 다리의 예의", "Courtesy on a Narrow Bridge", "狭い橋の礼儀", "한 사람만 지날 수 있는 다리에서 누가 물러설지 시선이 마주친다.", "On a one-person bridge, neither immediately yields.", "一人しか通れない橋で互いに譲らない。", "짧은 겨룸으로 순서를 정하고 서로의 솜씨를 인정했다.", "A brief contest decided the order, and each acknowledged the other’s skill.", "短い勝負で順番を決め、互いの腕を認めた。", ApproachSet.DUEL, battleRule = battle(AdventureRelationshipBattleKind.SPAR, *AdventureEventOutcome.entries.toTypedArray())),
        Seed("kill_claim", "같은 사냥감의 흔적", "Tracks of the Same Quarry", "同じ獲物の跡", "두 사람이 같은 정예 짐승의 흔적을 자신의 사냥감이라 여긴다.", "Both believe the same elite beast is their quarry.", "二人が同じ強敵を自分の獲物だと思う。", "사냥의 공을 나누는 기준을 정해 불필요한 다툼을 막았다.", "They agreed on how to share credit for the hunt and prevented a needless dispute.", "狩りの功績を分ける基準を決め、余計な争いを防いだ。", ApproachSet.HUNT, battleRule = battle(AdventureRelationshipBattleKind.RIVALRY, AdventureEventOutcome.PARTIAL, AdventureEventOutcome.FAILURE)),
        Seed("training_spar", "해 질 녘의 대련", "Spar at Sunset", "夕暮れの稽古", "서로의 무기를 알아본 두 모험가가 짧은 대련을 제안한다.", "Recognizing each other's weapons, they propose a short spar.", "互いの武器を見て短い稽古を提案する。", "규칙을 먼저 맞춰 상처 없이 기술을 나눴다.", "They agreed on the rules first and traded techniques without injury.", "先に規則を決め、傷つけ合わずに技を交わした。", ApproachSet.DUEL, battleRule = battle(AdventureRelationshipBattleKind.SPAR, *AdventureEventOutcome.entries.toTypedArray())),
        Seed("shared_ambush", "등을 맞댄 기습", "Back-to-Back Ambush", "背中合わせの奇襲", "수풀에서 적의 기척이 번지자 두 모험가가 동시에 무기를 뽑는다.", "Both draw weapons as danger spreads through the brush.", "茂みから敵意が広がり二人が同時に武器を抜く。", "역할을 빠르게 나눠 포위망의 약한 곳을 찾았다.", "They quickly divided their roles and found a weak point in the encirclement.", "素早く役割を分け、包囲の弱点を見つけた。", ApproachSet.HUNT, battleRule = battle(AdventureRelationshipBattleKind.COOPERATIVE_HUNT, *AdventureEventOutcome.entries.toTypedArray())),
        Seed("resonant_weapons", "울림이 같은 두 무기", "Weapons of One Resonance", "同じ響きの二つの武器", "두 무기가 가까워지자 같은 음으로 울리며 주인의 힘을 시험한다.", "The two weapons hum together and test their wielders.", "二つの武器が同じ音で鳴り持ち主を試す。", "울림에 맞춰 힘을 겨루고 무기를 진정시켰다.", "Matching the resonance, they tested their strength and calmed the weapons.", "響きに合わせて力を競い、武器を鎮めた。", ApproachSet.DUEL, battleRule = battle(AdventureRelationshipBattleKind.RIVALRY, *AdventureEventOutcome.entries.toTypedArray())),
        Seed("treasure_dispute", "한 상자의 두 열쇠", "Two Keys, One Chest", "一つの箱と二つの鍵", "각자 가진 열쇠가 모두 있어야 열리는 상자를 발견했다.", "A chest opens only when both keys are present.", "二つの鍵がそろわないと開かない箱を見つける。", "소유권보다 약속을 먼저 정해 상자를 공정하게 열었다.", "They agreed on terms before ownership and opened the chest fairly.", "所有権より先に約束を決め、箱を公平に開けた。", ApproachSet.TRADE, battleRule = battle(AdventureRelationshipBattleKind.CONFLICT, AdventureEventOutcome.FAILURE)),
        Seed("nest_race", "괴수 둥지까지의 경주", "Race to the Beast Nest", "魔獣の巣への競争", "누가 먼저 둥지를 찾는지 겨루다 안쪽에서 포효가 들린다.", "A race to the nest ends when a roar rises within.", "巣への競争中に奥から咆哮が響く。", "경쟁을 멈추고 함께 둥지의 위협에 맞설 준비를 했다.", "They stopped competing and prepared to face the threat in the nest together.", "競争をやめ、巣の脅威に共に立ち向かう準備をした。", ApproachSet.CONTEST, battleRule = battle(AdventureRelationshipBattleKind.COOPERATIVE_HUNT, *AdventureEventOutcome.entries.toTypedArray())),
        Seed("escort_argument", "호위 길의 이견", "Dispute on the Escort Road", "護衛路の意見違い", "호위 대상의 안전을 두고 빠른 길과 안전한 길 중 의견이 갈린다.", "They disagree over the fast or safe escort route.", "護衛で速い道と安全な道に意見が分かれる。", "위험을 나눠 맡는 절충안을 찾아 호위를 이어 갔다.", "They found a compromise that divided the risks and continued the escort.", "危険を分担する折衷案を見つけ、護衛を続けた。", ApproachSet.RETURN, battleRule = battle(AdventureRelationshipBattleKind.CONFLICT, AdventureEventOutcome.FAILURE)),
        Seed("hostile_gate", "다시 마주친 폐성문", "Reunion at the Ruined Gate", "廃城門での再会", "좋지 않은 기억을 남긴 상대가 폐성문 앞에서 길을 막는다.", "A counterpart from a bitter memory blocks the ruined gate.", "悪い記憶の相手が廃城門で道を塞ぐ。", "지난 일을 직접 꺼내 최소한의 규칙을 다시 세웠다.", "They addressed the past directly and reestablished a minimum set of rules.", "過去のことを率直に話し、最低限の決まりを立て直した。", ApproachSet.DUEL, AdventureRelationshipReunionRule.REUNION_ONLY, negativeTiers, battle(AdventureRelationshipBattleKind.CONFLICT, AdventureEventOutcome.PARTIAL, AdventureEventOutcome.FAILURE)),
        Seed("dawn_oath", "새벽의 약속 대련", "Dawn Oath Spar", "夜明けの約束稽古", "가까워진 모험가와 다음 재회를 약속하며 기술을 확인한다.", "Close companions test their skill while promising another meeting.", "親しい冒険者と再会を約束し技を確かめる。", "서로의 성장을 확인하고 다음 만남의 표식을 나눴다.", "They recognized each other’s growth and exchanged signs for their next meeting.", "互いの成長を確かめ、次の再会の印を交換した。", ApproachSet.DUEL, AdventureRelationshipReunionRule.REUNION_ONLY, positiveTiers, battle(AdventureRelationshipBattleKind.SPAR, *AdventureEventOutcome.entries.toTypedArray())),
    )

    val all: List<AdventureRelationshipDefinition> = seeds.map(::build).also { definitions ->
        require(definitions.size == 50)
        require(definitions.map { it.id }.distinct().size == definitions.size)
        require(definitions.all { it.approaches.size == 3 && it.allowedTiers.isNotEmpty() })
        require(definitions.all { definition ->
            listOf(definition.title, definition.scene, definition.success, definition.partial, definition.failure)
                .all { it.ko.isNotBlank() && it.en.isNotBlank() && it.ja.isNotBlank() }
        })
        require(definitions.count { it.battleRule != null } in 8..15)
    }

    private val byId = all.associateBy { it.id }
    fun definition(id: String): AdventureRelationshipDefinition =
        requireNotNull(byId[id]) { "Unknown relationship scene $id" }

    private fun build(seed: Seed): AdventureRelationshipDefinition {
        val copies = outcomeCopies(seed.set)
        return AdventureRelationshipDefinition(
            id = seed.id,
            title = t(seed.koTitle, seed.enTitle, seed.jaTitle),
            scene = t(seed.koScene, seed.enScene, seed.jaScene),
            approaches = requireNotNull(approaches[seed.set]),
            success = t(seed.koSuccess, seed.enSuccess, seed.jaSuccess),
            partial = t(copies.partialKo, copies.partialEn, copies.partialJa),
            failure = t(copies.failureKo, copies.failureEn, copies.failureJa),
            rewardWeights = FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS,
            reunionRule = seed.reunionRule,
            allowedTiers = seed.allowedTiers,
            battleRule = seed.battleRule,
        )
    }

    private fun outcomeCopies(set: ApproachSet): OutcomeCopies = when (set) {
        ApproachSet.ROAD -> OutcomeCopies("확실한 구간까지만 함께 걷고 각자의 길로 향했다.", "방향을 정하지 못해 처음 자리로 돌아왔다.", "They share the certain stretch, then part ways.", "Unable to agree on a route, they return to the start.", "確かな区間だけ共に歩き、それぞれの道へ進んだ。", "道を決められず元の場所へ戻った。")
        ApproachSet.AID -> OutcomeCopies("급한 문제만 해결하고 남은 일은 주변 사람에게 맡겼다.", "도움의 순서를 맞추지 못해 시간이 더 흘렀다.", "They solve the urgent part and leave the rest to others.", "They lose time while disagreeing on how to help.", "急ぎの問題だけ解決し残りを周囲に任せた。", "助ける順番が合わず時間が過ぎた。")
        ApproachSet.MYSTERY -> OutcomeCopies("확실한 단서 하나만 얻어 서로 나눴다.", "해답을 찾지 못하고 서로 다른 추측만 남겼다.", "They share one reliable clue.", "They leave with different guesses and no answer.", "確かな手がかりを一つだけ分けた。", "答えを得られず別々の推測だけが残った。")
        ApproachSet.TRADE -> OutcomeCopies("급한 조건만 맞추고 다음 거래를 기약했다.", "서로의 몫을 정하지 못해 거래가 흐지부지됐다.", "They settle the urgent terms and defer the rest.", "The deal falls apart over their shares.", "急ぎの条件だけ合わせ次の取引を約束した。", "取り分を決められず取引は流れた。")
        ApproachSet.CAMP -> OutcomeCopies("짧은 휴식만 나누고 먼저 일어난 사람이 길을 나섰다.", "서로 경계를 풀지 못해 불편한 침묵이 남았다.", "They share a short rest before one leaves.", "Neither relaxes, leaving an uneasy silence.", "短い休息だけ分け先に起きた者が旅立った。", "警戒を解けず気まずい沈黙が残った。")
        ApproachSet.CONTEST -> OutcomeCopies("승부를 끝내지 못했지만 서로의 방식을 기억했다.", "규칙을 맞추지 못해 승부보다 감정이 앞섰다.", "The contest ends unfinished, but each remembers the other.", "Disagreement over the rules sours the contest.", "勝負は未決でも互いのやり方を覚えた。", "規則が合わず感情が先に立った。")
        ApproachSet.HUNT -> OutcomeCopies("흔적 일부를 나누고 서로 다른 방향을 맡았다.", "서로의 움직임이 엇갈려 사냥감을 놓쳤다.", "They split the trail and take different directions.", "Their movements clash and the quarry escapes.", "痕跡を分け別の方向を担当した。", "動きが合わず獲物を逃した。")
        ApproachSet.RETURN -> OutcomeCopies("마을이 보이는 곳까지만 동행한 뒤 헤어졌다.", "귀환 속도를 맞추지 못해 서로 먼저 가려 했다.", "They travel together until the town comes into view.", "Unable to match pace, each tries to go first.", "町が見える所まで同行して別れた。", "帰還の速さが合わず互いに先を急いだ。")
        ApproachSet.TOWN -> OutcomeCopies("사람들이 납득할 최소한의 답만 찾아냈다.", "말이 엇갈리며 주변의 오해까지 커졌다.", "They find only the minimum answer people can accept.", "Conflicting words deepen the misunderstanding.", "皆が納得できる最低限の答えだけ得た。", "言葉が食い違い誤解まで深まった。")
        ApproachSet.DUEL -> OutcomeCopies("승부는 나지 않았지만 서로의 한 수를 기억했다.", "예의를 지키지 못해 승부 뒤에도 날이 섰다.", "No victor emerges, but each remembers one technique.", "Broken courtesy leaves tension after the contest.", "決着はつかないが互いの一手を覚えた。", "礼を欠き勝負の後も緊張が残った。")
    }
}
