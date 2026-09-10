package com.nullplaying.engine

import com.nullplaying.model.AdventureTraitActivation
import com.nullplaying.model.AdventureTraitChange
import com.nullplaying.model.AdventureTraitEffectKind
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType

data class AdventureTraitDefinition(
    val id: String, val name: AdventureText, val advantage: AdventureText,
    val disadvantage: AdventureText, val oppositeId: String = "",
)

object AdventureTraitCatalog {
    private fun t(ko: String, en: String, ja: String) = AdventureText(ko, en, ja)
    private fun trait(
        id: String,
        koName: String, enName: String, jaName: String,
        koAdvantage: String, enAdvantage: String, jaAdvantage: String,
        koDisadvantage: String, enDisadvantage: String, jaDisadvantage: String,
        oppositeId: String,
    ) = AdventureTraitDefinition(
        id = id,
        name = t(koName, enName, jaName),
        advantage = t(koAdvantage, enAdvantage, jaAdvantage),
        disadvantage = t(koDisadvantage, enDisadvantage, jaDisadvantage),
        oppositeId = oppositeId,
    )
    val all = listOf(
        trait("L03", "가볍게 출발", "Light Packer", "身軽な出発", "때때로 준비를 줄여 빠르게 출발합니다", "Sometimes packs lightly and sets out sooner", "時に荷造りを減らし、早く出発する", "그 여정에서는 가방이 1칸 줄어듭니다", "That journey has one less bag slot", "その旅では荷物枠が1つ減る", "L04"),
        trait("L06", "손에 익은 장비", "Familiar Gear", "手に馴染んだ装備", "때때로 작은 차이에는 익숙한 장비를 지킵니다", "Sometimes keeps familiar gear when the difference is small", "時に差が小さければ使い慣れた装備を使い続ける", "작은 성능 향상을 지나칠 수 있습니다", "May pass up a small improvement", "わずかな性能向上を見逃すことがある", "L05"),
        trait("S06", "방어구 애호가", "Armor Enthusiast", "防具好き", "때때로 상점에서 방어구를 한 번 더 살핍니다", "Sometimes reviews one more piece of armor in town", "時に店でもう一度防具を調べる", "검토에 시간이 들고 구매 비용도 냅니다", "Review takes time; purchases still cost gold", "確認に時間がかかり、購入にはお金も必要", "S05"),
        trait("E01", "대담한 선택", "Bold Choices", "大胆な選択", "드물게 더 과감한 방법을 택합니다", "Rarely switches to a bolder approach", "まれにより大胆な方法を選ぶ", "안전하고 잘하는 방법을 놓칠 수 있습니다", "May set aside a safer, better-suited method", "安全で得意な方法を手放すことがある", "E02"),
        trait("E02", "안전을 확인함", "Safety First", "安全を確かめる", "드물게 더 신중한 방법을 택합니다", "Rarely switches to a safer approach", "まれにより慎重な方法を選ぶ", "과감한 해결의 기회를 놓칠 수 있습니다", "May pass up a bold opportunity", "大胆に解決する機会を逃すことがある", "E01"),
        trait("E04", "미련을 두지 않음", "Moves On", "見切りが早い", "드물게 풀리지 않은 사건을 더 살피지 않고 끝냅니다", "Rarely closes an unresolved matter quickly", "まれに解決しない出来事を早く切り上げる", "다시 살펴볼 기회를 남기지 않습니다", "Leaves no chance for another look", "調べ直す機会を残さない", "E03"),
        trait("G02", "반복으로 익힘", "Learns by Repetition", "反復で学ぶ", "때때로 익숙한 경험에서 더 배웁니다", "Sometimes learns more from a familiar experience", "時に慣れた経験から多く学ぶ", "반복을 되새기는 데 더 머뭅니다", "Reviewing the repetition takes longer", "反復を振り返る時間が増える", "G01"),
        trait("R01", "남을 먼저 도움", "Helps Others First", "人助けを優先", "드물게 다른 이를 돕는 방법을 우선합니다", "Rarely gives priority to helping someone else", "まれに他人を助ける方法を優先する", "자신의 일정이 늦어질 수 있습니다", "The hero's own journey may be delayed", "自分の旅が遅れることがある", "R02"),
        trait("R02", "제 갈 길을 지킴", "Stays the Course", "自分の道を貫く", "드물게 자신의 여정을 지키는 방법을 우선합니다", "Rarely favors the approach that keeps the journey on course", "まれに自分の旅程を守る方法を優先する", "남을 도울 기회를 지나칠 수 있습니다", "May pass by a chance to help", "他人を助ける機会を逃すことがある", "R01"),
        trait("R03", "함께 풀어감", "Works Together", "共に解決する", "드물게 다른 이와 협력하는 방법을 택합니다", "Rarely switches to a cooperative approach", "まれに他人と協力する方法を選ぶ", "뜻을 맞추는 데 시간이 들 수 있습니다", "Coordinating can take time", "意思を合わせるのに時間がかかることがある", "R04"),
        trait("R04", "혼자 판단함", "Acts Alone", "一人で判断する", "드물게 혼자 해결하는 방법을 택합니다", "Rarely switches to an independent approach", "まれに一人で解決する方法を選ぶ", "함께하는 이의 통찰을 놓칠 수 있습니다", "May miss another person's insight", "仲間の気づきを見逃すことがある", "R03"),
        trait("T01", "마을이 편함", "At Home in Town", "町が落ち着く", "때때로 마을의 사건을 빠르게 풀어갑니다", "Sometimes handles town matters more quickly", "時に町の出来事を早く解決する", "들판의 사건에서는 더 머물 수 있습니다", "May take longer over events outside town", "野外の出来事では時間がかかることがある", "T02"),
        trait("T02", "들판이 편함", "At Home Outdoors", "野外が落ち着く", "때때로 들판의 사건을 빠르게 풀어갑니다", "Sometimes handles events outside town more quickly", "時に野外の出来事を早く解決する", "마을의 사건에서는 더 머물 수 있습니다", "May take longer over town matters", "町の出来事では時間がかかることがある", "T01"),
        trait("T03", "귀향을 서두름", "Hurries Home", "帰郷を急ぐ", "드물게 돌아가는 길을 재촉하는 방법을 택합니다", "Rarely favors the quickest way home", "まれに最も早く帰る方法を選ぶ", "귀환길의 발견을 지나칠 수 있습니다", "May pass by discoveries on the return route", "帰り道の発見を見逃すことがある", "T04"),
        trait("T04", "돌아오는 길을 살핌", "Scans the Way Home", "帰り道を見回る", "드물게 귀환길의 단서를 살피는 방법을 택합니다", "Rarely favors a closer look along the way home", "まれに帰り道をじっくり調べる方法を選ぶ", "마을로 돌아가는 길이 느려질 수 있습니다", "The return to town may take longer", "町への帰路が遅れることがある", "T03"),
        AdventureTraitDefinition("C03", t("한 방을 노림", "Decisive Striker", "一撃を狙う"), t("때때로 강한 일격이 더 강해집니다", "Sometimes a strong blow hits harder", "時に強い一撃がさらに強くなる"), t("약한 일격도 더 약해집니다", "Weak blows also become weaker", "弱い一撃もさらに弱くなる"), "C04"),
        AdventureTraitDefinition("C04", t("안정적인 공격", "Steady Striker", "安定した攻撃"), t("때때로 약한 일격을 보완합니다", "Sometimes weak blows improve", "時に弱い一撃を補う"), t("강한 일격의 위력도 줄어듭니다", "Strong blows also lose force", "強い一撃の威力も下がる"), "C03"),
        AdventureTraitDefinition("L01", t("수집벽", "Collector", "収集癖"), t("드물게 물건을 하나 더 발견합니다", "Rarely finds one more item", "まれに品をもう一つ見つける"), t("확인에 더 머물고 가방이 찹니다", "Searching takes longer and fills the bag", "確認に時間がかかり、荷物も増える"), "L02"),
        AdventureTraitDefinition("L02", t("필요한 것만 챙김", "Travels Light", "必要な物だけ"), t("드물게 작은 전리품을 두고 빠르게 떠납니다", "Rarely leaves minor loot and moves on sooner", "まれに小さな戦利品を置き、早く進む"), t("남긴 물건의 판매액은 받지 못합니다", "The item's sale value is lost", "置いた品の売却金は得られない"), "L01"),
        AdventureTraitDefinition("L04", t("짐을 잘 꾸림", "Careful Packer", "荷造り上手"), t("때때로 한 여정에 물건을 더 담습니다", "Sometimes carries more for one journey", "時に一度の旅で荷物を多く持てる"), t("출발 준비에 더 머뭅니다", "Departure preparation takes longer", "出発の準備に時間がかかる"), "L03"),
        AdventureTraitDefinition("L05", t("장비를 감정하는 눈", "Appraiser's Eye", "装備を見極める目"), t("드물게 장비의 숨은 성능을 알아봅니다", "Rarely uncovers an item's hidden power", "まれに装備の隠れた性能を見抜く"), t("발견한 성능을 확인하는 데 시간이 듭니다", "Confirming improved power takes time", "見つけた性能の確認に時間がかかる"), "L06"),
        AdventureTraitDefinition("S01", t("흥정꾼", "Bargainer", "値交渉上手"), t("때때로 전리품 값을 더 받습니다", "Sometimes earns more from a sale", "時に戦利品を高く売れる"), t("그 거래가 조금 길어집니다", "That sale takes a little longer", "その取引に少し時間がかかる"), "S02"),
        AdventureTraitDefinition("S02", t("거래를 서두름", "Quick Trader", "取引を急ぐ"), t("때때로 판매를 빠르게 마칩니다", "Sometimes finishes selling sooner", "時に売却を早く終える"), t("그 판매에서 값을 덜 받습니다", "That sale earns less gold", "その売却で得る金額が減る"), "S01"),
        AdventureTraitDefinition("S05", t("무기 애호가", "Weapon Enthusiast", "武器好き"), t("때때로 상점에서 무기를 한 번 더 살핍니다", "Sometimes reviews one more weapon in town", "時に店でもう一度武器を調べる"), t("검토에 시간이 들고 구매 비용도 냅니다", "Review takes time; purchases still cost gold", "確認に時間がかかり、購入にはお金も必要"), "S06"),
        AdventureTraitDefinition("E03", t("끝까지 조사", "Persistent Investigator", "最後まで調べる"), t("드물게 다른 방법으로 사건을 다시 살핍니다", "Rarely tries another approach to a situation", "まれに別の方法で出来事を調べ直す"), t("추가 조사에 시간이 듭니다", "Further investigation takes time", "追加の調査に時間がかかる"), "E04"),
        AdventureTraitDefinition("G01", t("변화를 통해 배움", "Learns from Change", "変化から学ぶ"), t("때때로 새로운 경험에서 더 배웁니다", "Sometimes learns more from a new experience", "時に新しい経験から多く学ぶ"), t("경험을 정리하는 데 더 머뭅니다", "Reflection takes a little longer", "経験を振り返る時間が増える"), "G02"),
        AdventureTraitDefinition("R05", t("직설적", "Forthright", "率直な話し方"), t("때때로 호감과 대화의 성과가 깊어집니다", "Sometimes deepens warmth and clear outcomes", "時に好感や会話の成果が深まる"), t("불편한 기억과 대화의 실패도 깊어집니다", "Uneasy memories and failures can also deepen", "気まずい記憶や会話の失敗も深まる"), "R06"),
        AdventureTraitDefinition("R06", t("완곡한 말투", "Gentle Words", "穏やかな言葉"), t("때때로 불편한 기억과 대화 실패를 누그러뜨립니다", "Sometimes softens friction and failed conversations", "時に気まずい記憶や会話の失敗を和らげる"), t("호감과 확실한 성과도 조금 약해집니다", "Warmth and clear outcomes may also be reduced", "好感や確かな成果も少し弱まる"), "R05"),
        trait("C01", "강적 추적자", "Mighty Foe Hunter", "強敵追跡者", "드물게 정예와 보스 전투에서 경험을 더 쌓습니다", "Rarely learns more from elite and boss battles", "まれに強敵やボス戦で多く学ぶ", "같은 성향이 발동하면 일반 몬스터에게서 얻는 경험이 줄어듭니다", "When it triggers, ordinary battles grant less experience", "発動時は通常戦で得る経験が減る", "C02"),
        trait("C02", "길목 사냥꾼", "Roadside Hunter", "道端の狩人", "드물게 일반 몬스터 전투에서 경험을 더 쌓습니다", "Rarely learns more from ordinary battles", "まれに通常戦で多く学ぶ", "같은 성향이 발동하면 정예와 보스에게서 얻는 경험이 줄어듭니다", "When it triggers, elite and boss battles grant less experience", "発動時は強敵やボス戦で得る経験が減る", "C01"),
        trait("C05", "결정타를 새김", "Studies the Finisher", "決め技を刻む", "드물게 강한 결정타로 끝낸 전투에서 더 배웁니다", "Rarely learns more after a powerful finishing blow", "まれに強い決め技で終えた戦いから多く学ぶ", "약한 한 방으로 끝낸 전투에서는 경험을 덜 얻습니다", "A weak finishing blow grants less experience", "弱い一撃で終えた戦いでは経験が減る", "C06"),
        trait("C06", "안정된 마무리", "Measured Finisher", "安定した仕上げ", "드물게 절제된 마무리로 끝낸 전투에서 더 배웁니다", "Rarely learns more after a measured finishing blow", "まれに抑えた一撃で終えた戦いから多く学ぶ", "과감한 결정타로 끝낸 전투에서는 경험을 덜 얻습니다", "A powerful finishing blow grants less experience", "強い決め技で終えた戦いでは経験が減る", "C05"),
        trait("E05", "성공의 기세", "Success Momentum", "成功の勢い", "드물게 성공한 사건을 빠르게 마무리합니다", "Rarely wraps up a successful incident sooner", "まれに成功した出来事を早く終える", "풀리지 않은 사건에서는 더 오래 머뭅니다", "Unresolved incidents take longer", "解決しない出来事では長く留まる", "E06"),
        trait("E06", "실패를 대비함", "Plans for Setbacks", "失敗に備える", "드물게 풀리지 않은 사건을 빠르게 정리합니다", "Rarely closes an unresolved incident sooner", "まれに解決しない出来事を早く整理する", "성공한 사건도 확인하느라 마무리가 느려집니다", "Even success takes longer to double-check", "成功しても確認のため終了が遅くなる", "E05"),
        trait("S03", "약점 보완", "Shore Up Weaknesses", "弱点を補う", "때때로 상점에서 가장 약한 부위를 한 번 더 살폍니다", "Sometimes reviews the weakest equipment slot once more", "時に店で最も弱い部位をもう一度見る", "추가 검토에 시간이 들고 구매 비용도 냅니다", "The extra review takes time and purchases still cost gold", "追加確認に時間がかかり購入費用も必要", "S04"),
        trait("S04", "강점 집중", "Build on Strengths", "強みを伸ばす", "때때로 상점에서 가장 강한 부위를 한 번 더 살폍니다", "Sometimes reviews the strongest equipment slot once more", "時に店で最も強い部位をもう一度見る", "약한 부위는 남은 채 추가 검토 시간과 비용이 듭니다", "Weaker slots remain while the extra review takes time and gold", "弱い部位を残したまま追加確認の時間と費用がかかる", "S03"),
        trait("T05", "귀환 정리", "Orderly Return", "帰還の整理", "때때로 귀환 판매를 빠르게 마칩니다", "Sometimes completes the return sale sooner", "時に帰還後の売却を早く終える", "다음 출발 준비는 더 오래 걸립니다", "Preparing the next departure takes longer", "次の出発準備に時間がかかる", "T06"),
        trait("T06", "곧장 재출발", "Quick Turnaround", "すぐに再出発", "때때로 귀환 후 출발 준비를 빠르게 마칩니다", "Sometimes prepares the next departure sooner", "時に帰還後の出発準備を早く終える", "귀환 판매를 서두르지 못해 더 오래 걸립니다", "The return sale takes longer", "帰還後の売却に時間がかかる", "T05"),
        trait("G03", "큰 성과를 새김", "Learns from Great Feats", "大きな成果を刻む", "드물게 큰 경험치 보상에서 더 배웁니다", "Rarely learns more from a large experience reward", "まれに大きな経験値報酬から多く学ぶ", "작은 보상에서는 경험을 덜 얻습니다", "Small rewards grant less experience", "小さな報酬では経験が減る", "G04"),
        trait("G04", "작은 배움을 챙김", "Values Small Lessons", "小さな学びを拾う", "드물게 작은 경험치 보상에서 더 배웁니다", "Rarely learns more from a small experience reward", "まれに小さな経験値報酬から多く学ぶ", "큰 보상에서는 경험을 덜 얻습니다", "Large rewards grant less experience", "大きな報酬では経験が減る", "G03"),
    )
    private val byId = all.associateBy { it.id }
    fun definition(id: String): AdventureTraitDefinition = requireNotNull(byId[id]) { "Unknown adventure trait $id" }
    fun find(id: String): AdventureTraitDefinition? = byId[id]

