package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Completed local matches, newest first. Legacy history has no captured power/adjustment. */
internal data class ArenaRecentMatch(
    val battleId: String,
    val opponentId: String,
    val heroLevel: Long,
    val outcome: BattleOutcome,
    val heroPower: Long = 0L,
    val adjustmentPermille: Int = 0,
)

/** Frozen before selection/simulation. It is an audit receipt, never a combat-stat modifier. */
@Serializable
internal data class ArenaMatchmakingProfile(
    val rulesVersion: Int = 1,
    val heroLevel: Long,
    val heroPower: Long,
    val referencePower: Long,
    val targetPower: Long,
    val adjustmentPermille: Int,
    val sampleCount: Int,
    val recentStreak: Int = 0,
    val levelSearchRadius: Int = 1,
)

internal object ArenaAdaptiveMatchmaking {
    const val RULES_VERSION = 2
    // Local opponents are never uploaded. Keep arithmetic bounded independently of the
    // public ranking envelope so legitimate restored/QA equipment cannot block local play.
    const val MAX_LOCAL_REFERENCE_POWER = 1_000_000_000L
    const val HISTORY_LIMIT = 10
    const val MIN_HISTORY = 3
    const val MAX_ADJUSTMENT = 100
    private const val STEP = 25

    fun recentMatches(history: List<ArenaRecentMatch>, heroLevel: Long): List<ArenaRecentMatch> =
        history.asSequence().filter {
            it.battleId.isNotBlank() && it.heroLevel in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL &&
                abs(it.heroLevel - heroLevel) <= 1L && it.heroPower in 0L..MAX_LOCAL_REFERENCE_POWER &&
                it.adjustmentPermille in -MAX_ADJUSTMENT..MAX_ADJUSTMENT
        }.distinctBy { it.battleId }.take(HISTORY_LIMIT).toList()

    fun profile(heroLevel: Long, heroPower: Long, history: List<ArenaRecentMatch>): ArenaMatchmakingProfile? {
        if (heroLevel !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL ||
            heroPower !in 1L..MAX_LOCAL_REFERENCE_POWER) return null
        val recent = recentMatches(history, heroLevel)
        // Keep the recent equipment reference for score settlement only. Candidate power always
        // stays within the current hero's +/-10%, including after a legitimate equipment change.
        val recentPeak = recent.filter { it.heroLevel == heroLevel }.maxOfOrNull { it.heroPower } ?: 0L
        val reference = maxOf(heroPower, (recentPeak * 0.9).roundToLong())
        val adjustment = if (recent.size < MIN_HISTORY) 0 else {
            var earned = 0
            var weightTotal = 0
            recent.forEachIndexed { index, match ->
                val weight = if (index < 5) 2 else 1
                earned += weight * when (match.outcome) {
                    BattleOutcome.USER_WIN -> 2
                    BattleOutcome.DRAW -> 1
                    BattleOutcome.USER_LOSS -> 0
                }
                weightTotal += weight * 2
            }
            val rate = earned * 1_000 / weightTotal
            val desired = when {
                rate < 400 -> (rate - 400).coerceAtLeast(-MAX_ADJUSTMENT)
                rate > 600 -> (rate - 600).coerceAtMost(MAX_ADJUSTMENT)
                else -> 0
            }
            val previous = recent.first().adjustmentPermille
            desired.coerceIn(previous - STEP, previous + STEP).coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
        }
        val firstOutcome = recent.firstOrNull()?.outcome
        val streak = when (firstOutcome) {
            BattleOutcome.USER_WIN -> recent.takeWhile { it.outcome == firstOutcome }.size
            BattleOutcome.USER_LOSS -> -recent.takeWhile { it.outcome == firstOutcome }.size
            else -> 0
        }
        return ArenaMatchmakingProfile(
            rulesVersion = RULES_VERSION,
            heroLevel = heroLevel, heroPower = heroPower, referencePower = reference,
            // At the limits, reserves cover 90..100% or 100..110%; their center is +/-5%.
            targetPower = (heroPower * (2_000L + adjustment) + 1_000L) / 2_000L,
            adjustmentPermille = adjustment, sampleCount = recent.size,
            recentStreak = streak,
        )
    }

    /** Compare the same bounded power that the combat adapter actually uses. */
    fun effectivePower(level: Long, power: Long): Long =
        (averagePower(level) * PublicPlayerBattleDerivation.powerScalePermille(level, power) + 500L) / 1_000L

    fun averagePower(level: Long): Long = (1L + (level.coerceIn(1L, SHARED_PLAYER_MAX_LEVEL) - 1L) * 5L) * 2L

    fun accepts(profile: ArenaMatchmakingProfile, level: Long, power: Long): Boolean {
        if (level !in (profile.heroLevel - 1L)..(profile.heroLevel + 1L) || power <= 0L) return false
        if (profile.rulesVersion == RULES_VERSION) {
            return power in localPowerBounds(profile) &&
                abs(power - profile.targetPower) <= maxOf(1L, profile.heroPower / 20L)
        }
        val effective = effectivePower(level, power)
        return effective * 1_000L in profile.targetPower * 900L..profile.targetPower * 1_100L
    }

    fun localPowerBounds(profile: ArenaMatchmakingProfile): LongRange =
        ((profile.heroPower * 900L + 999L) / 1_000L)..(profile.heroPower * 1_100L / 1_000L)

    /** Shift the authored spread inside the fixed band instead of multiplying two +/-10% bands. */
    fun localPower(profile: ArenaMatchmakingProfile, identityPermille: Int): Long {
        require(profile.rulesVersion == RULES_VERSION && identityPermille in 900..1_100)
        val adjustment = profile.adjustmentPermille.coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
        val offset = identityPermille - 1_000
        val scale = 200_000L + adjustment * 100L + offset * (200L - abs(adjustment))
        return ((profile.heroPower * scale + 100_000L) / 200_000L).coerceIn(localPowerBounds(profile))
    }

    /** Existing K=24 Elo consumes this local difficulty reference; no opponent ranking is edited. */
    fun referenceScore(profile: ArenaMatchmakingProfile, score: Int, opponentLevel: Long, opponentPower: Long): Int {
        val power = if (profile.rulesVersion == 1) effectivePower(opponentLevel, opponentPower) else opponentPower
        val ratio = power.toDouble() / profile.referencePower.coerceAtLeast(1L)
        val offset = (800.0 * log10(ratio.coerceIn(0.5, 2.0))).roundToInt().coerceIn(-160, 160)
        return (score.coerceAtLeast(0).toLong() + offset).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
