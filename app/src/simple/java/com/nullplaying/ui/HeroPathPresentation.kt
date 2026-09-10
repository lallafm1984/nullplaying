package com.nullplaying.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nullplaying.engine.BattleTraitCatalog
import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.engine.HeroPathEngine
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleTraitCategory
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChoiceType
import com.nullplaying.model.HeroPathMilestoneToken
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HERO_PATH_MAX_COMBAT_POINTS
import com.nullplaying.model.HeroPathNodeDefinition
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathEffectFamily
import com.nullplaying.model.SimpleGameState

internal fun heroPathPanelModel(
    state: SimpleGameState,
    selectedFilter: HeroPathFilter,
    draftTraitIds: Set<String> = emptySet(),
): HeroPathPanelUiModel {
    val battleClass = BattleHeroClass.valueOf(state.hero.heroClass.name)
    val branchDefinitions = HeroPathCatalog.branchesFor(battleClass)
    val lanes = branchDefinitions.mapIndexed { index, branch ->
        HeroPathLaneUiModel(
            id = branch.branch.name,
            name = HeroPathCopy(branch.nameKo, branch.nameEn, branch.nameJa),
            description = branchDescription(branch.branch),
            accent = laneAccent(index),
            icon = heroPathBranchIcon(branch.branch),
        )
    }
    val progressById = state.heroPath.traits.associateBy { it.traitId }
    val normalizedDrafts = draftTraitIds
        .filter { HeroPathCatalog.byTraitId[it]?.heroClassAffinity == battleClass }
        .take(state.heroPath.unspentPoints.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        .toSet()
    val projectedRanks = progressById.mapValues { it.value.rank }.toMutableMap().apply {
        normalizedDrafts.forEach { traitId -> this[traitId] = (this[traitId] ?: 0) + 1 }
    }
    // activeTraitIds is a five-slot V1 compatibility view, not the V2 allocation ledger.
    val projectedCores = projectedRanks.keys.count {
        HeroPathCatalog.byTraitId[it]?.nodeType == HeroPathNodeType.CORE
    }
    val projectedBranchRanks = projectedRanks.entries.groupBy(
        keySelector = { HeroPathCatalog.byTraitId.getValue(it.key).branch },
        valueTransform = Map.Entry<String, Int>::value,
    ).mapValues { (_, ranks) -> ranks.sum() }
    val remainingDraftPoints = state.heroPath.unspentPoints - normalizedDrafts.size
    val nodes = HeroPathCatalog.nodesFor(battleClass).map { node ->
        val progress = progressById[node.traitId]
        val branch = HeroPathCatalog.byBranch.getValue(node.branch)
        val ordinal = HeroPathCatalog.nodesFor(node.branch).indexOfFirst { it.traitId == node.traitId } + 1
        val category = BattleTraitCatalog.byId[node.traitId]?.category ?: BattleTraitCategory.TEMPERAMENT
        val projectedOwnRank = projectedRanks[node.traitId] ?: 0
        val maxRank = node.maxRank.coerceAtLeast(1)
        val isDraft = node.traitId in normalizedDrafts
        val candidateOwnRank = projectedOwnRank + if (isDraft) 0 else 1
        val candidateBranchPoints = projectedBranchRanks.getOrDefault(node.branch, 0) + if (isDraft) 0 else 1
        val gatePoints = if (node.nodeType == HeroPathNodeType.CORE) {
            candidateBranchPoints - candidateOwnRank
        } else candidateBranchPoints
        val prerequisitesMet = gatePoints >= node.requiredBranchInvestments
        val explicitPrerequisitesMet = node.prerequisiteTraitIds.all { (projectedRanks[it] ?: 0) > 0 }
        val prerequisiteRequirement = heroPathPrerequisiteRequirement(
            node.requiredBranchInvestments, prerequisitesMet, explicitPrerequisitesMet,
            excludesThisTalent = node.nodeType == HeroPathNodeType.CORE,
        )
        val exclusiveChoiceAllowed = node.choiceGroupId.isBlank() || projectedRanks.keys.none { traitId ->
            traitId != node.traitId && HeroPathCatalog.byTraitId[traitId]?.choiceGroupId == node.choiceGroupId
        }
        val coreAllowed = node.nodeType != HeroPathNodeType.CORE ||
            node.traitId in projectedRanks || projectedCores == 0
        val pointCapAllowed = projectedRanks.values.sum() + (if (isDraft) 0 else 1) <= HERO_PATH_MAX_COMBAT_POINTS
        // The same pure atomic allocator validates both the preview and the eventual commit.
        // This also rejects an invalid remaining batch after cancelling a prerequisite draft.
        val canDraft = isDraft || HeroPathEngine.applyDraft(
            state.heroPath, (normalizedDrafts + node.traitId).toList(),
        ).status == HeroPathMutationStatus.APPLIED
        val status = when {
            node.traitId in normalizedDrafts -> HeroPathNodeStatus.DRAFT
            progress != null && progress.rank >= maxRank -> HeroPathNodeStatus.MAX
            progress != null -> HeroPathNodeStatus.LEARNED
            canDraft -> HeroPathNodeStatus.AVAILABLE
            else -> HeroPathNodeStatus.LOCKED
        }
        val actionBlockReason = when {
            status == HeroPathNodeStatus.MAX -> HeroPathCopy("최대 랭크", "Max rank", "最大ランク")
            status == HeroPathNodeStatus.DRAFT || canDraft -> null
            node.minimumLevel > state.heroPath.lastGrantedMilestone -> HeroPathCopy(
                "Lv.${node.minimumLevel} 필요",
                "Requires Lv. ${node.minimumLevel}",
                "Lv.${node.minimumLevel}必要",
            )
            prerequisiteRequirement != null -> prerequisiteRequirement
            !exclusiveChoiceAllowed -> HeroPathCopy("A/B 중 하나만 습득", "Choose only one of A/B", "A/Bのどちらか一方のみ")
            !coreAllowed -> HeroPathCopy("핵심 특성은 1개만 활성", "Only one core trait can be active", "中核特性は1つのみ有効")
            !pointCapAllowed -> HeroPathCopy("최대 20포인트까지 배분", "20-point allocation limit", "割り振り上限は20ポイント")
            remainingDraftPoints <= 0L -> HeroPathCopy("포인트 부족", "Not enough points", "ポイントが足りません")
            else -> HeroPathCopy("현재 잠김", "Currently locked", "現在は未解放")
        }
        val detail = heroPathCombatDetail(node, progressById.mapValues { it.value.rank })
        HeroPathNodeUiModel(
            id = node.traitId,
            level = node.minimumLevel.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            laneId = node.branch.name,
            status = status,
            kind = node.uiKind(ordinal),
            name = nodeName(node, branch.nameEn, branch.nameJa, category, ordinal),
            summary = detail.summary,
            requirement = when (status) {
                HeroPathNodeStatus.LEARNED -> HeroPathCopy(
                    "${progress?.rank ?: 1}/$maxRank",
                    "${progress?.rank ?: 1}/$maxRank",
                    "${progress?.rank ?: 1}/$maxRank",
                )
                HeroPathNodeStatus.DRAFT -> HeroPathCopy("초안 +1", "Draft +1", "仮選択 +1")
                HeroPathNodeStatus.AVAILABLE -> HeroPathCopy("습득 가능", "Ready to learn", "習得可能")
                HeroPathNodeStatus.MAX -> HeroPathCopy("$maxRank/$maxRank MAX", "$maxRank/$maxRank MAX", "$maxRank/$maxRank MAX")
                HeroPathNodeStatus.LOCKED -> when {
                    node.minimumLevel > state.heroPath.lastGrantedMilestone -> HeroPathCopy(
                        "Lv.${node.minimumLevel} 필요",
                        "Requires Lv. ${node.minimumLevel}",
                        "Lv.${node.minimumLevel}必要",
                    )
                    prerequisiteRequirement != null -> prerequisiteRequirement
                    !exclusiveChoiceAllowed -> HeroPathCopy("A/B 중 하나만 습득", "Choose only one of A/B", "A/Bのどちらか一方のみ")
                    !coreAllowed -> HeroPathCopy("핵심 특성은 1개만 활성", "Only one core trait can be active", "中核特性は1つのみ有効")
                    !pointCapAllowed -> HeroPathCopy("최대 20포인트까지 배분", "20-point allocation limit", "割り振り上限は20ポイント")
                    remainingDraftPoints <= 0L -> HeroPathCopy("포인트 부족", "Not enough points", "ポイントが足りません")
                    else -> HeroPathCopy("현재 잠김", "Currently locked", "現在は未解放")
                }
            },
            metrics = progress?.let {
                listOf(
                    HeroPathMetricUiModel(
                        label = HeroPathCopy("전투 랭크", "Combat rank", "戦闘ランク"),
                        value = HeroPathCopy("${it.effectiveRank}", "${it.effectiveRank}", "${it.effectiveRank}"),
                        accent = laneAccent(branchDefinitions.indexOf(branch)),
                    ),
                )
            }.orEmpty(),
            canDraft = canDraft,
            actionBlockReason = actionBlockReason,
            currentRank = progress?.rank ?: 0,
            maxRank = maxRank,
            choiceGroupId = node.choiceGroupId,
            automaticRule = detail.rule,
            currentEffect = detail.current,
            nextEffect = detail.next,
            slot = node.slot,
            shortName = heroPathShortNodeName(node),
        )
    }
    return HeroPathPanelUiModel(
        heroName = state.hero.name,
        heroClassName = HeroPathCopy(
            state.hero.heroClass.labelKo,
            classNameEn(battleClass),
            classNameJa(battleClass),
        ),
        heroLevel = state.hero.level.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        learnedCount = state.heroPath.milestoneTokens.count(HeroPathMilestoneToken::resolved),
        totalChoiceCount = (state.hero.level / 5L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        pendingChoiceCount = state.heroPath.milestoneTokens.count { !it.resolved },
        earnedPoints = state.heroPath.earnedPoints,
        spentPoints = state.heroPath.spentPoints,
        unspentPoints = state.heroPath.unspentPoints,
        draftCount = normalizedDrafts.size,
        selectedFilter = selectedFilter,
        lanes = lanes,
        nodes = nodes,
    )
}

/** A branch symbol describes its specialization, never its position in the tab strip. */
internal fun heroPathBranchIcon(branch: HeroPathBranch): ImageVector = when (branch) {
    HeroPathBranch.WARRIOR_BERSERKER -> Icons.Filled.LocalFireDepartment
    HeroPathBranch.WARRIOR_BULWARK -> Icons.Filled.Shield
    HeroPathBranch.WARRIOR_WARLORD -> Icons.Filled.Campaign
    HeroPathBranch.ROGUE_ASSASSIN -> Icons.Filled.FlashOn
    HeroPathBranch.ROGUE_SHADOW_DANCER -> Icons.Filled.VisibilityOff
    HeroPathBranch.ROGUE_TRICKSTER -> Icons.Filled.TheaterComedy
    HeroPathBranch.RANGER_MARKSMAN -> Icons.Filled.MyLocation
    HeroPathBranch.RANGER_WINDWALKER -> Icons.Filled.Pets
    HeroPathBranch.RANGER_TRAPPER -> Icons.Filled.Forest
    HeroPathBranch.MAGE_ELEMENTALIST -> Icons.Filled.LocalFireDepartment
    HeroPathBranch.MAGE_ARCANIST -> Icons.Filled.AutoAwesome
    HeroPathBranch.MAGE_FORBIDDEN -> Icons.Filled.AcUnit
    HeroPathBranch.CLERIC_SANCTUARY -> Icons.Filled.WbSunny
    HeroPathBranch.CLERIC_JUDGMENT -> Icons.Filled.Gavel
    HeroPathBranch.CLERIC_PROVIDENCE -> Icons.Filled.SelfImprovement
    HeroPathBranch.PALADIN_GUARDIAN -> Icons.Filled.Security
    HeroPathBranch.PALADIN_AVENGER -> Icons.Filled.FlashOn
    HeroPathBranch.PALADIN_DAWN -> Icons.Filled.LightMode
}

/** Tier gates describe allocation totals; only the core excludes its own rank. */
internal fun heroPathPrerequisiteRequirement(
    requiredBranchPoints: Int,
    investmentMet: Boolean,
    explicitPrerequisitesMet: Boolean,
    excludesThisTalent: Boolean = false,
): HeroPathCopy? = when {
    !investmentMet -> if (excludesThisTalent) HeroPathCopy(
        "분야 내 다른 특성에 ${requiredBranchPoints}포인트 필요",
        "Requires $requiredBranchPoints points in other talents in this specialization",
        "この専門の他の特性に${requiredBranchPoints}ポイント必要",
    ) else HeroPathCopy(
        "습득 후 분야 투자 ${requiredBranchPoints}포인트 필요",
        "Requires $requiredBranchPoints specialization points after learning",
        "習得後、この専門に${requiredBranchPoints}ポイント必要",
    )
    !explicitPrerequisitesMet -> HeroPathCopy("선행 특성 필요", "Prerequisite required", "前提特性が必要")
    else -> null
}

internal fun heroPathArenaEntryModel(state: SimpleGameState): HeroPathArenaEntryUiModel {
    val battleClass = BattleHeroClass.valueOf(state.hero.heroClass.name)
    val branches = HeroPathCatalog.branchesFor(battleClass)
    val investmentByBranch = state.heroPath.traits.groupBy { progress ->
        HeroPathCatalog.byTraitId[progress.traitId]?.branch
    }.mapValues { (_, progress) -> progress.sumOf { it.rank.coerceAtLeast(1) } }
    val leadingBranch = branches.maxByOrNull { investmentByBranch[it.branch] ?: 0 }
        ?.takeIf { (investmentByBranch[it.branch] ?: 0) > 0 }
    val specialization = leadingBranch?.let {
        HeroPathCopy(it.nameKo, it.nameEn, it.nameJa)
    } ?: HeroPathCopy("미배정", "Unassigned", "未割り当て")
    val core = state.heroPath.activeCoreTraitId.takeIf(String::isNotBlank)?.let { traitId ->
        val node = HeroPathCatalog.byTraitId[traitId] ?: return@let null
        val branch = HeroPathCatalog.byBranch.getValue(node.branch)
        val korean = BattleTraitCatalog.byId[traitId]?.nameKo ?: "${branch.nameKo} 핵심"
        HeroPathCopy(korean, "${branch.nameEn} Core", "${branch.nameJa}・中核")
    }
    return HeroPathArenaEntryUiModel(
        specialization = specialization,
        coreTrait = core,
        spentPoints = state.heroPath.spentPoints,
        unspentPoints = state.heroPath.unspentPoints,
    )
}

internal fun heroPathChoiceModel(
    state: SimpleGameState,
    token: HeroPathMilestoneToken,
): HeroPathChoiceUiModel {
    val panel = heroPathPanelModel(state, HeroPathFilter.ALL)
    val panelNodes = panel.nodes.associateBy(HeroPathNodeUiModel::id)
    return HeroPathChoiceUiModel(
        eventId = token.tokenId,
        milestoneLevel = token.milestoneLevel.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        queuePosition = 1,
        queueSize = state.heroPath.milestoneTokens.count { !it.resolved }.coerceAtLeast(1),
        unspentPoints = state.heroPath.unspentPoints,
        options = token.offers.map { offer ->
            val source = requireNotNull(panelNodes[offer.traitId])
            val laneIndex = panel.lanes.indexOfFirst { it.id == source.laneId }.coerceAtLeast(0)
            HeroPathChoiceOptionUiModel(
                node = source.copy(
                    id = offer.offerId,
                    status = HeroPathNodeStatus.AVAILABLE,
                    kind = when (offer.type) {
                        HeroPathChoiceType.UNLOCK -> source.kind
                        HeroPathChoiceType.RANK_UP -> HeroPathNodeKind.MODIFIER
                        HeroPathChoiceType.REPLACE -> HeroPathNodeKind.MATCHUP_TACTIC
                        HeroPathChoiceType.CHANGE_CORE -> HeroPathNodeKind.CAPSTONE
                    },
                    requirement = choiceRequirement(offer.type),
                    metrics = source.metrics.ifEmpty { traitMetrics(source.id, laneIndex) },
                ),
                icon = when (laneIndex) {
                    0 -> Icons.Filled.Bolt
                    1 -> Icons.Filled.Shield
                    else -> Icons.Filled.Campaign
                },
            )
        },
    )
}

private fun traitMetrics(traitId: String, laneIndex: Int): List<HeroPathMetricUiModel> {
    val values = BattleTraitCatalog.byId[traitId]?.combatProfile?.values().orEmpty()
    val labels = listOf(
        HeroPathCopy("공격성", "Aggression", "攻撃性"),
        HeroPathCopy("강한 공격", "Heavy attack", "強攻撃"),
        HeroPathCopy("승부수", "Gamble", "勝負手"),
        HeroPathCopy("안정성", "Stability", "安定性"),
        HeroPathCopy("단기전", "Short fight", "短期戦"),
        HeroPathCopy("장기전", "Long fight", "長期戦"),
    )
    return values.mapIndexed { index, value -> index to (value - 50) }
        .filter { (_, delta) -> delta != 0 }
        .sortedByDescending { (_, delta) -> kotlin.math.abs(delta) }
        .take(2)
        .map { (index, delta) ->
            val display = if (delta > 0) "+$delta" else delta.toString()
            HeroPathMetricUiModel(
                label = labels[index],
                value = HeroPathCopy(display, display, display),
                accent = if (delta > 0) laneAccent(laneIndex) else AqRed,
            )
        }
}

internal fun HeroPathNodeDefinition.uiKind(ordinal: Int): HeroPathNodeKind = when (nodeType) {
    HeroPathNodeType.FOUNDATION -> HeroPathNodeKind.MODIFIER
    HeroPathNodeType.CHOICE -> HeroPathNodeKind.MATCHUP_TACTIC
    HeroPathNodeType.TACTICAL -> HeroPathNodeKind.KEYSTONE
    HeroPathNodeType.SPECIAL -> HeroPathNodeKind.ACTIVE
    HeroPathNodeType.CORE -> HeroPathNodeKind.CAPSTONE
    HeroPathNodeType.NORMAL -> when {
        ordinal in 1..2 -> HeroPathNodeKind.MODIFIER
        ordinal in 5..6 -> HeroPathNodeKind.ACTIVE
        else -> HeroPathNodeKind.KEYSTONE
    }
}

/** UI descriptions follow the deterministic rules, not the narrative names of the skills. */
internal fun heroPathShortNodeName(node: HeroPathNodeDefinition): HeroPathCopy = when (node.slot) {
    HeroPathNodeSlot.CHOICE_A -> HeroPathCopy("유리 상성", "Press Advantage", "有利相性")
    HeroPathNodeSlot.CHOICE_B -> HeroPathCopy("불리 상성", "Counter Disadvantage", "不利相性")
    else -> HeroPathCopy(
        node.nameKo.substringAfter(" · "),
        node.nameEn.substringAfter(" · "),
        node.nameJa.substringAfter(" · "),
    )
}

internal data class HeroPathCombatDetail(
    val summary: HeroPathCopy,
    val rule: HeroPathCopy,
    val current: HeroPathCopy,
    val next: HeroPathCopy?,
)

internal fun heroPathCombatDetail(
    node: HeroPathNodeDefinition,
    ranks: Map<String, Int> = emptyMap(),
): HeroPathCombatDetail {
    val ownRank = ranks[node.traitId] ?: 0
    val slotRank = ranks.entries.sumOf { (id, rank) ->
        if (HeroPathCatalog.byTraitId[id]?.slot == node.slot) rank else 0
    }.coerceIn(0, 2)
    val isFoundation = node.slot in setOf(HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B)
    val isAdvanced = node.slot == HeroPathNodeSlot.ADVANCED_TACTIC
    val advancedRank = ranks.entries.sumOf { (id, rank) ->
        if (HeroPathCatalog.byTraitId[id]?.slot == HeroPathNodeSlot.ADVANCED_TACTIC) rank else 0
    }.coerceIn(0, 2)
    val summary = when (node.slot) {
        HeroPathNodeSlot.FOUNDATION_A -> HeroPathCopy("충전을 더 쉽게 얻습니다.", "Build charge more easily.", "充填を得やすくする。")
        HeroPathNodeSlot.FOUNDATION_B -> if (node.heroClassAffinity in setOf(BattleHeroClass.ROGUE, BattleHeroClass.RANGER)) {
            HeroPathCopy("피격 시 충전 손실을 줄입니다.", "Reduce charge loss after impact.", "被弾時の充填減少を抑える。")
        } else HeroPathCopy("특수기 사용 후 충전 일부를 되찾습니다.", "Recover charge after a special.", "特殊技の後に充填を一部回収する。")
        HeroPathNodeSlot.CHOICE_A -> HeroPathCopy("유리한 상성을 더 강하게 활용합니다.", "Press a favorable matchup.", "有利な相性をさらに活かす。")
        HeroPathNodeSlot.CHOICE_B -> HeroPathCopy("상대의 유리한 상성 효과를 줄입니다.", "Reduce the opponent's matchup advantage.", "相手の有利相性効果を抑える。")
        HeroPathNodeSlot.ADVANCED_TACTIC -> HeroPathCopy("특수 효과를 강화합니다.", "Strengthen special effects.", "特殊効果を強化する。")
        HeroPathNodeSlot.CORE -> if (node.effectFamily in survivalFamilies) {
            HeroPathCopy("치명상을 한 번 버팁니다.", "Survive one lethal blow.", "致命傷を一度耐える。")
        } else specialEffectSummary(node.effectFamily)
        else -> specialEffectSummary(node.effectFamily)
    }
    val rule = when (node.slot) {
        HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B -> {
            val impactLoss = node.heroClassAffinity in setOf(BattleHeroClass.ROGUE, BattleHeroClass.RANGER)
            HeroPathCopy(
                "행동 후 충전 정산 · 최대 3. 같은 기초 슬롯은 분기 합산 최대 2랭크." +
                    if (impactLoss) " 피격 손실을 먼저 처리합니다." else "",
                "Charge updates after actions · max 3. Same-slot ranks total up to 2 across branches." +
                    if (impactLoss) " Impact loss is resolved first." else "",
                "行動後に充填を更新・最大3。同じ基礎枠は全系統で合算し最大2ランク。" +
                    if (impactLoss) "被弾による減少を先に処理。" else "",
            )
        }
        HeroPathNodeSlot.CHOICE_A, HeroPathNodeSlot.CHOICE_B -> HeroPathCopy(
            "주력 전문화의 A/B만 적용합니다. 활성 핵심이 주력을 정하며, 없으면 투자 포인트가 가장 많은 전문화를 따릅니다.",
            "Only the dominant specialization's A/B applies: the active core decides it, otherwise the most invested branch does.",
            "主力専門のA/Bのみ適用。有効な中核が主力を決め、なければ最多ポイントの系統に従う。",
        )
        HeroPathNodeSlot.SPECIAL_A -> HeroPathCopy(
            "충전 3에서 사용 가능한 보유 스킬로 자동 발동합니다. 충전 3을 소모합니다.",
            "At 3 charge, triggers through an available owned skill. Consumes 3 charge.",
            "充填3で使用可能な所持スキルから自動発動。充填3を消費。",
        )
        HeroPathNodeSlot.SPECIAL_B -> HeroPathCopy(
            "충전 3이고, 체력 60% 이하 또는 6라운드 이후이면 자동 선택됩니다. 사용 가능한 보유 스킬이 필요하며 충전을 모두 소모합니다.",
            "Eligible at 3 charge when HP is 60% or lower, or from round 6. Requires an available owned skill; consumes all charge.",
            "充填3に加え、体力60%以下または6ラウンド以降で自動選択。使用可能な所持スキルが必要で、充填を全消費。",
        )
        HeroPathNodeSlot.CORE -> if (node.effectFamily in survivalFamilies) HeroPathCopy(
            "충전 3을 보유한 채 치명상을 입으면 모두 소모하고 체력 1로 생존합니다. 전투당 1회.",
            "At 3 charge, a lethal blow consumes all charge and leaves 1 HP. Once per battle.",
            "充填3で致命傷を受けると全消費し、体力1で生存。1戦に1回。",
        ) else HeroPathCopy(
            "충전 3이고, 체력 45% 이하 또는 8라운드 이후이면 우선 선택됩니다. 사용 가능한 보유 스킬로 발동하고 충전을 모두 소모합니다. 전투당 1회.",
            "Prioritized at 3 charge when HP is 45% or lower, or from round 8. Requires an available owned skill; consumes all charge. Once per battle.",
            "充填3に加え、体力45%以下または8ラウンド以降で優先選択。使用可能な所持スキルで発動し、充填を全消費。1戦に1回。",
        )
        HeroPathNodeSlot.ADVANCED_TACTIC -> HeroPathCopy(
            "특수기가 발동할 때 적용합니다. 고급 전술 랭크는 분기 간 합산 최대 2이며, 상성과 효과별 상한이 최종 수치를 조정합니다.",
            "Applies on special activation. Advanced ranks add across branches, capped at 2. Matchups and effect caps adjust final values.",
            "特殊技発動時に適用。上級戦術は全系統で合算し最大2。最終値は相性と効果上限で変わる。",
        )
    }
    fun effect(rank: Int): HeroPathCopy = when {
        isFoundation -> foundationEffect(node, rank)
        isAdvanced -> advancedEffect(node.effectFamily, rank)
        rank == 0 -> HeroPathCopy("미습득", "Not learned", "未習得")
        node.slot in setOf(HeroPathNodeSlot.SPECIAL_A, HeroPathNodeSlot.SPECIAL_B) ||
            (node.slot == HeroPathNodeSlot.CORE && node.effectFamily !in survivalFamilies) -> specialNumericEffect(node.effectFamily, advancedRank)
        else -> summary
    }
    return HeroPathCombatDetail(
        summary, rule,
        current = effect(if (isFoundation || isAdvanced) slotRank else ownRank),
        next = if (ownRank >= node.maxRank) null else effect(
            if (isFoundation || isAdvanced) (slotRank + 1).coerceAtMost(2) else ownRank + 1,
        ),
    )
}

private val survivalFamilies = setOf(
    HeroPathEffectFamily.IMPACT_GUARD, HeroPathEffectFamily.GRACEFUL_RECOVERY,
    HeroPathEffectFamily.PROVIDENT_REVERSAL, HeroPathEffectFamily.OATHED_GUARD,
)

private fun specialEffectSummary(family: HeroPathEffectFamily): HeroPathCopy = when (family) {
    HeroPathEffectFamily.IMPACT_GUARD, HeroPathEffectFamily.RETRIBUTIVE_COUNTER -> HeroPathCopy(
        "명중·방어 판정 시 반격 피해를 더합니다.", "Add counter damage on a hit or block.", "命中・防御判定時に反撃ダメージを追加。",
    )
    HeroPathEffectFamily.GRACEFUL_RECOVERY, HeroPathEffectFamily.PROVIDENT_REVERSAL,
    HeroPathEffectFamily.OATHED_GUARD, HeroPathEffectFamily.DAWN_CYCLE, HeroPathEffectFamily.EVASIVE_CHAIN -> HeroPathCopy(
        "기술과 함께 자신의 체력을 회복합니다.", "Restore your HP alongside the technique.", "技とともに自身の体力を回復。",
    )
    HeroPathEffectFamily.DECEPTIVE_CONTROL, HeroPathEffectFamily.CONTROLLED_HUNT, HeroPathEffectFamily.ARCANE_CYCLE -> HeroPathCopy(
        "명중·방어 판정 시 추가 피해와 상대 충전 −1.", "On a hit or block: bonus damage and enemy charge −1.", "命中・防御判定時に追加ダメージ、相手の充填−1。",
    )
    HeroPathEffectFamily.FORBIDDEN_GAMBIT -> HeroPathCopy(
        "명중·방어 판정 시 추가 피해와 상대 충전 −1.",
        "On a hit or block: bonus damage and enemy charge −1.",
        "命中・防御判定時に追加ダメージ、相手の充填−1。",
    )
    else -> HeroPathCopy(
        "명중·방어 판정 시 추격 피해를 더합니다(전투당 최대 3회).",
        "Add follow-up damage on a hit or block (up to 3 times per battle).",
        "命中・防御判定時に追撃ダメージを追加（1戦に最大3回）。",
    )
}

private fun advancedEffect(family: HeroPathEffectFamily, rank: Int): HeroPathCopy = when (family) {
    HeroPathEffectFamily.IMPACT_GUARD, HeroPathEffectFamily.RETRIBUTIVE_COUNTER -> HeroPathCopy(
        "반격량: 해당 공격 피해의 ${20 + rank * 3}% (상성 적용 전)",
        "Counter: ${20 + rank * 3}% of this attack's damage (before matchup)",
        "反撃量：その攻撃ダメージの${20 + rank * 3}%（相性補正前）",
    )
    HeroPathEffectFamily.GRACEFUL_RECOVERY, HeroPathEffectFamily.PROVIDENT_REVERSAL,
    HeroPathEffectFamily.OATHED_GUARD, HeroPathEffectFamily.DAWN_CYCLE, HeroPathEffectFamily.EVASIVE_CHAIN -> HeroPathCopy(
        "회복 기술의 추가 회복 ${25 + rank * 5}%. 회복이 없는 기술의 고정 회복은 동일.",
        "Healing techniques: +${25 + rank * 5}% healing. Flat healing on non-healing techniques is unchanged.",
        "回復技の追加回復${25 + rank * 5}%。回復のない技の固定回復は変化なし。",
    )
    HeroPathEffectFamily.FORBIDDEN_GAMBIT -> HeroPathCopy(
        "추가 피해 ${10 + rank * 10}%, 상대 최대 체력의 ${4 + rank * 2}%까지(상성 적용 전).",
        "Bonus: ${10 + rank * 10}% of attack damage, capped at ${4 + rank * 2}% enemy max HP (before matchup).",
        "追加ダメージ${10 + rank * 10}%、相手最大体力の${4 + rank * 2}%が上限（相性補正前）。",
    )
    else -> {
        val base = when (family) {
            HeroPathEffectFamily.RAGE_BURST -> 20
            HeroPathEffectFamily.MORALE_COMMAND -> 50
            HeroPathEffectFamily.FOCUSED_SHOT -> 400
            HeroPathEffectFamily.MOBILE_VOLLEY -> 350
            HeroPathEffectFamily.DECEPTIVE_CONTROL -> 35
            HeroPathEffectFamily.CONTROLLED_HUNT -> 120
            HeroPathEffectFamily.ARCANE_CYCLE -> 18
            else -> 30
        }
        val step = if (family in setOf(HeroPathEffectFamily.DECEPTIVE_CONTROL, HeroPathEffectFamily.CONTROLLED_HUNT, HeroPathEffectFamily.ARCANE_CYCLE)) 3 else 5
        HeroPathCopy(
            "추가 피해: 해당 공격 피해의 ${base + rank * step}% (상성·상한 적용 전)",
            "Bonus damage: ${base + rank * step}% of this attack's damage (before matchup and caps)",
            "追加ダメージ：その攻撃ダメージの${base + rank * step}%（相性・上限適用前）",
        )
    }
}

private fun specialNumericEffect(family: HeroPathEffectFamily, advancedRank: Int): HeroPathCopy = when (family) {
    HeroPathEffectFamily.GRACEFUL_RECOVERY, HeroPathEffectFamily.PROVIDENT_REVERSAL,
    HeroPathEffectFamily.OATHED_GUARD, HeroPathEffectFamily.DAWN_CYCLE, HeroPathEffectFamily.EVASIVE_CHAIN -> {
        val flat = when (family) {
            HeroPathEffectFamily.PROVIDENT_REVERSAL -> 1
            HeroPathEffectFamily.DAWN_CYCLE -> 2
            else -> 5
        }
        HeroPathCopy(
            "회복 기술은 회복량 +${25 + advancedRank * 5}%, 그 외 기술은 최대 체력 $flat% 회복(상성 적용 전). 추가 회복은 1회 15%·전투 합계 30%까지.",
            "Healing skill: +${25 + advancedRank * 5}% healing; otherwise restore $flat% max HP (before matchup). Bonus healing cap: 15% per use, 30% per battle.",
            "回復技は回復量+${25 + advancedRank * 5}%、他の技は最大体力$flat%回復（相性補正前）。追加回復の上限は1回15%、1戦合計30%。",
        )
    }
    else -> advancedEffect(family, advancedRank)
}

private fun foundationEffect(node: HeroPathNodeDefinition, rank: Int): HeroPathCopy {
    val heroClass = HeroPathCatalog.byBranch.getValue(node.branch).heroClass
    if (node.slot == HeroPathNodeSlot.FOUNDATION_A && heroClass == BattleHeroClass.WARRIOR) {
        return HeroPathCopy(
            "최대 체력의 ${12 - rank}% 이상 피격 또는 공격을 막으면 충전 +1.",
            "Gain 1 charge after taking at least ${12 - rank}% max HP damage or blocking an attack.",
            "最大体力の${12 - rank}%以上の被弾、または攻撃を防ぐと充填+1。",
        )
    }
    if (node.slot == HeroPathNodeSlot.FOUNDATION_B && heroClass == BattleHeroClass.RANGER) {
        return HeroPathCopy(
            "한 번에 최대 체력의 ${25 + rank * 2}% 이상 피격 시 충전 초기화.",
            "Charge resets after a hit for at least ${25 + rank * 2}% of max HP.",
            "一度に最大体力の${25 + rank * 2}%以上の被弾で充填リセット。",
        )
    }
    if (node.slot == HeroPathNodeSlot.FOUNDATION_B && heroClass == BattleHeroClass.ROGUE) {
        return when (rank) {
            0 -> HeroPathCopy("피격 시 충전 −1.", "Lose 1 charge when damaged.", "被弾時に充填−1。")
            1 -> HeroPathCopy(
                "25% 미만 피격은 50% 확률로 충전 −1. 25% 이상은 항상 −1.",
                "Below 25% max HP damage: 50% chance to lose 1 charge. At 25% or more: always lose 1.",
                "最大体力25%未満の被弾は50%の確率で充填−1。25%以上は必ず−1。",
            )
            else -> HeroPathCopy(
            "25% 미만 피격은 충전 유지. 25% 이상 피격은 50% 확률로 충전 −1.",
            "Keep charge below 25% max HP damage. At 25% or more, 50% chance to lose 1 charge.",
            "最大体力25%未満の被弾は充填維持。25%以上は50%の確率で充填−1。",
            )
        }
    }
    if (node.slot == HeroPathNodeSlot.FOUNDATION_B) return HeroPathCopy(
        "특수기 사용 후 ${rank * 25}% 확률로 충전 1 회수(치명상 생존 제외).",
        "After a special: ${rank * 25}% chance to recover 1 charge (excludes lethal survival).",
        "特殊技後、${rank * 25}%の確率で充填1を回収（致命傷の生存効果を除く）。",
    )
    return when (heroClass) {
        BattleHeroClass.ROGUE, BattleHeroClass.RANGER -> when (rank) {
            0 -> if (heroClass == BattleHeroClass.ROGUE) HeroPathCopy(
                "피해를 주거나 공격을 회피하면 충전 +1.",
                "Deal damage or evade an attack: charge +1.",
                "ダメージを与えるか攻撃を回避すると充填+1。",
            ) else HeroPathCopy("피해를 주면 충전 +1.", "Deal damage: charge +1.", "ダメージを与えると充填+1。")
            1 -> HeroPathCopy(
                "기본 조건에 추가: 스킬이 빗나가거나 회피당해도 충전 +1.",
                "Also gain 1 charge when a skill misses or is evaded.",
                "基本条件に追加：スキルが外れるか回避されても充填+1。",
            )
            else -> HeroPathCopy(
                "기본 조건에 추가: 스킬·평타가 빗나가거나 회피당해도 충전 +1.",
                "Also gain 1 charge when a skill or basic attack misses or is evaded.",
                "基本条件に追加：スキル・通常攻撃が外れるか回避されても充填+1。",
            )
        }
        BattleHeroClass.MAGE -> when (rank) {
            0 -> HeroPathCopy("방어 또는 스킬 성공 시 충전 +1.", "Guard or successful skill: charge +1.", "防御またはスキル成功で充填+1。")
            1 -> HeroPathCopy("추가: 치명타·강공 평타로 피해를 주면 충전 +1.", "Also gain 1 charge on damaging critical/heavy basic attacks.", "追加：会心・強打の通常攻撃でダメージを与えると充填+1。")
            else -> HeroPathCopy("추가: 평타로 피해를 주면 충전 +1.", "Also gain 1 charge on damaging basic attacks.", "追加：通常攻撃でダメージを与えると充填+1。")
        }
        BattleHeroClass.CLERIC -> if (rank == 0) HeroPathCopy(
            "스킬 사용 또는 방어 시 충전 +1.", "Use a skill or guard: charge +1.", "スキル使用または防御で充填+1。",
        ) else HeroPathCopy(
            "추가: 최대 체력의 ${20 - rank * 5}% 이상 피격 시 충전 +1.",
            "Also gain 1 charge after taking at least ${20 - rank * 5}% max HP damage.",
            "追加：最大体力の${20 - rank * 5}%以上の被弾で充填+1。",
        )
        BattleHeroClass.PALADIN -> when (rank) {
            0 -> HeroPathCopy("스킬·방어 또는 공격을 막으면 충전 +1.", "Use a skill, guard, or block: charge +1.", "スキル使用・防御・攻撃を防ぐと充填+1。")
            1 -> HeroPathCopy("추가: 강공 평타로 피해를 주면 충전 +1.", "Also gain 1 charge on damaging heavy basic attacks.", "追加：強打の通常攻撃でダメージを与えると充填+1。")
            else -> HeroPathCopy("추가: 평타로 피해를 주면 충전 +1.", "Also gain 1 charge on damaging basic attacks.", "追加：通常攻撃でダメージを与えると充填+1。")
        }
        BattleHeroClass.WARRIOR -> error("Warrior foundation A is handled above")
    }
}

private fun nodeName(
    node: HeroPathNodeDefinition,
    branchEn: String,
    branchJa: String,
    category: BattleTraitCategory,
    ordinal: Int,
): HeroPathCopy {
    if (node.nameKo.isNotBlank() && node.nameKo != node.traitId) {
        return HeroPathCopy(node.nameKo, node.nameEn, node.nameJa)
    }
    val korean = BattleTraitCatalog.byId[node.traitId]?.nameKo ?: node.traitId
    val categoryCopy = category.copy
    return HeroPathCopy(
        korean,
        "$branchEn ${categoryCopy.en} $ordinal",
        "$branchJa・${categoryCopy.ja}$ordinal",
    )
}

private fun choiceRequirement(type: HeroPathChoiceType): HeroPathCopy = when (type) {
    HeroPathChoiceType.UNLOCK -> HeroPathCopy("습득", "Unlock", "習得")
    HeroPathChoiceType.RANK_UP -> HeroPathCopy("랭크 상승", "Rank up", "ランクアップ")
    HeroPathChoiceType.REPLACE -> HeroPathCopy("활성 교체", "Replace active", "アクティブ入替")
    HeroPathChoiceType.CHANGE_CORE -> HeroPathCopy("핵심 교체", "Change core", "中核入替")
}

private fun branchDescription(branch: HeroPathBranch): HeroPathCopy = when (branch) {
    HeroPathBranch.WARRIOR_BERSERKER -> HeroPathCopy("피격을 힘으로 바꿔 밀어붙입니다.", "Turns impact into relentless force.", "被弾を力に変え、攻勢を続ける。")
    HeroPathBranch.WARRIOR_BULWARK -> HeroPathCopy("충격을 받아내며 중심을 지킵니다.", "Absorbs impact and holds the center.", "衝撃を受け止め、中心を守る。")
    HeroPathBranch.WARRIOR_WARLORD -> HeroPathCopy("사기로 전투의 박자를 이끌어 갑니다.", "Commands the tempo through morale.", "士気で戦いの流れを指揮する。")
    HeroPathBranch.ROGUE_ASSASSIN -> HeroPathCopy("개전의 빈틈을 치명타로 연결합니다.", "Converts opening gaps into decisive strikes.", "開幕の隙を決定打に変える。")
    HeroPathBranch.ROGUE_SHADOW_DANCER -> HeroPathCopy("회피와 연격을 하나의 흐름으로 잇습니다.", "Chains evasion into rapid follow-ups.", "回避から素早い追撃へつなぐ。")
    HeroPathBranch.ROGUE_TRICKSTER -> HeroPathCopy("속임수로 상대의 호흡을 깨트립니다.", "Breaks the opponent's rhythm with feints.", "フェイントで相手のリズムを崩す。")
    HeroPathBranch.RANGER_MARKSMAN -> HeroPathCopy("거리와 조준을 정교하게 다듬습니다.", "Perfects range and precise aim.", "間合いと狙いを研ぎ澄ます。")
    HeroPathBranch.RANGER_WINDWALKER -> HeroPathCopy("이동하며 연속 공격을 이어 갑니다.", "Keeps volleys flowing while moving.", "動きながら連射をつなぐ。")
    HeroPathBranch.RANGER_TRAPPER -> HeroPathCopy("준비된 위치로 사냥의 흐름을 설계합니다.", "Controls the hunt through prepared ground.", "備えた地形で狩りを支配する。")
    HeroPathBranch.MAGE_ELEMENTALIST -> HeroPathCopy("원소를 폭발적인 공세로 바꿉니다.", "Shapes elements into explosive pressure.", "元素を爆発的な攻勢に変える。")
    HeroPathBranch.MAGE_ARCANIST -> HeroPathCopy("마력 순환으로 기술을 빠르게 이어 갑니다.", "Cycles arcane power between techniques.", "秘術の力を循環させ、技をつなぐ。")
    HeroPathBranch.MAGE_FORBIDDEN -> HeroPathCopy("위험한 마법으로 한 번의 반전을 노립니다.", "Gambles on dangerous magic for reversals.", "危険な魔法に賭け、逆転を狙う。")
    HeroPathBranch.CLERIC_SANCTUARY -> HeroPathCopy("회복과 방어로 장기전을 지배합니다.", "Controls long fights through recovery.", "回復と守りで長期戦を制する。")
    HeroPathBranch.CLERIC_JUDGMENT -> HeroPathCopy("성력을 정확한 심판으로 바꿉니다.", "Turns grace into precise judgment.", "聖なる力を正確な審判に変える。")
    HeroPathBranch.CLERIC_PROVIDENCE -> HeroPathCopy("위기 속에서 반전의 답을 찾습니다.", "Finds a reversal inside each crisis.", "危機の中に逆転の答を見つける。")
    HeroPathBranch.PALADIN_GUARDIAN -> HeroPathCopy("맹세로 방어의 축을 세웁니다.", "Builds an unbroken guard through oaths.", "誓いで揺るぎない守りを築く。")
    HeroPathBranch.PALADIN_AVENGER -> HeroPathCopy("받은 충격을 응징으로 돌려줍니다.", "Returns endured impact as retribution.", "受けた衝撃を報いとして返す。")
    HeroPathBranch.PALADIN_DAWN -> HeroPathCopy("수호와 공세를 새벽처럼 순환시킵니다.", "Cycles between guard and radiant assault.", "守りと輝く攻勢を循環させる。")
}

private data class CategoryCopy(val en: String, val ja: String)

private val BattleTraitCategory.copy: CategoryCopy
    get() = when (this) {
        BattleTraitCategory.OPENING -> CategoryCopy("Opening", "開幕")
        BattleTraitCategory.OFFENSE -> CategoryCopy("Offense", "攻勢")
        BattleTraitCategory.DEFENSE -> CategoryCopy("Defense", "防御")
        BattleTraitCategory.REVERSAL -> CategoryCopy("Reversal", "逆転")
        BattleTraitCategory.ENDURANCE -> CategoryCopy("Endurance", "持久")
        BattleTraitCategory.PRECISION -> CategoryCopy("Precision", "精密")
        BattleTraitCategory.TACTICS -> CategoryCopy("Tactics", "戦術")
        BattleTraitCategory.TEMPERAMENT -> CategoryCopy("Resolve", "意志")
        BattleTraitCategory.MOMENTUM -> CategoryCopy("Momentum", "勢い")
        BattleTraitCategory.FINISH -> CategoryCopy("Finisher", "決着")
    }

private fun laneAccent(index: Int): Color = when (index) {
    0 -> Color(0xFFD35858)
    1 -> Color(0xFFD7A84A)
    else -> Color(0xFF6685DE)
}

private fun classNameEn(heroClass: BattleHeroClass): String = when (heroClass) {
    BattleHeroClass.WARRIOR -> "Fighter"
    BattleHeroClass.ROGUE -> "Thief"
    BattleHeroClass.RANGER -> "Ranger"
    BattleHeroClass.MAGE -> "Mage"
    BattleHeroClass.CLERIC -> "Cleric"
    BattleHeroClass.PALADIN -> "Paladin"
}

private fun classNameJa(heroClass: BattleHeroClass): String = when (heroClass) {
    BattleHeroClass.WARRIOR -> "ファイター"
    BattleHeroClass.ROGUE -> "シーフ"
    BattleHeroClass.RANGER -> "レンジャー"
    BattleHeroClass.MAGE -> "メイジ"
    BattleHeroClass.CLERIC -> "クレリック"
    BattleHeroClass.PALADIN -> "パラディン"
}
