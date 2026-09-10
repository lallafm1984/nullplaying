package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaRecentMatch
import com.nullplaying.model.BattleOutcome

/** Use canonical outcomes, never signed point deltas (a loss at zero score has a zero delta). */
internal fun arenaRecentMatchHistory(history: List<BattlePreviewHistory>): List<ArenaRecentMatch> =
    history.mapNotNull { entry ->
        val contract = entry.arenaSnapshot ?: return@mapNotNull null
        val outcome = when (entry.resultLabel) {
            "승리" -> BattleOutcome.USER_WIN
            "패배" -> BattleOutcome.USER_LOSS
            "무승부" -> BattleOutcome.DRAW
            else -> return@mapNotNull null
        }
        val profile = contract.matchmakingProfile?.takeIf {
            it.rulesVersion in 1..com.nullplaying.engine.arena.ArenaAdaptiveMatchmaking.RULES_VERSION &&
                it.heroLevel == contract.user.fighter.level && it.heroPower > 0L
        }
        ArenaRecentMatch(
            battleId = entry.battleId,
            opponentId = entry.opponentProjectionId.ifBlank { contract.opponent.fighter.id },
            heroLevel = contract.user.fighter.level,
            outcome = outcome,
            heroPower = profile?.heroPower ?: 0L,
            adjustmentPermille = profile?.adjustmentPermille ?: 0,
        )
    }
