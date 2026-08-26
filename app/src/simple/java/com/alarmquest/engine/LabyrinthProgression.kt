package com.alarmquest.engine

/**
 * Endless-labyrinth rules that turn depth into pacing, danger, rewards, and records.
 *
 * A depth is one five-act tale. Every tenth depth is a gate expedition whose final
 * encounter is a stronger automatic gate boss.
 */
internal object LabyrinthProgression {
    const val GATE_INTERVAL = 10L

    private val BASE_ACT_TARGETS = listOf(800L, 950L, 1_100L, 900L, 1_250L)
    private const val TARGET_GROWTH_PER_DEPTH = 2L
    private const val GATE_FINAL_ACT_TARGET_BONUS = 300L
    private const val REWARD_BONUS_PER_CLEARED_GATE_PERCENT = 1L
    private const val GATE_EXPEDITION_REWARD_BONUS_PERCENT = 15L
    private const val MAX_REWARD_BONUS_PERCENT = 400L
    private const val BASE_LABYRINTH_ATTACK_BONUS = 1L
    private const val GATES_PER_ATTACK_BONUS = 3L
    private const val GATE_BOSS_ATTACK_BONUS = 10L

    fun safeDepth(depth: Long): Long = depth.coerceAtLeast(1L)

    fun isGateDepth(depth: Long): Boolean =
        safeDepth(depth) % GATE_INTERVAL == 0L

    fun gateNumberForDepth(depth: Long): Long = safeDepth(depth) / GATE_INTERVAL

    fun completedGateCount(completedDepth: Long): Long =
        completedDepth.coerceAtLeast(0L) / GATE_INTERVAL

    fun clearedGatesBefore(depth: Long): Long =
        (safeDepth(depth) - 1L) / GATE_INTERVAL

    fun dangerTier(depth: Long): Long = safeIncrement(clearedGatesBefore(depth))

    fun actTarget(depth: Long, actIndex: Int): Long {
        val safeDepth = safeDepth(depth)
        val index = actIndex.coerceIn(0, BASE_ACT_TARGETS.lastIndex)
        val depthGrowth = safeMul(safeDepth - 1L, TARGET_GROWTH_PER_DEPTH)
        val gateBonus = if (isGateDepth(safeDepth) && index == BASE_ACT_TARGETS.lastIndex) {
            GATE_FINAL_ACT_TARGET_BONUS
        } else {
            0L
        }
        return safeAdd(safeAdd(BASE_ACT_TARGETS[index], depthGrowth), gateBonus)
    }

    fun rewardBonusPercent(depth: Long): Long {
        val progressionBonus = safeMul(
            clearedGatesBefore(depth),
            REWARD_BONUS_PER_CLEARED_GATE_PERCENT,
        )
        val gateBonus = if (isGateDepth(depth)) GATE_EXPEDITION_REWARD_BONUS_PERCENT else 0L
        return safeAdd(progressionBonus, gateBonus).coerceAtMost(MAX_REWARD_BONUS_PERCENT)
    }

    fun rewardPercent(depth: Long): Long = safeAdd(100L, rewardBonusPercent(depth))

    fun scaleReward(value: Long, depth: Long): Long =
        scalePercentCeiling(value.coerceAtLeast(0L), rewardPercent(depth))

    /** Combat XP receives half the act-reward bonus so difficulty growth remains meaningful. */
    fun scaleCombatExperience(value: Long, depth: Long): Long = scalePercentCeiling(
        value.coerceAtLeast(0L),
        safeAdd(100L, rewardBonusPercent(depth) / 2L),
    )

    fun monsterLevelBonus(depth: Long): Long = clearedGatesBefore(depth)

    fun monsterAttackBonus(depth: Long): Int {
        val bonus = safeAdd(
            BASE_LABYRINTH_ATTACK_BONUS,
            clearedGatesBefore(depth) / GATES_PER_ATTACK_BONUS,
        )
        return bonus.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun targetAttacks(
        depth: Long,
        baseAttacks: Int,
        isGateBoss: Boolean,
    ): Int {
        val gateBossBonus = if (isGateBoss) GATE_BOSS_ATTACK_BONUS else 0L
        return safeAdd(
            baseAttacks.coerceAtLeast(1).toLong(),
            safeAdd(monsterAttackBonus(depth).toLong(), gateBossBonus),
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun nextGateDepth(completedDepth: Long): Long {
        val nextGateNumber = safeIncrement(completedGateCount(completedDepth))
        return safeMul(nextGateNumber, GATE_INTERVAL).coerceAtLeast(GATE_INTERVAL)
    }

    fun titleForCompletedDepth(completedDepth: Long): String {
        val safeCompleted = completedDepth.coerceAtLeast(0L)
        val gateCount = completedGateCount(safeCompleted)
        return when {
            safeCompleted == 0L -> "아직 없음"
            gateCount == 0L -> "미궁 탐사자"
            else -> "제${gateCount}관문 정복자"
        }
    }

    private fun safeIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) value else value + 1L

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun safeMul(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun scalePercentCeiling(value: Long, percent: Long): Long {
        if (value <= 0L || percent <= 0L) return 0L
        val whole = safeMul(value / 100L, percent)
        val remainderProduct = safeMul(value % 100L, percent)
        val remainder = if (remainderProduct == 0L) {
            0L
        } else {
            safeAdd(remainderProduct, 99L) / 100L
        }
        return safeAdd(whole, remainder)
    }
}
