package com.nullplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.engine.AdventureTraitCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*

internal fun adventureTraitChangeLabel(kind: AdventureTraitChangeKind, language: AppLanguage): String = when (kind) {
    AdventureTraitChangeKind.ACQUIRED -> journeyText(language, "새 특성", "New trait", "新しい特性")
    AdventureTraitChangeKind.WEAKENED -> journeyText(language, "흔들리는 특성", "Wavering trait", "揺らぐ特性")
    AdventureTraitChangeKind.RECOVERED -> journeyText(language, "다시 뚜렷해진 특성", "Trait reaffirmed", "再び明確になった特性")
    AdventureTraitChangeKind.LOST -> journeyText(language, "사라진 특성", "Trait faded", "薄れた特性")
    AdventureTraitChangeKind.REPLACED -> journeyText(language, "변화한 특성", "Trait changed", "変化した特性")
}

internal fun adventureTraitActivationText(activation: AdventureTraitActivation, language: AppLanguage): String {
    val localizedActivation = when (activation.effectKind) {
        AdventureTraitEffectKind.EXTRA_ITEM, AdventureTraitEffectKind.OMITTED_ITEM ->
            activation.copy(subjectName = localizedItemName(activation.subjectName, language))
        else -> activation
    }
    return AdventureTraitCatalog.activationText(localizedActivation).inLanguage(language)
}

internal fun adventureTraitEffectVisibleInPhase(kind: AdventureTraitEffectKind, phase: AdventurePhase): Boolean = when (kind) {
    AdventureTraitEffectKind.DAMAGE -> phase == AdventurePhase.COMBAT || phase == AdventurePhase.LOOTING
    AdventureTraitEffectKind.EXTRA_ITEM, AdventureTraitEffectKind.OMITTED_ITEM,
    AdventureTraitEffectKind.APPRAISAL, AdventureTraitEffectKind.EXPERIENCE ->
        phase == AdventurePhase.LOOTING || phase == AdventurePhase.EVENT_RESULT
    AdventureTraitEffectKind.BAG_CAPACITY -> phase == AdventurePhase.DEPARTING
    AdventureTraitEffectKind.SALE -> phase == AdventurePhase.SELLING
    AdventureTraitEffectKind.SHOP_REVIEW -> phase == AdventurePhase.SHOPPING || phase == AdventurePhase.SHOPPING_RESULT || phase == AdventurePhase.SHOPPING_EMPTY
    AdventureTraitEffectKind.RETRY, AdventureTraitEffectKind.DIALOGUE -> phase == AdventurePhase.EVENT || phase == AdventurePhase.EVENT_RESULT
    AdventureTraitEffectKind.RELATIONSHIP -> phase == AdventurePhase.RELATIONSHIP || phase == AdventurePhase.RELATIONSHIP_RESULT
}

@Composable
internal fun AdventureTraitActivationStrip(state: SimpleGameState, now: Long) {
    val language = LocalAppLanguage.current
    val retryStarted = rememberAdventureRetryStarted(state, now)
    val activations = state.adventureTraits.visibleActivations.filter {
        AdventureTraitCatalog.find(it.traitId) != null && adventureTraitEffectVisibleInPhase(it.effectKind, state.adventurePhase)
            && (it.effectKind != AdventureTraitEffectKind.RETRY || state.adventurePhase != AdventurePhase.EVENT || retryStarted)
    }
    if (activations.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .background(AqSurfaceHigh, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        activations.takeLast(2).forEach { activation ->
            MaterialText(
                "${AdventureTraitCatalog.definition(activation.traitId).name.inLanguage(language)} · ${adventureTraitActivationText(activation, language)}",
                color = AqGold, fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
        if (activations.size > 2) MaterialText(
            journeyText(language, "다른 특성의 영향도 최근 사건에 남았습니다.", "Other trait effects are recorded in recent events.", "ほかの特性の影響も最近の出来事に残っています。"),
            color = AqMuted, fontSize = 10.sp, lineHeight = 14.sp,
        )
    }
}

internal data class AdventureTraitListItem(
    val traitId: String,
    val name: String,
    val advantage: String,
    val disadvantage: String,
    val shaky: Boolean,
    val status: String,
) {
    val description: String
        get() = listOf(advantage, disadvantage).filter(String::isNotBlank).joinToString("\n")
}

internal val AdventureTraitAdvantageColor = Color(0xFF82C7FF)
internal val AdventureTraitDisadvantageColor = Color(0xFFFF8C99)

internal fun adventureTraitListItems(
    state: SimpleGameState,
    language: AppLanguage,
): List<AdventureTraitListItem> = state.adventureTraits.owned
    .sortedByDescending { it.acquisitionSequence }
    .mapNotNull { owned ->
        AdventureTraitCatalog.find(owned.traitId)?.let { definition ->
            AdventureTraitListItem(
                traitId = definition.id,
                name = definition.name.inLanguage(language),
                advantage = definition.advantage.inLanguage(language),
                disadvantage = definition.disadvantage.inLanguage(language),
                shaky = owned.shaky,
                status = if (owned.shaky) journeyText(language, "흔들리는 중", "Wavering", "揺らぎ中") else "",
            )
        }
    }

@Composable
internal fun AdventureTraitProfile(state: SimpleGameState) {
    val language = LocalAppLanguage.current
    val traits = adventureTraitListItems(state, language)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (traits.isEmpty()) {
            MaterialText(
                text = journeyText(
                    language,
                    "여러 경험을 쌓으며 특성이 생기고 변합니다.",
                    "Traits develop and change through varied experiences.",
                    "さまざまな経験を重ねると、特性が生まれ、変わっていきます。",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AqSurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = AqMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        } else {
            traits.forEach { trait ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AqSurfaceHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = journeyText(
                                language,
                                "${trait.name}${if (trait.shaky) ", ${trait.status}" else ""}, 장점 ${trait.advantage}, 단점 ${trait.disadvantage}",
                                "${trait.name}${if (trait.shaky) ", ${trait.status}" else ""}, advantage: ${trait.advantage}, drawback: ${trait.disadvantage}",
                                "${trait.name}${if (trait.shaky) "、${trait.status}" else ""}、長所 ${trait.advantage}、短所 ${trait.disadvantage}",
                            )
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialText(
                            text = trait.name,
                            modifier = Modifier.weight(1f),
                            color = AqText,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (trait.shaky) {
                            Spacer(Modifier.width(8.dp))
                            MaterialText(
                                text = trait.status,
                                color = AqGold,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    MaterialText(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = AdventureTraitAdvantageColor)) {
                                append(trait.advantage)
                            }
                            if (trait.advantage.isNotBlank() && trait.disadvantage.isNotBlank()) {
                                append("\n")
                            }
                            withStyle(SpanStyle(color = AdventureTraitDisadvantageColor)) {
                                append(trait.disadvantage)
                            }
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
