package com.nullplaying.ui

/** Shared copy for creation and the adventurer menu. Deliberately excludes balance numbers. */
internal data class StatGuideEntry(
    val statLabel: String,
    val title: String,
    val benefit: String,
)

internal object StatGuideContent {
    const val title = "능력치 안내"
    const val introduction = "모든 직업에 적용되는 효과입니다."
    const val classBenefit = "직업의 주·보조 능력치는 공격력·전투력과 기본 스킬 발동률에도 영향을 줍니다."

    val entries = listOf(
        StatGuideEntry("STR", "STR · 힘", "가방에 더 많은 전리품을 담을 수 있습니다."),
        StatGuideEntry("CON", "CON · 체력", "오프라인 모험시간이 늘어나고, 최대 HP 성장에 도움이 됩니다."),
        StatGuideEntry("DEX", "DEX · 민첩", "몬스터를 더 빨리 찾습니다."),
        StatGuideEntry("INT", "INT · 지능", "스킬이 더 자주 발동하며, 최대 MP 성장에 도움이 됩니다."),
        StatGuideEntry("WIS", "WIS · 지혜", "스킬이 더 자주 발동하며, 최대 MP 성장에 도움이 됩니다."),
        StatGuideEntry("CHA", "CHA · 매력", "전리품을 더 높은 가격에 판매합니다."),
        StatGuideEntry("HP MAX", "HP MAX · 최대 HP", "CON에 따라 성장하는 최대 체력입니다."),
        StatGuideEntry("MP MAX", "MP MAX · 최대 MP", "INT와 WIS에 따라 성장하는 최대 마나입니다."),
    )
}