    fun activationText(a: AdventureTraitActivation): AdventureText = when (a.effectKind) {
        AdventureTraitEffectKind.DAMAGE -> t("일격의 피해 ${a.previousValue} → ${a.currentValue}", "Strike damage ${a.previousValue} → ${a.currentValue}", "一撃のダメージ ${a.previousValue} → ${a.currentValue}")
        AdventureTraitEffectKind.EXTRA_ITEM -> t("${a.subjectName} 추가 획득 · 확인에 더 머뭅니다", "Extra item: ${a.subjectName} · a longer search", "${a.subjectName}を追加獲得・確認に時間をかけた")
        AdventureTraitEffectKind.OMITTED_ITEM -> t("${a.subjectName} 남김 · 빠르게 떠납니다", "Leaves ${a.subjectName} and moves on sooner", "${a.subjectName}を置き、早く進む")
        AdventureTraitEffectKind.BAG_CAPACITY -> if (a.traitId == "L03") t(
            "출발 준비 단축 · 이번 여정 가방 -1칸",
            "Departure shortened · bag -1 this journey",
            "出発準備を短縮・この旅の荷物枠-1",
        ) else t("이번 여정 가방 +3칸 · 출발 준비가 길어집니다", "Bag +3 this journey · more preparation", "この旅の荷物枠+3・出発準備が長くなる")
        AdventureTraitEffectKind.APPRAISAL -> if (a.traitId == "L06") t(
            "소폭 향상은 가방에 남기고 익숙한 장비를 유지합니다",
            "Keeps familiar gear and stores the marginal upgrade",
            "小さな強化は荷物に残し、使い慣れた装備を保つ",
        ) else t("새 장비 성능 ${a.previousValue} → ${a.currentValue} · 확인에 더 머뭅니다", "Found gear power ${a.previousValue} → ${a.currentValue} · longer inspection", "入手装備の性能 ${a.previousValue} → ${a.currentValue}・確認に時間をかけた")
        AdventureTraitEffectKind.SALE -> if (a.traitId == "S01") t("판매 합계 ${a.previousValue} → ${a.currentValue}G · 거래가 조금 길어집니다", "Sale total ${a.previousValue} → ${a.currentValue}G · trading takes longer", "売却合計 ${a.previousValue} → ${a.currentValue}G・取引が少し長くなる")
            else t("판매 합계 ${a.previousValue} → ${a.currentValue}G · 빠르게 거래를 마칩니다", "Sale total ${a.previousValue} → ${a.currentValue}G · trading finishes sooner", "売却合計 ${a.previousValue} → ${a.currentValue}G・取引を早く終える")
        AdventureTraitEffectKind.SHOP_REVIEW -> when (a.traitId) {
            "S06" -> t("방어구를 한 번 더 살핍니다 · 검토에 시간이 듭니다", "Reviews one more piece of armor · inspection takes time", "防具をもう一度調べる・確認に時間がかかる")
            "S03" -> t("가장 약한 장비 부위를 한 번 더 살핍니다", "Reviews the weakest equipment slot once more", "最も弱い装備部位をもう一度見る")
            "S04" -> t("가장 강한 장비 부위를 한 번 더 살핍니다", "Reviews the strongest equipment slot once more", "最も強い装備部位をもう一度見る")
            else -> t("무기를 한 번 더 살핍니다 · 검토에 시간이 듭니다", "Reviews one more weapon · inspection takes time", "武器をもう一度調べる・確認に時間がかかる")
        }
        AdventureTraitEffectKind.RETRY -> {
            if (a.traitId == "E04") t(
                "풀리지 않은 일을 정리하고 길을 재촉합니다",
                "Closes the unresolved matter and moves on",
                "解決しない出来事を切り上げ、先を急ぐ",
            ) else run {
            val parts = a.subjectName.split(':')
            val approach = AdventureEventEngine.all.firstOrNull { it.id == parts.firstOrNull() }?.approaches
                ?.firstOrNull { it.id == parts.getOrNull(1) }?.title
            if (approach == null) t("다시 조사 · 조사 시간이 늘어납니다", "Investigates again · more time needed", "再調査・調査時間が増える")
            else t("다시 조사 · ${approach.ko}", "Investigates again · ${approach.en}", "再調査・${approach.ja}")
            }
        }
        AdventureTraitEffectKind.EXPERIENCE -> if (a.traitId in setOf("C01", "C02", "C05", "C06", "G03", "G04")) {
            t("경험치 ${a.previousValue} → ${a.currentValue}", "Experience ${a.previousValue} → ${a.currentValue}", "経験値 ${a.previousValue} → ${a.currentValue}")
        } else {
            t("경험치 ${a.previousValue} → ${a.currentValue} · 잠시 돌아봅니다", "Experience ${a.previousValue} → ${a.currentValue} · a moment to reflect", "経験値 ${a.previousValue} → ${a.currentValue}・少し振り返る")
        }
        AdventureTraitEffectKind.RELATIONSHIP -> t("말투에 따라 이번 만남의 기억이 달라집니다", "The tone changes the memory of this meeting", "話し方で今回の出会いの記憶が変わる")
        AdventureTraitEffectKind.DIALOGUE -> when (a.traitId) {
            "R05", "R06" -> t("말투가 대화의 결말을 바꿉니다", "The tone changes the conversation's outcome", "話し方が会話の結末を変える")
            "T01", "T02" -> t("익숙한 환경에 따라 사건을 풀어가는 속도가 달라집니다", "Familiar surroundings change the pace of the event", "慣れた環境によって出来事への対応速度が変わる")
            "E05", "E06" -> t("사건 처리 시간 ${a.previousValue} → ${a.currentValue}", "Incident time ${a.previousValue} → ${a.currentValue}", "出来事の時間 ${a.previousValue} → ${a.currentValue}")
            "T05", "T06" -> t("귀환 진행 시간 ${a.previousValue} → ${a.currentValue}", "Return flow time ${a.previousValue} → ${a.currentValue}", "帰還の進行時間 ${a.previousValue} → ${a.currentValue}")
            else -> t("성향에 따라 사건에 접근하는 방법을 바꿉니다", "A trait changes the approach to the event", "性向によって出来事への取り組み方が変わる")
        }
    }

