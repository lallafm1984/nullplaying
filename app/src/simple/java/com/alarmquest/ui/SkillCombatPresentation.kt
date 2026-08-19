package com.alarmquest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.alarmquest.engine.SkillCatalog
import com.alarmquest.engine.SkillDefinition
import com.alarmquest.engine.SkillElement
import com.alarmquest.engine.SkillFinisher
import com.alarmquest.engine.SkillMotion
import com.alarmquest.model.HeroClass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// The engine still owns a 1,400 ms attack boundary.  Keep a short clean frame at the end,
// but give late multi-hit finishers enough room to complete their authored afterglow.
internal const val SKILL_PRESENTATION_DURATION_MILLIS = 1_350
internal const val SKILL_VFX_END_MILLIS = 1_300
internal const val SKILL_VFX_CLEAN_TAIL_MILLIS = 60
internal const val SKILL_VFX_LAST_VISIBLE_MILLIS = SKILL_VFX_END_MILLIS - SKILL_VFX_CLEAN_TAIL_MILLIS
internal const val MIN_DAMAGE_READ_MILLIS = 72
internal const val DAMAGE_FRAME_HANDOFF_MILLIS = 24
internal const val MIN_PRESENTATION_HIT_GAP_MILLIS =
    MIN_DAMAGE_READ_MILLIS + DAMAGE_FRAME_HANDOFF_MILLIS

internal data class SkillDamageFrame(
    val visible: Boolean,
    val damage: Long,
    val alpha: Float,
    val scale: Float,
    val translationY: Float,
    val isFinal: Boolean,
)

internal data class SkillCameraFrame(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
)

internal data class SkillPresentationHit(
    val timingMillis: Int,
    val sourceHitIndices: List<Int>,
    val weight: Int,
)

internal fun isSteelSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t01_c01"