    fun changeReason(change: AdventureTraitChange): AdventureText = when (change.reasonKey.substringBefore(':')) {
        "combat" -> t("여러 전투에서 쌓인 공격 경험", "Attack experience across several battles", "いくつもの戦闘で積んだ攻撃経験")
        "equipment" -> t("장비를 비교하고 사용한 경험", "Experience comparing and using equipment", "装備を比べて使った経験")
        "trade" -> t("판매와 준비에서 쌓인 경험", "Experience from trading and preparation", "取引と準備で積んだ経験")
        "relationship" -> t("길에서 마음을 전한 경험", "Experience expressing thoughts on the road", "道で気持ちを伝えた経験")
        else -> t("모험에서 반복해 쌓인 경험", "Repeated experience during adventures", "冒険で繰り返し積んだ経験")
    }

    fun recentEventText(event: RecentAdventureEvent): AdventureText {
        return if (event.type == RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED) {
        val kind = AdventureTraitEffectKind.entries.firstOrNull { it.name == event.contextName } ?: return t("특성이 여정에 영향을 남겼습니다", "A trait influenced the journey", "特性が旅に影響を残した")
        activationText(AdventureTraitActivation(0L, event.subjectId, "", 0L, event.occurredAt, kind,
            event.previousValue ?: 0L, event.currentValue ?: 0L, subjectName = event.currentName))
    } else {
        val kind = AdventureTraitChangeKind.entries.firstOrNull { it.name == event.contextName } ?: AdventureTraitChangeKind.ACQUIRED
        changeReason(AdventureTraitChange(0L, event.subjectId, kind, "", event.occurredAt, event.currentName, event.previousName))
        }
    }
}