internal fun isFierceDownwardStrike(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t01_c02"

internal fun isChargingThrust(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t01_c03"

internal fun isGroundImpact(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t01_c04"

internal fun isContinuousSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t01_c05"

internal fun isHalfMoonSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t02_c01"

internal fun isArmorShatter(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t02_c02"

internal fun isFrontlineBreakthrough(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t02_c03"

internal fun isStoneDustBurst(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t02_c04"

internal fun isTripleSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t02_c05"

internal fun isBattlefieldCleave(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t03_c01"

internal fun isIronWallSmash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t03_c02"

internal fun isFuriousAdvance(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t03_c03"

internal fun isEarthFissure(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t03_c04"

internal fun isBeastFrenzy(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t03_c05"

internal fun isBloodWindSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t04_c01"

internal fun isHelmetCrusher(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t04_c02"

internal fun isCastleBreakerCharge(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t04_c03"

internal fun isRockEruption(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t04_c04"

internal fun isFiveStrike(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t04_c05"

internal fun isSwordLightSever(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t05_c01"

internal fun isBattleAxeDescent(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t05_c02"

internal fun isWedgeBreakthrough(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t05_c03"

internal fun isSeismicWave(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t05_c04"

internal fun isBerserkerChainSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t05_c05"

internal fun isRotatingSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t06_c01"

internal fun isGiantHammer(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t06_c02"

internal fun isIroncladCharge(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t06_c03"

internal fun isFaultShatter(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t06_c04"

internal fun isStormFrenzy(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t06_c05"

internal fun isLionSlash(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t07_c01"
internal fun isBoneCrushingBlow(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t07_c02"
internal fun isLightningBreakthrough(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t07_c03"
internal fun isMountainFist(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t07_c04"
internal fun isCrimsonCombo(definition: SkillDefinition): Boolean =
    definition.catalogId == "warrior_t07_c05"

internal fun isValiantCleave(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t08_c01"
internal fun isGateDestroyer(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t08_c02"
internal fun isChariotCharge(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t08_c03"
internal fun isEarthRoar(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t08_c04"
internal fun isSevenSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t08_c05"

internal fun isSteelWhirlwind(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t09_c01"
internal fun isSmashToPieces(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t09_c02"
internal fun isIndomitableAdvance(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t09_c03"
internal fun isRiftExplosion(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t09_c04"
internal fun isFrenzyBlades(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t09_c05"

internal fun isKingdomSever(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t10_c01"
internal fun isThunderDownstrike(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t10_c02"
internal fun isVanguardBreakthrough(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t10_c03"
internal fun isLeylineEruption(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t10_c04"
internal fun isBloodFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t10_c05"

internal fun isSwordEmperorHalfMoon(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t11_c01"
internal fun isCastleCrushingGreatSmash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t11_c02"
internal fun isUnbeatenCharge(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t11_c03"
internal fun isContinentalFissure(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t11_c04"
internal fun isHotWindChainSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t11_c05"

internal fun isTitanCleave(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t12_c01"
internal fun isCometSmash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t12_c02"
internal fun isKingsAdvance(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t12_c03"
internal fun isMountainCollapse(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t12_c04"
internal fun isHundredBattleFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t12_c05"

internal fun isWarGodBlade(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t13_c01"
internal fun isDragonBoneShatter(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t13_c02"
internal fun isIronBloodBreakthrough(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t13_c03"
internal fun isEarthRage(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t13_c04"
internal fun isTyrantCombo(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t13_c05"

internal fun isSkySever(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t14_c01"
internal fun isCliffDescent(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t14_c02"
internal fun isLegionCharge(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t14_c03"
internal fun isCrustExplosion(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t14_c04"
internal fun isInfiniteSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t14_c05"

internal fun isGoldenLionSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t15_c01"
internal fun isStarIronShatter(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t15_c02"
internal fun isEmperorCharge(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t15_c03"
internal fun isWorldFissure(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t15_c04"
internal fun isMeteorFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t15_c05"

internal fun isStormKingSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t16_c01"
internal fun isJudgmentSmash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t16_c02"
internal fun isCitadelPierce(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t16_c03"
internal fun isEarthDoom(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t16_c04"
internal fun isTwelveSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t16_c05"

internal fun isDragonSlayerCleave(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t17_c01"
internal fun isMountTaiCollapse(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t17_c02"
internal fun isMythBreakthrough(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t17_c03"
internal fun isHeavenEarthShatter(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t17_c04"
internal fun isBerserkerGodFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t17_c05"

internal fun isOverlordSever(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t18_c01"
internal fun isDoomHammer(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t18_c02"
internal fun isWarKingAdvance(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t18_c03"
internal fun isContinentCollapse(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t18_c04"
internal fun isBloodWindCombo(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t18_c05"

internal fun isWorldSplit(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t19_c01"
internal fun isStarBreakingStrike(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t19_c02"
internal fun isInvincibleGrandCharge(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t19_c03"
internal fun isWorldAxisCollapse(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t19_c04"
internal fun isHundredLotusSwordDance(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t19_c05"

internal fun isEndSword(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t20_c01"
internal fun isDivineShatter(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t20_c02"
internal fun isLastVanguard(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t20_c03"
internal fun isCreationEarthquake(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t20_c04"
internal fun isInfiniteFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t20_c05"

internal fun isQuickStab(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t01_c01" || definition.name == "빠른 찌르기"
internal fun isShadowSlash(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t01_c02" || definition.name == "그림자 베기"
internal fun isVenomFangStab(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t01_c03" || definition.name == "독니 찌르기"
internal fun isAnkleTrap(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t01_c04" || definition.name == "발목 덫"
internal fun isVitalSlash(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t01_c05" || definition.name == "급소 베기"
internal fun isTwinFangCombo(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t02_c01" || definition.name == "쌍아 연격"
internal fun isAmbush(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t02_c02" || definition.name == "암습"
internal fun isGreenVenomBurst(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t02_c03" || definition.name == "녹독 파열"
internal fun isWireSever(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t02_c04" || definition.name == "철사 절단"
internal fun isThroatEnd(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t02_c05" || definition.name == "숨통 끊기"
internal fun isTripleStab(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t03_c01" || definition.name == "삼연 찌르기"
internal fun isAfterimageAssault(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t03_c02" || definition.name == "잔영 습격"
internal fun isVenomTwinNeedles(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t03_c03" || definition.name == "맹독 쌍침"
internal fun isLassoStrike(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t03_c04" || definition.name == "올가미 강타"
internal fun isHeartStab(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t03_c05" || definition.name == "심장 찌르기"
internal fun isDaggerFrenzy(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t04_c01" || definition.name == "비수 난무"
internal fun isDarkLeap(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t04_c02" || definition.name == "어둠 도약"
internal fun isPoisonMistBlast(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t04_c03" || definition.name == "독안개 폭침"
internal fun isBladeTrap(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t04_c04" || definition.name == "칼날 덫"
internal fun isSilentExecution(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t04_c05" || definition.name == "무음 처형"
internal fun isCrescentDagger(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t05_c01" || definition.name == "초승달 단검"
internal fun isShadowCross(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t05_c02" || definition.name == "그림자 교차"
internal fun isPoisonSpray(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t05_c03" || definition.name == "독액 분사"
internal fun isSilverWireBinding(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t05_c04" || definition.name == "은사 포박"
internal fun isCrimsonVital(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t05_c05" || definition.name == "붉은 급소"
internal fun isGaleTwinBlades(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t06_c01" || definition.name == "질풍 쌍검"
internal fun isNightRaid(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t06_c02" || definition.name == "야행 습격"
internal fun isCorrosionBurst(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t06_c03" || definition.name == "부식 파열"
internal fun isSpinningLasso(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t06_c04" || definition.name == "회전 올가미"
internal fun isBlindSpotStrike(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t06_c05" || definition.name == "사각 일격"
internal fun isFourWayDaggers(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t07_c01" || definition.name == "네 갈래 비수"
internal fun isBlackShadowSlash(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t07_c02" || definition.name == "흑영 베기"
internal fun isDeadlyPoisonNeedles(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t07_c03" || definition.name == "사독 연침"
internal fun isChainTrap(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t07_c04" || definition.name == "사슬 덫"
internal fun isFatalSever(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t07_c05" || definition.name == "치명 절단"
internal fun isGhostCarve(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t08_c01" || definition.name == "유령 난도"
internal fun isAfterimageLeap(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t08_c02" || definition.name == "잔상 도약"
internal fun isSerpentFang(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t08_c03" || definition.name == "독사 송곳니"
internal fun isSteelThreadLine(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t08_c04" || definition.name == "강철 실선"
internal fun isSoulStab(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t08_c05" || definition.name == "영혼 찌르기"
internal fun isBloodTwinFangs(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t09_c01"
internal fun isMoonlightAmbush(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t09_c02"
internal fun isGreenPoisonExplosion(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t09_c03"
internal fun isExecutionLasso(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t09_c04"
internal fun isMidnightVital(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t09_c05"
internal fun isStormDaggers(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t10_c01"
internal fun isShadowCloneSlash(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t10_c02"
internal fun isRoyalPoisonChain(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t10_c03"
internal fun isBladePrison(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t10_c04"
internal fun isDeathStrike(definition: SkillDefinition): Boolean = definition.catalogId == "rogue_t10_c05"

internal fun isAimedShot(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t01_c01" || definition.name == "정조준 사격"
internal fun isTripleArrow(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t01_c02" || definition.name == "세 갈래 화살"
internal fun isBreezeArrow(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t01_c03" || definition.name == "산들 화살"
internal fun isThornTrap(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t01_c04" || definition.name == "가시 덫"
internal fun isMoonlightArrow(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t01_c05" || definition.name == "달빛 화살"
internal fun isPiercingShot(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t02_c01" || definition.name == "관통 사격"
internal fun isRapidShot(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t02_c02" || definition.name == "연속 사격"
internal fun isGustBowstring(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t02_c03" || definition.name == "돌풍 시위"
internal fun isWolfFang(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t02_c04" || definition.name == "늑대 엄니"
internal fun isStardustShot(definition: SkillDefinition): Boolean = definition.catalogId == "ranger_t02_c05" || definition.name == "별가루 사격"

internal data class SkillPalette(
    val backdrop: Color,
    val primary: Color,
    val secondary: Color,
    val damage: Color,
    val maxBackdropAlpha: Float,
)

internal fun skillDefinition(catalogId: String): SkillDefinition? = SkillCatalog.find(catalogId)

internal fun splitSkillDamage(totalDamage: Long, definition: SkillDefinition): List<Long> =
    SkillCatalog.splitDamage(totalDamage, definition.hitWeights)

/**
 * Keeps every authored VFX hit intact while coalescing only the damage-number and energy
 * presentation.  Endpoints are selected from the final hit backwards so a grouped value never
 * appears before any of the impacts it represents, and adjacent presentation hits always leave
 * a full readable frame plus the existing 24 ms handoff.
 */
internal fun skillPresentationHits(definition: SkillDefinition): List<SkillPresentationHit> {
    val timings = definition.hitTimingsMillis
    if (timings.size == 1) {
        return listOf(SkillPresentationHit(timings.single(), listOf(0), definition.hitWeights.single()))
    }

    val endpointIndicesReversed = mutableListOf(timings.lastIndex)
    var nextEndpointTiming = timings.last()
    for (index in timings.lastIndex - 1 downTo 0) {
        if (nextEndpointTiming - timings[index] >= MIN_PRESENTATION_HIT_GAP_MILLIS) {
            endpointIndicesReversed += index
            nextEndpointTiming = timings[index]
        }
    }
    val endpointIndices = endpointIndicesReversed.asReversed()
    var firstSourceIndex = 0
    return endpointIndices.map { endpointIndex ->
        val sourceIndices = (firstSourceIndex..endpointIndex).toList()
        firstSourceIndex = endpointIndex + 1
        SkillPresentationHit(
            timingMillis = timings[endpointIndex],
            sourceHitIndices = sourceIndices,
            weight = sourceIndices.sumOf(definition.hitWeights::get),
        )
    }
}

internal fun groupedSkillDamage(totalDamage: Long, definition: SkillDefinition): List<Long> {
    val rawDamages = splitSkillDamage(totalDamage, definition)
    return skillPresentationHits(definition).map { hit ->
        hit.sourceHitIndices.sumOf(rawDamages::get)
    }
}

internal fun skillLabelAlpha(elapsedMillis: Int, definition: SkillDefinition): Float {
    if (elapsedMillis !in 0 until SKILL_VFX_END_MILLIS) return 0f
    if (isSteelSlash(definition)) {
        return when {
            elapsedMillis < 260 -> 0f
            elapsedMillis < 340 -> easeOut(fraction(elapsedMillis, 260, 340))
            elapsedMillis < 590 -> 1f
            elapsedMillis < 680 -> 1f - fraction(elapsedMillis, 590, 680)
            else -> 0f
        }
    }
    val firstHit = definition.hitTimingsMillis.first()
    val enterStart = (firstHit - 140).coerceAtLeast(0)
    val enterEnd = (enterStart + 80).coerceAtMost(firstHit)
    val exitEnd = minOf(skillFinalDamageEndMillis(definition), enterStart + 420)
    val exitStart = (exitEnd - 90).coerceAtLeast(enterEnd)
    return when {
        elapsedMillis < enterStart -> 0f
        elapsedMillis < enterEnd -> fraction(elapsedMillis, enterStart, enterEnd)
        elapsedMillis < exitStart -> 1f
        elapsedMillis < exitEnd -> 1f - fraction(elapsedMillis, exitStart, exitEnd)
        else -> 0f
    }
}

internal fun skillFinalDamageEndMillis(definition: SkillDefinition): Int =
    if (isSteelSlash(definition)) {
        820
    } else {
        minOf(definition.hitTimingsMillis.last() + 420, SKILL_VFX_END_MILLIS)
    }

internal fun skillDamageFrame(
    elapsedMillis: Int,
    definition: SkillDefinition,
    totalDamage: Long,
): SkillDamageFrame {
    val rawDamages = splitSkillDamage(totalDamage, definition)
    if (isSteelSlash(definition)) {
        val impactAt = definition.hitTimingsMillis.single()
        if (elapsedMillis < impactAt || elapsedMillis >= 820) {
            return SkillDamageFrame(false, 0L, 0f, 1f, 0f, true)
        }
        val alpha = when {
            elapsedMillis < 452 -> 0.70f + 0.30f * easeOut(fraction(elapsedMillis, impactAt, 452))
            elapsedMillis < 700 -> 1f
            else -> 1f - fraction(elapsedMillis, 700, 820)
        }
        val scale = when {
            elapsedMillis < 452 -> 0.94f + 0.16f * easeOut(fraction(elapsedMillis, impactAt, 452))
            elapsedMillis < 520 -> 1.10f - 0.10f * easeOut(fraction(elapsedMillis, 452, 520))
            else -> 1f
        }
        val translationY = if (elapsedMillis < 700) 0f else -6f * fraction(elapsedMillis, 700, 820)
        return SkillDamageFrame(true, rawDamages.single(), alpha, scale, translationY, true)
    }
    val presentationHits = skillPresentationHits(definition)
    val timings = presentationHits.map(SkillPresentationHit::timingMillis)
    val damages = groupedSkillDamage(totalDamage, definition)
    val hitIndex = timings.indices.lastOrNull { elapsedMillis >= timings[it] }
        ?: return SkillDamageFrame(false, 0L, 0f, 1f, 0f, false)
    val isFinal = hitIndex == timings.lastIndex
    val plannedDuration = if (isFinal) 420 else when (presentationHits.size) {
        2 -> 280
        3 -> 220
        4 -> 170
        else -> 140
    }
    val nextBoundary = timings.getOrNull(hitIndex + 1)?.minus(DAMAGE_FRAME_HANDOFF_MILLIS)
        ?: skillFinalDamageEndMillis(definition)
    val end = minOf(timings[hitIndex] + plannedDuration, nextBoundary, SKILL_VFX_END_MILLIS)
    if (end <= timings[hitIndex] || elapsedMillis >= end) {
        return SkillDamageFrame(false, 0L, 0f, 1f, 0f, isFinal)
    }
    val progress = fraction(elapsedMillis, timings[hitIndex], end)
    val enterEnd = if (isFinal) 0.18f else 0.30f
    val settleEnd = if (isFinal) 0.38f else 0.55f
    val exitStart = if (isFinal) 0.72f else 0.70f
    val alpha = when {
        progress < enterEnd -> progress / enterEnd
        progress < exitStart -> 1f
        else -> 1f - (progress - exitStart) / (1f - exitStart)
    }.coerceIn(0f, 1f)
    val peakScale = if (isFinal) 1.15f else 1.08f
    val scale = when {
        progress < enterEnd -> 0.84f + (peakScale - 0.84f) * (progress / enterEnd)
        progress < settleEnd -> peakScale - (peakScale - 1f) * fraction(progress, enterEnd, settleEnd)
        progress < exitStart -> 1f
        else -> 1f - 0.04f * fraction(progress, exitStart, 1f)
    }
    val translationY = when {
        progress < enterEnd -> 8f * (1f - progress / enterEnd)
        progress < exitStart -> 0f
        else -> -if (isFinal) 16f else 10f * fraction(progress, exitStart, 1f)
    }
    return SkillDamageFrame(
        visible = true,
        damage = damages[hitIndex],
        alpha = alpha,
        scale = scale,
        translationY = translationY,
        isFinal = isFinal,
    )
}

internal fun skillEnergyFraction(
    elapsedMillis: Int,
    startFraction: Float,
    endFraction: Float,
    definition: SkillDefinition,
    reducedMotion: Boolean = false,
): Float {
    val start = startFraction.coerceIn(0f, 1f)
    val end = endFraction.coerceIn(0f, 1f)
    val presentationHits = skillPresentationHits(definition)
    if (reducedMotion) {
        var cumulativeWeight = 0
        presentationHits.forEachIndexed { index, hit ->
            if (elapsedMillis < hit.timingMillis) {
                return if (index == 0) start else {
                    start + (end - start) * cumulativeWeight.toFloat() / 100f
                }
            }
            cumulativeWeight += hit.weight
        }
        return end
    }
    var previous = start
    var cumulativeWeight = 0
    presentationHits.forEachIndexed { index, hit ->
        cumulativeWeight += hit.weight
        val target = if (index == presentationHits.lastIndex) {
            end
        } else {
            start + (end - start) * cumulativeWeight.toFloat() / 100f
        }
        val duration = if (index == presentationHits.lastIndex) 96 else MIN_DAMAGE_READ_MILLIS
        if (elapsedMillis < hit.timingMillis) return previous.coerceIn(0f, 1f)
        if (elapsedMillis < hit.timingMillis + duration) {
            val step = fraction(elapsedMillis, hit.timingMillis, hit.timingMillis + duration)
            return (previous + (target - previous) * easeOut(step)).coerceIn(0f, 1f)
        }
        previous = target
    }
    return end
}

internal fun skillCameraFrame(elapsedMillis: Int, definition: SkillDefinition): SkillCameraFrame {
    if (isSteelSlash(definition)) {
        return when (elapsedMillis) {
            in 360 until 404 -> SkillCameraFrame(
                scale = 1f + 0.004f * fraction(elapsedMillis, 360, 404),
            )
            in 404 until 420 -> {
                val progress = fraction(elapsedMillis, 404, 420)
                SkillCameraFrame(
                    translationX = -3.6f * progress,
                    scale = 1.004f + 0.012f * progress,
                )
            }
            in 420 until 452 -> {
                val progress = easeOut(fraction(elapsedMillis, 420, 452))
                SkillCameraFrame(
                    translationX = -3.6f + 4.8f * progress,
                    scale = 1.016f - 0.006f * progress,
                )
            }
            in 452 until 520 -> {
                val progress = easeOut(fraction(elapsedMillis, 452, 520))
                SkillCameraFrame(
                    translationX = 1.2f * (1f - progress),
                    scale = 1.010f - 0.010f * progress,
                )
            }
            else -> SkillCameraFrame()
        }
    }
    if (isFierceDownwardStrike(definition)) {
        return when (elapsedMillis) {
            in 330 until 390 -> SkillCameraFrame(
                scale = 1f + 0.004f * fraction(elapsedMillis, 330, 390),
            )
            in 390 until 412 -> {
                val progress = fraction(elapsedMillis, 390, 412)
                SkillCameraFrame(
                    translationY = -2.0f * progress,
                    scale = 1.004f + 0.006f * progress,
                )
            }
            in 412 until 420 -> {
                val progress = fraction(elapsedMillis, 412, 420)
                SkillCameraFrame(
                    translationY = -2.0f + 6.0f * progress * progress * progress,
                    scale = 1.010f + 0.004f * progress,
                )
            }
            in 420 until 468 -> {
                val progress = easeOut(fraction(elapsedMillis, 420, 468))
                SkillCameraFrame(
                    translationY = 4.0f * (1f - progress),
                    scale = 1.014f - 0.014f * progress,
                )
            }
            else -> SkillCameraFrame()
        }
    }
    if (
        isChargingThrust(definition) ||
        isFrontlineBreakthrough(definition) ||
        isFuriousAdvance(definition) ||
        isCastleBreakerCharge(definition) ||
        isWedgeBreakthrough(definition) ||
        isIroncladCharge(definition) ||
        isLightningBreakthrough(definition) ||
        isChariotCharge(definition) ||
        isIndomitableAdvance(definition) ||
        isVanguardBreakthrough(definition) ||
        isUnbeatenCharge(definition) ||
        isKingsAdvance(definition) ||
        isIronBloodBreakthrough(definition) ||
        isLegionCharge(definition) ||
        isEmperorCharge(definition) ||
        isCitadelPierce(definition) ||
        isMythBreakthrough(definition) ||
        isWarKingAdvance(definition) ||
        isInvincibleGrandCharge(definition) ||
        isLastVanguard(definition)
    ) {
        return when (elapsedMillis) {
            in 350 until 400 -> SkillCameraFrame(
                translationX = -1.6f * fraction(elapsedMillis, 350, 400),
                scale = 1f + 0.006f * fraction(elapsedMillis, 350, 400),
            )
            in 400 until 420 -> {
                val progress = fraction(elapsedMillis, 400, 420)
                SkillCameraFrame(
                    translationX = -1.6f + 5.2f * progress,
                    scale = 1.006f + 0.008f * progress,
                )
            }
            in 420 until 470 -> {
                val progress = easeOut(fraction(elapsedMillis, 420, 470))
                SkillCameraFrame(
                    translationX = 3.6f * (1f - progress),
                    scale = 1.014f - 0.014f * progress,
                )
            }
            else -> SkillCameraFrame()
        }
    }
    if (definition.heroClass == HeroClass.WARRIOR && definition.candidate == 3) {
        val impactAt = skillPresentationHits(definition).last().timingMillis
        return when (elapsedMillis) {
            in (impactAt - 70) until (impactAt - 18) -> SkillCameraFrame(
                scale = 1f + .007f * fraction(elapsedMillis, impactAt - 70, impactAt - 18),
            )
            in (impactAt - 18) until impactAt -> {
                val progress = fraction(elapsedMillis, impactAt - 18, impactAt)
                SkillCameraFrame(
                    translationY = -2.2f * progress,
                    scale = 1.007f + .011f * progress,
                )
            }
            in impactAt until (impactAt + 110) -> {
                val progress = fraction(elapsedMillis, impactAt, impactAt + 110)
                val damping = (1f - progress) * (1f - progress)
                SkillCameraFrame(
                    translationX = sin((progress * PI * 5f).toFloat()) * 4.4f * damping,
                    translationY = cos((progress * PI * 4f).toFloat()) * 4.8f * damping,
                    scale = 1.018f - .018f * easeOut(progress),
                )
            }
            else -> SkillCameraFrame()
        }
    }
    val action = semanticVfxPlan(definition).action
    val amplitude = when (action) {
        SemanticVfxAction.DESCEND,
        SemanticVfxAction.ASCEND,
        SemanticVfxAction.COLLAPSE,
        -> 4.2f
        SemanticVfxAction.CHARGE,
        SemanticVfxAction.DIVE,
        SemanticVfxAction.EXECUTE,
        -> 3.6f
        SemanticVfxAction.CUT,
        SemanticVfxAction.CROSS_CUT,
        SemanticVfxAction.PIERCE,
        SemanticVfxAction.BEAM,
        SemanticVfxAction.SHOT,
        SemanticVfxAction.CHAIN,
        -> 2.8f
        else -> 2.1f
    } * floatArrayOf(0.50f, 0.64f, 0.77f, 0.90f, 1.03f)[definition.intensityTier.coerceIn(1, 5) - 1]
    // Camera impulses share the exact same readable endpoints as damage and energy. Raw hits
    // suppressed into a nearby presentation group must not leak an extra shake one frame early.
    val presentationHits = skillPresentationHits(definition)
    presentationHits.indices.reversed().forEach { index ->
        val timing = presentationHits[index].timingMillis
        val zoomStart = timing - 30
        val shakeEnd = timing + if (index == presentationHits.lastIndex) 120 else 70
        val zoomEnd = timing + 120
        if (elapsedMillis in zoomStart..zoomEnd) {
            val shakeProgress = fraction(elapsedMillis, timing, shakeEnd)
            val finalBoost = if (index == presentationHits.lastIndex) 1.15f else {
                when (presentationHits.size) {
                    1 -> 1f
                    2 -> 0.82f
                    3 -> 0.70f
                    4 -> 0.60f
                    else -> 0.52f
                }
            }
            val wave = if (elapsedMillis < timing || elapsedMillis > shakeEnd) {
                0f
            } else {
                val oscillation = sin((shakeProgress * PI * 2.5).toFloat())
                val guaranteedImpulse = if (shakeProgress < 0.32f) 0.18f * (1f - shakeProgress / 0.32f) else 0f
                (oscillation + guaranteedImpulse) * (1f - shakeProgress) * (1f - shakeProgress)
            }
            val strength = (amplitude * finalBoost).coerceAtMost(5.2f)
            val vertical = action in setOf(
                SemanticVfxAction.DESCEND,
                SemanticVfxAction.ASCEND,
                SemanticVfxAction.DIVE,
                SemanticVfxAction.VOLLEY,
            )
            val pulseOnly = action in setOf(
                SemanticVfxAction.BURST,
                SemanticVfxAction.SPIN,
                SemanticVfxAction.COLLAPSE,
                SemanticVfxAction.TRAP,
            )
            val zoomProgress = if (elapsedMillis < timing) {
                fraction(elapsedMillis, zoomStart, timing)
            } else {
                1f - fraction(elapsedMillis, timing, zoomEnd)
            }
            return SkillCameraFrame(
                translationX = if (vertical || pulseOnly) 0f else wave * strength,
                translationY = if (vertical && !pulseOnly) wave * strength else 0f,
                scale = 1f + zoomProgress.coerceIn(0f, 1f) * 0.018f *
                    finalBoost.coerceAtMost(1f) *
                    floatArrayOf(0.42f, 0.58f, 0.73f, 0.88f, 1f)[definition.intensityTier.coerceIn(1, 5) - 1],
            )
        }
    }
    return SkillCameraFrame()
}

internal fun skillPalette(element: SkillElement): SkillPalette = when (element) {
    SkillElement.PHYSICAL -> SkillPalette(Color(0xFF707784), Color(0xFFF6F0E7), Color(0xFFA9B2C2), Color(0xFFF6F0E7), 0.09f)
    SkillElement.FIRE -> SkillPalette(Color(0xFF6A1715), Color(0xFFFFB45F), Color(0xFFE6612C), Color(0xFFFFB45F), 0.14f)
    SkillElement.ICE -> SkillPalette(Color(0xFF123652), Color(0xFFB9EDFF), Color(0xFF72D4FF), Color(0xFFB9EDFF), 0.13f)
    SkillElement.LIGHTNING -> SkillPalette(Color(0xFF182858), Color(0xFFDDF7FF), Color(0xFF8DBFFF), Color(0xFFDDF7FF), 0.12f)
    SkillElement.ARCANE -> SkillPalette(Color(0xFF243A78), Color(0xFFD3B6FF), Color(0xFF845CCB), Color(0xFFD3B6FF), 0.14f)
    SkillElement.DARK -> SkillPalette(Color(0xFF190C24), Color(0xFFE0A7FF), Color(0xFF52216B), Color(0xFFE0A7FF), 0.14f)
    SkillElement.POISON -> SkillPalette(Color(0xFF102B19), Color(0xFFB8E879), Color(0xFF70B84F), Color(0xFFB8E879), 0.14f)
    SkillElement.WIND -> SkillPalette(Color(0xFF123B3A), Color(0xFFA7E9DF), Color(0xFF69D3C9), Color(0xFFA7E9DF), 0.11f)
    SkillElement.EARTH -> SkillPalette(Color(0xFF2B1C12), Color(0xFFE2BE79), Color(0xFFA87B3E), Color(0xFFE2BE79), 0.14f)
    SkillElement.HOLY -> SkillPalette(Color(0xFF3B2C10), Color(0xFFFFF0AD), Color(0xFFF0C454), Color(0xFFFFF0AD), 0.12f)
    SkillElement.COSMIC -> SkillPalette(Color(0xFF17183D), Color(0xFFD9D8FF), Color(0xFF7774D8), Color(0xFFD9D8FF), 0.14f)
}

@Composable
internal fun SkillEffectLayer(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    ModularSkillEffectLayer(definition, elapsedMillis, reducedMotion, modifier)
}

private fun DrawScope.drawMotion(
    definition: SkillDefinition,
    palette: SkillPalette,
    progress: Float,
    hitIndex: Int,
    drawParticles: Boolean,
    organicAtlas: ImageBitmap,
) {
    val center = Offset(size.width * 0.5f, size.height * 0.62f)
    val enter = (progress / 0.58f).coerceIn(0f, 1f)
    val fade = (1f - (progress - 0.62f) / 0.38f).coerceIn(0f, 1f)
    val alpha = minOf(enter, fade)
    val direction = if ((definition.effectVariant + hitIndex) % 2 == 0) 1f else -1f
    val primary = palette.primary.copy(alpha = alpha * 0.94f)
    val secondary = palette.secondary.copy(alpha = alpha * 0.70f)
    when (definition.motion) {
        SkillMotion.CLEAVE_HORIZONTAL -> {
            val halfWidth = size.width * 0.35f * enter
            drawBladeSweep(
                start = center - Offset(halfWidth, size.height * 0.055f * direction),
                end = center + Offset(halfWidth, size.height * 0.055f * direction),
                width = size.minDimension * (0.11f + definition.intensityTier * 0.008f),
                primary = primary,
                highlight = secondary,
            )
        }
        SkillMotion.CROSS_SLASH -> {
            val halfWidth = size.width * 0.29f * enter
            val halfHeight = size.height * 0.22f * direction
            drawBladeSweep(
                center - Offset(halfWidth, halfHeight),
                center + Offset(halfWidth, halfHeight),
                size.minDimension * 0.12f,
                primary,
                secondary,
            )
            if (hitIndex == definition.hitTimingsMillis.lastIndex && progress > 0.20f) {
                val secondEnter = ((progress - 0.20f) / 0.45f).coerceIn(0f, 1f)
                drawBladeSweep(
                    center - Offset(halfWidth * secondEnter, -halfHeight * secondEnter),
                    center + Offset(halfWidth * secondEnter, -halfHeight * secondEnter),
                    size.minDimension * 0.09f,
                    secondary,
                    primary.copy(alpha = primary.alpha * 0.66f),
                )
            }
        }
        SkillMotion.RAPID_THREE,
        SkillMotion.FRENZY_FIVE,
        -> {
            val lane = ((hitIndex % 3) - 1) * size.height * 0.08f
            val halfWidth = size.width * if (definition.motion == SkillMotion.FRENZY_FIVE) 0.25f else 0.29f
            val halfHeight = size.height * 0.16f * direction
            drawBladeSweep(
                center - Offset(halfWidth * enter, halfHeight) + Offset(0f, lane),
                center + Offset(halfWidth * enter, halfHeight) + Offset(0f, lane),
                size.minDimension * if (definition.motion == SkillMotion.FRENZY_FIVE) 0.085f else 0.105f,
                primary,
                secondary,
            )
        }
        SkillMotion.SPIN_CUT -> {
            val radius = size.minDimension * (0.18f + 0.16f * enter)
            drawArc(
                color = primary.copy(alpha = primary.alpha * 0.28f),
                startAngle = 160f + direction * 18f,
                sweepAngle = direction * (220f + definition.effectVariant * 18f) * enter,
                useCenter = true,
                topLeft = center - Offset(radius, radius),
                size = Size(radius * 2f, radius * 2f),
            )
            drawCircle(secondary.copy(alpha = secondary.alpha * 0.32f), radius * 0.38f, center)
        }
        SkillMotion.HEAVY_FALL -> {
            val impactY = center.y - size.height * 0.42f + size.height * 0.42f * enter
            drawRibbon(
                Offset(center.x - size.width * 0.07f * direction, impactY),
                Offset(center.x + size.width * 0.035f * direction, center.y + size.height * 0.17f),
                size.minDimension * 0.045f,
                size.minDimension * 0.13f,
                primary,
            )
            drawImpactShards(center, palette, enter, definition.effectVariant + hitIndex, 5)
        }
        SkillMotion.PILLAR_DROP -> {
            val y = center.y - size.height * 0.58f + size.height * 0.58f * enter
            drawRibbon(
                Offset(center.x, y),
                Offset(center.x, center.y + size.height * 0.22f),
                size.minDimension * 0.12f,
                size.minDimension * 0.25f,
                primary.copy(alpha = primary.alpha * 0.78f),
            )
            drawSoftGlow(center, size.minDimension * 0.26f * enter, secondary, alpha * 0.32f)
        }
        SkillMotion.RAIN_VERTICAL -> {
            repeat(3) { column ->
                val localEnter = ((enter - column * 0.08f) / 0.84f).coerceIn(0f, 1f)
                val x = size.width * listOf(0.32f, 0.50f, 0.68f)[column]
                val top = -size.height * 0.12f + size.height * 0.48f * localEnter
                drawRibbon(
                    Offset(x, top),
                    Offset(x, center.y + size.height * 0.20f),
                    size.minDimension * 0.035f,
                    size.minDimension * 0.11f,
                    if (column == 1) primary else secondary,
                )
            }
        }
        SkillMotion.PIERCE_LINE -> {
            val origin = Offset(if (direction > 0) size.width * 0.08f else size.width * 0.92f, center.y)
            val tip = Offset(if (direction > 0) size.width * (0.12f + 0.76f * enter) else size.width * (0.88f - 0.76f * enter), center.y)
            drawRibbon(origin, tip, size.minDimension * 0.035f, size.minDimension * 0.12f, primary)
            drawImpactShards(tip, palette, enter, definition.effectVariant + hitIndex, 4)
        }
        SkillMotion.DASH_IMPACT -> {
            val spread = size.width * 0.24f * (1f - enter)
            drawRibbon(
                Offset(center.x - spread - size.width * 0.14f, center.y - size.height * 0.07f),
                center,
                size.minDimension * 0.11f,
                size.minDimension * 0.19f,
                primary,
            )
            drawRibbon(
                Offset(center.x + spread + size.width * 0.14f, center.y + size.height * 0.07f),
                center,
                size.minDimension * 0.09f,
                size.minDimension * 0.16f,
                secondary,
            )
            drawSoftGlow(center, size.minDimension * 0.20f, primary, alpha * 0.42f)
        }
        SkillMotion.PROJECTILE_SINGLE -> {
            val x = if (direction > 0) size.width * (0.12f + 0.76f * enter) else size.width * (0.88f - 0.76f * enter)
            val tip = Offset(x, center.y - size.height * 0.04f)
            val tail = tip - Offset(size.width * 0.16f * direction, 0f)
            drawRibbon(tail, tip, size.minDimension * 0.07f, size.minDimension * 0.13f, secondary)
            drawSoftGlow(tip, size.minDimension * 0.13f, primary, alpha * 0.56f)
            drawCircle(primary, size.minDimension * 0.045f, tip)
        }
        SkillMotion.PROJECTILE_VOLLEY -> {
            repeat(3) { projectile ->
                val localEnter = ((enter - projectile * 0.055f) / 0.89f).coerceIn(0f, 1f)
                val x = if (direction > 0) size.width * (0.10f + 0.78f * localEnter) else size.width * (0.90f - 0.78f * localEnter)
                val y = center.y + (projectile - 1) * size.height * 0.10f
                val tip = Offset(x, y)
                drawRibbon(
                    tip - Offset(size.width * 0.11f * direction, 0f),
                    tip,
                    size.minDimension * 0.04f,
                    size.minDimension * 0.075f,
                    if (projectile == 1) primary else secondary,
                )
            }
        }
        SkillMotion.BEAM_CHANNEL -> {
            val beamHalf = size.width * 0.39f * enter
            drawRibbon(
                center - Offset(beamHalf, 0f),
                center + Offset(beamHalf, 0f),
                size.minDimension * 0.10f,
                size.minDimension * 0.15f,
                primary.copy(alpha = primary.alpha * 0.72f),
            )
            drawSoftGlow(center, size.minDimension * 0.16f, secondary, alpha * 0.30f)
        }
        SkillMotion.CHAIN_ARC -> {
            var previous = Offset(size.width * 0.13f, center.y)
            repeat(6) { segment ->
                val point = Offset(
                    size.width * (0.13f + 0.74f * (segment + 1f) / 6f),
                    center.y + direction * if (segment % 2 == 0) size.height * 0.09f else -size.height * 0.07f,
                )
                drawRibbon(
                    previous,
                    point,
                    size.minDimension * 0.045f,
                    size.minDimension * 0.07f,
                    if (segment % 2 == 0) primary else secondary,
                )
                previous = point
            }
        }
        SkillMotion.NOVA_RADIAL,
        SkillMotion.GRAVITY_COLLAPSE,
        -> {
            val reverse = definition.motion == SkillMotion.GRAVITY_COLLAPSE
            val radiusProgress = if (reverse) 1f - enter else enter
            val radius = size.minDimension * (0.08f + radiusProgress * 0.34f)
            drawSoftGlow(center, radius, primary, alpha * 0.34f)
            drawCircle(secondary.copy(alpha = secondary.alpha * 0.58f), radius * 0.46f, center)
            drawCircle(primary.copy(alpha = primary.alpha * 0.72f), radius * 0.20f, center)
        }
        SkillMotion.ERUPTION_UP -> {
            repeat(3) { index ->
                val x = center.x + (index - 1) * size.width * 0.11f
                val top = center.y - size.height * (0.12f + index * 0.06f) * enter
                drawRibbon(
                    start = Offset(x, size.height),
                    end = Offset(x, top),
                    startWidth = size.minDimension * (0.14f - index * 0.018f),
                    endWidth = size.minDimension * 0.075f,
                    color = if (index == 1) primary else secondary,
                )
            }
        }
        SkillMotion.TRAP_SNAP -> {
            val inset = size.width * 0.42f * (1f - enter)
            drawBladeSweep(
                Offset(inset, size.height * 0.25f),
                Offset(size.width - inset, size.height * 0.75f),
                size.minDimension * 0.09f,
                primary,
                secondary,
            )
            drawBladeSweep(
                Offset(size.width - inset, size.height * 0.25f),
                Offset(inset, size.height * 0.75f),
                size.minDimension * 0.09f,
                secondary,
                primary,
            )
        }
        SkillMotion.SUMMON_DIVE -> {
            repeat(3) { claw ->
                val offset = (claw - 1) * size.width * 0.07f
                drawRibbon(
                    start = Offset(size.width * 0.22f + offset, size.height * 0.12f),
                    end = Offset(center.x + offset, center.y + size.height * 0.22f),
                    startWidth = size.minDimension * 0.11f,
                    endWidth = size.minDimension * 0.065f,
                    color = if (claw == 1) primary else secondary,
                )
            }
        }
        SkillMotion.EXECUTE_PAUSE -> {
            val length = size.width * 0.40f * if (progress < 0.50f) 0.15f else enter
            drawBladeSweep(
                center - Offset(length, 0f),
                center + Offset(length, 0f),
                size.minDimension * 0.11f,
                primary,
                secondary,
            )
            drawSoftGlow(
                center,
                size.minDimension * (0.10f + 0.24f * (1f - enter)),
                secondary,
                alpha * 0.28f,
            )
        }
    }
    if (hitIndex == definition.hitTimingsMillis.lastIndex) {
        drawClassSignature(definition.heroClass, palette, center, enter, alpha, direction)
    }
    if (drawParticles) {
        drawParticles(definition, palette, center, progress, hitIndex, organicAtlas)
    }
}

private fun DrawScope.drawClassSignature(
    heroClass: HeroClass,
    palette: SkillPalette,
    center: Offset,
    progress: Float,
    alpha: Float,
    direction: Float,
) {
    val signatureAlpha = alpha * 0.22f
    when (heroClass) {
        HeroClass.WARRIOR -> {
            val impactPlate = Path().apply {
                moveTo(center.x - size.width * 0.13f, center.y - size.height * 0.07f)
                lineTo(center.x + size.width * 0.13f, center.y - size.height * 0.07f)
                lineTo(center.x + size.width * 0.18f, center.y + size.height * 0.10f)
                lineTo(center.x, center.y + size.height * 0.17f)
                lineTo(center.x - size.width * 0.18f, center.y + size.height * 0.10f)
                close()
            }
            drawPath(impactPlate, palette.secondary.copy(alpha = signatureAlpha))
        }
        HeroClass.ROGUE -> {
            val side = center + Offset(size.width * 0.10f * direction, -size.height * 0.06f)
            val afterimage = Path().apply {
                moveTo(side.x + size.width * 0.13f * direction, side.y)
                lineTo(side.x - size.width * 0.11f * direction, side.y - size.height * 0.12f)
                lineTo(side.x - size.width * 0.06f * direction, side.y + size.height * 0.11f)
                close()
            }
            drawPath(afterimage, palette.secondary.copy(alpha = signatureAlpha))
        }
        HeroClass.RANGER -> {
            repeat(3) { lane ->
                val origin = Offset(size.width * if (direction > 0) 0.18f else 0.82f, center.y + (lane - 1) * size.height * 0.12f)
                val vector = center - origin
                val length = sqrt(vector.x * vector.x + vector.y * vector.y).coerceAtLeast(0.001f)
                val normal = Offset(-vector.y / length, vector.x / length)
                val arrow = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(origin.x + normal.x * size.minDimension * 0.055f, origin.y + normal.y * size.minDimension * 0.055f)
                    lineTo(origin.x - normal.x * size.minDimension * 0.055f, origin.y - normal.y * size.minDimension * 0.055f)
                    close()
                }
                drawPath(arrow, palette.secondary.copy(alpha = signatureAlpha))
            }
        }
        HeroClass.MAGE -> {
            val radius = size.minDimension * (0.12f + 0.12f * progress)
            drawCircle(palette.secondary.copy(alpha = signatureAlpha), radius, center)
            drawCircle(palette.primary.copy(alpha = signatureAlpha * 1.25f), radius * 0.42f, center)
        }
        HeroClass.CLERIC -> {
            repeat(2) { side ->
                val x = center.x + (if (side == 0) -1f else 1f) * size.width * 0.12f
                val column = Path().apply {
                    moveTo(x - size.width * 0.035f, center.y - size.height * 0.24f)
                    lineTo(x + size.width * 0.035f, center.y - size.height * 0.24f)
                    lineTo(x + size.width * 0.065f, center.y + size.height * 0.17f)
                    lineTo(x - size.width * 0.065f, center.y + size.height * 0.17f)
                    close()
                }
                drawPath(column, palette.secondary.copy(alpha = signatureAlpha))
            }
        }
        HeroClass.PALADIN -> {
            val radius = size.minDimension * (0.16f + 0.06f * progress)
            drawDiamond(
                center,
                radius,
                radius * 0.82f,
                palette.secondary.copy(alpha = signatureAlpha),
            )
            drawDiamond(
                center,
                radius * 0.46f,
                radius * 0.38f,
                palette.primary.copy(alpha = signatureAlpha * 1.35f),
            )
        }
    }
}

private fun DrawScope.drawParticles(
    definition: SkillDefinition,
    palette: SkillPalette,
    center: Offset,
    progress: Float,
    hitIndex: Int,
    organicAtlas: ImageBitmap,
) {
    drawOrganicAccent(definition, palette, center, progress, hitIndex, organicAtlas)
    val isFinal = hitIndex == definition.hitCount - 1
    val count = if (isFinal) {
        (4 + definition.intensityTier).coerceAtMost(8)
    } else {
        (2 + definition.intensityTier / 2).coerceAtMost(4)
    }
    val fade = (1f - progress).coerceIn(0f, 1f)
    repeat(count) { particle ->
        val angle = ((particle * 67 + definition.effectVariant * 29 + hitIndex * 41) % 360) * PI / 180.0
        val distance = size.minDimension * (0.08f + 0.28f * progress) * (0.65f + (particle % 3) * 0.16f)
        val point = center + Offset(
            (cos(angle) * distance).toFloat(),
            (sin(angle) * distance).toFloat(),
        )
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val perpendicular = Offset(-direction.y, direction.x)
        val length = size.minDimension * (0.045f + (particle % 3) * 0.014f)
        val width = size.minDimension * (0.032f + (particle % 2) * 0.010f)
        val color = if (particle % 2 == 0) {
            palette.primary.copy(alpha = fade * if (isFinal) 0.78f else 0.62f)
        } else {
            palette.secondary.copy(alpha = fade * if (isFinal) 0.62f else 0.50f)
        }
        when (definition.element) {
            SkillElement.POISON, SkillElement.DARK, SkillElement.COSMIC -> {
                drawCircle(color, radius = width * (1.1f + particle % 3 * 0.35f), center = point)
                if (definition.element == SkillElement.COSMIC && particle % 3 == 0) {
                    drawDiamond(point, length * 0.72f, width * 0.72f, color.copy(alpha = color.alpha * 0.88f))
                }
            }
            SkillElement.FIRE -> drawFlameLeaf(point, direction, perpendicular, length * 1.18f, width * 1.35f, color)
            SkillElement.LIGHTNING -> drawLightningChunk(point, direction, perpendicular, length * 1.25f, width * 1.18f, color)
            SkillElement.WIND -> drawPressureWedge(point, direction, perpendicular, length * 1.45f, width * 1.35f, color)
            SkillElement.HOLY, SkillElement.ARCANE -> drawDiamond(
                point,
                length * if (definition.element == SkillElement.HOLY) 1.1f else 0.88f,
                width * 1.15f,
                color,
            )
            SkillElement.PHYSICAL, SkillElement.ICE, SkillElement.EARTH -> drawAngularShard(
                point,
                direction,
                perpendicular,
                length * if (definition.element == SkillElement.EARTH) 1.30f else 1f,
                width * if (definition.element == SkillElement.ICE) 0.82f else 1.15f,
                color,
            )
        }
    }
}

private fun DrawScope.drawOrganicAccent(
    definition: SkillDefinition,
    palette: SkillPalette,
    center: Offset,
    progress: Float,
    hitIndex: Int,
    atlas: ImageBitmap,
) {
    val atlasIndex = when (definition.element) {
        SkillElement.FIRE -> 3
        SkillElement.ICE -> 4
        SkillElement.POISON -> 5
        SkillElement.EARTH -> 6
        SkillElement.HOLY -> 7
        SkillElement.DARK, SkillElement.WIND -> 2
        SkillElement.PHYSICAL -> 1
        SkillElement.LIGHTNING, SkillElement.ARCANE, SkillElement.COSMIC -> 0
    }
    val sourceSize = 256
    val sourceOffset = IntOffset((atlasIndex % 4) * sourceSize, (atlasIndex / 4) * sourceSize)
    val isFinal = hitIndex == definition.hitTimingsMillis.lastIndex
    val baseSize = size.minDimension * (if (isFinal) 0.56f else 0.40f)
    val pulse = 0.78f + 0.22f * sin((progress * PI).toFloat()).coerceAtLeast(0f)
    val destinationSize = baseSize * pulse
    val destinationCenter = center + Offset(
        ((definition.effectVariant % 2) * 2 - 1) * size.width * 0.035f,
        size.height * 0.055f,
    )
    val destination = IntOffset(
        (destinationCenter.x - destinationSize / 2f).toInt(),
        (destinationCenter.y - destinationSize / 2f).toInt(),
    )
    val fade = (1f - progress).coerceIn(0f, 1f)
    drawImage(
        image = atlas,
        srcOffset = sourceOffset,
        srcSize = IntSize(sourceSize, sourceSize),
        dstOffset = destination,
        dstSize = IntSize(destinationSize.toInt().coerceAtLeast(1), destinationSize.toInt().coerceAtLeast(1)),
        alpha = fade * if (isFinal) 0.34f else 0.22f,
        colorFilter = ColorFilter.tint(palette.secondary),
    )
}

private fun DrawScope.drawAngularShard(
    point: Offset,
    direction: Offset,
    perpendicular: Offset,
    length: Float,
    width: Float,
    color: Color,
) {
    val shard = Path().apply {
        moveTo(point.x + direction.x * length, point.y + direction.y * length)
        lineTo(point.x + perpendicular.x * width, point.y + perpendicular.y * width)
        lineTo(point.x - direction.x * length * 0.62f, point.y - direction.y * length * 0.62f)
        lineTo(point.x - perpendicular.x * width, point.y - perpendicular.y * width)
        close()
    }
    drawPath(shard, color)
}

private fun DrawScope.drawDiamond(point: Offset, length: Float, width: Float, color: Color) {
    val diamond = Path().apply {
        moveTo(point.x, point.y - length)
        lineTo(point.x + width, point.y)
        lineTo(point.x, point.y + length)
        lineTo(point.x - width, point.y)
        close()
    }
    drawPath(diamond, color)
}

private fun DrawScope.drawFlameLeaf(
    point: Offset,
    direction: Offset,
    perpendicular: Offset,
    length: Float,
    width: Float,
    color: Color,
) {
    val flame = Path().apply {
        moveTo(point.x + direction.x * length, point.y + direction.y * length)
        cubicTo(
            point.x + perpendicular.x * width,
            point.y + perpendicular.y * width,
            point.x - direction.x * length * 0.42f + perpendicular.x * width * 0.72f,
            point.y - direction.y * length * 0.42f + perpendicular.y * width * 0.72f,
            point.x - direction.x * length * 0.55f,
            point.y - direction.y * length * 0.55f,
        )
        cubicTo(
            point.x - direction.x * length * 0.24f - perpendicular.x * width,
            point.y - direction.y * length * 0.24f - perpendicular.y * width,
            point.x + direction.x * length * 0.34f - perpendicular.x * width * 0.78f,
            point.y + direction.y * length * 0.34f - perpendicular.y * width * 0.78f,
            point.x + direction.x * length,
            point.y + direction.y * length,
        )
        close()
    }
    drawPath(flame, color)
}

private fun DrawScope.drawLightningChunk(
    point: Offset,
    direction: Offset,
    perpendicular: Offset,
    length: Float,
    width: Float,
    color: Color,
) {
    val bolt = Path().apply {
        moveTo(point.x + direction.x * length, point.y + direction.y * length)
        lineTo(point.x + perpendicular.x * width * 0.18f, point.y + perpendicular.y * width * 0.18f)
        lineTo(point.x + direction.x * length * 0.18f + perpendicular.x * width, point.y + direction.y * length * 0.18f + perpendicular.y * width)
        lineTo(point.x - direction.x * length, point.y - direction.y * length)
        lineTo(point.x - perpendicular.x * width * 0.12f, point.y - perpendicular.y * width * 0.12f)
        lineTo(point.x - direction.x * length * 0.18f - perpendicular.x * width, point.y - direction.y * length * 0.18f - perpendicular.y * width)
        close()
    }
    drawPath(bolt, color)
}

private fun DrawScope.drawPressureWedge(
    point: Offset,
    direction: Offset,
    perpendicular: Offset,
    length: Float,
    width: Float,
    color: Color,
) {
    val wedge = Path().apply {
        moveTo(point.x + direction.x * length, point.y + direction.y * length)
        cubicTo(
            point.x + perpendicular.x * width,
            point.y + perpendicular.y * width,
            point.x - direction.x * length * 0.55f + perpendicular.x * width,
            point.y - direction.y * length * 0.55f + perpendicular.y * width,
            point.x - direction.x * length * 0.64f,
            point.y - direction.y * length * 0.64f,
        )
        cubicTo(
            point.x - direction.x * length * 0.30f - perpendicular.x * width,
            point.y - direction.y * length * 0.30f - perpendicular.y * width,
            point.x + direction.x * length * 0.30f - perpendicular.x * width,
            point.y + direction.y * length * 0.30f - perpendicular.y * width,
            point.x + direction.x * length,
            point.y + direction.y * length,
        )
        close()
    }
    drawPath(wedge, color)
}

private fun DrawScope.drawFinisher(
    finisher: SkillFinisher,
    palette: SkillPalette,
    progress: Float,
    intensityTier: Int,
) {
    if (finisher == SkillFinisher.NONE) return
    val center = Offset(size.width * 0.5f, size.height * 0.67f)
    val tierScale = listOf(0.35f, 0.50f, 0.65f, 0.82f, 1f)[intensityTier.coerceIn(1, 5) - 1]
    val fade = (1f - progress).coerceIn(0f, 1f) * tierScale
    val color = palette.primary.copy(alpha = fade * 0.72f)
    when (finisher) {
        SkillFinisher.RING -> {
            val radius = size.minDimension * (0.10f + progress * 0.38f)
            drawSoftGlow(center, radius, color, fade * 0.30f)
            drawCircle(color.copy(alpha = fade * 0.18f), radius * 0.62f, center)
        }
        SkillFinisher.FRACTURE -> drawImpactShards(center, palette, progress, 41, 7)
        SkillFinisher.COLUMN -> drawRect(
            color,
            topLeft = Offset(center.x - size.width * 0.055f, 0f),
            size = Size(size.width * 0.11f, size.height * (0.5f + 0.5f * progress)),
        )
        SkillFinisher.BURST -> {
            drawSoftGlow(center, size.minDimension * (0.14f + 0.24f * progress), color, fade * 0.42f)
            drawImpactShards(center, palette, progress, 83, 8)
        }
        SkillFinisher.AFTERIMAGE -> drawArc(
            color.copy(alpha = fade * 0.22f),
            startAngle = 195f,
            sweepAngle = 150f * progress,
            useCenter = true,
            topLeft = Offset(center.x - size.minDimension * 0.32f, center.y - size.minDimension * 0.32f),
            size = Size(size.minDimension * 0.64f, size.minDimension * 0.64f),
        )
        SkillFinisher.NONE -> Unit
    }
}

private fun DrawScope.drawBladeSweep(
    start: Offset,
    end: Offset,
    width: Float,
    primary: Color,
    highlight: Color,
) {
    drawCrescentBlade(
        start = start,
        end = end,
        width = width * 1.18f,
        color = primary.copy(alpha = primary.alpha * 0.24f),
    )
    drawCrescentBlade(
        start = start + (end - start) * 0.04f,
        end = start + (end - start) * 0.96f,
        width = width * 0.78f,
        color = primary.copy(alpha = primary.alpha * 0.60f),
    )
    drawCrescentBlade(
        start = start + (end - start) * 0.16f,
        end = start + (end - start) * 0.88f,
        width = width * 0.50f,
        color = highlight.copy(alpha = highlight.alpha * 0.88f),
    )
}

private fun DrawScope.drawCrescentBlade(
    start: Offset,
    end: Offset,
    width: Float,
    color: Color,
) {
    val vector = end - start
    val length = sqrt(vector.x * vector.x + vector.y * vector.y).coerceAtLeast(0.001f)
    val normal = Offset(-vector.y / length, vector.x / length)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(
            start.x + vector.x * 0.24f + normal.x * width * 0.78f,
            start.y + vector.y * 0.24f + normal.y * width * 0.78f,
            start.x + vector.x * 0.70f + normal.x * width,
            start.y + vector.y * 0.70f + normal.y * width,
            end.x,
            end.y,
        )
        cubicTo(
            start.x + vector.x * 0.72f + normal.x * width * 0.24f,
            start.y + vector.y * 0.72f + normal.y * width * 0.24f,
            start.x + vector.x * 0.28f + normal.x * width * 0.10f,
            start.y + vector.y * 0.28f + normal.y * width * 0.10f,
            start.x,
            start.y,
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawRibbon(
    start: Offset,
    end: Offset,
    startWidth: Float,
    endWidth: Float,
    color: Color,
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val perpendicular = Offset(-dy / length, dx / length)
    val path = Path().apply {
        moveTo(start.x + perpendicular.x * startWidth * 0.5f, start.y + perpendicular.y * startWidth * 0.5f)
        lineTo(end.x + perpendicular.x * endWidth * 0.5f, end.y + perpendicular.y * endWidth * 0.5f)
        lineTo(end.x - perpendicular.x * endWidth * 0.5f, end.y - perpendicular.y * endWidth * 0.5f)
        lineTo(start.x - perpendicular.x * startWidth * 0.5f, start.y - perpendicular.y * startWidth * 0.5f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSoftGlow(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(color.copy(alpha = alpha * 0.18f), radius, center)
    drawCircle(color.copy(alpha = alpha * 0.28f), radius * 0.68f, center)
    drawCircle(color.copy(alpha = alpha * 0.42f), radius * 0.34f, center)
}

private fun DrawScope.drawImpactShards(
    center: Offset,
    palette: SkillPalette,
    progress: Float,
    seed: Int,
    count: Int,
) {
    val fade = (1f - progress).coerceIn(0f, 1f)
    repeat(count) { index ->
        val angle = ((index * 53 + seed * 17) % 360) * PI / 180.0
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val perpendicular = Offset(-direction.y, direction.x)
        val distance = size.minDimension * (0.05f + progress * (0.16f + (index % 3) * 0.035f))
        val origin = center + direction * distance
        val length = size.minDimension * (0.048f + (index % 3) * 0.014f)
        val width = size.minDimension * (0.030f + (index % 2) * 0.008f)
        val path = Path().apply {
            moveTo(origin.x + direction.x * length, origin.y + direction.y * length)
            lineTo(origin.x - direction.x * length * 0.45f + perpendicular.x * width, origin.y - direction.y * length * 0.45f + perpendicular.y * width)
            lineTo(origin.x - direction.x * length * 0.45f - perpendicular.x * width, origin.y - direction.y * length * 0.45f - perpendicular.y * width)
            close()
        }
        drawPath(
            path,
            if (index % 2 == 0) palette.primary.copy(alpha = fade * 0.68f)
            else palette.secondary.copy(alpha = fade * 0.52f),
        )
    }
}

private fun easeOut(value: Float): Float = 1f - (1f - value) * (1f - value)

private fun fraction(value: Int, start: Int, end: Int): Float =
    if (end <= start) 1f else ((value - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)

private fun fraction(value: Float, start: Float, end: Float): Float =
    if (end <= start) 1f else ((value - start) / (end - start)).coerceIn(0f, 1f)
