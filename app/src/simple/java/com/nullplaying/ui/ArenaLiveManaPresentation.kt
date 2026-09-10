package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSupportEvent
import com.nullplaying.engine.arena.ArenaSupportEventType

/**
 * The engine reserves both costs before resolving either action. Keep that ledger untouched,
 * but reveal an instant cast's payment with its own action, not during the opponent's action.
 * A pending cost is an offset over the real balance, so intervening drains still show exactly
 * their own amount. Multi-turn casts pay visibly when their preparation is announced.
 */
internal fun buildArenaLiveManaFrames(
    events: List<ArenaSupportEvent>,
    initialMana: Map<String, Int> = emptyMap(),
): Map<Int, Map<String, Int>> {
    val actual = initialMana.toMutableMap()
    val pending = mutableMapOf<String, ArenaSupportEvent>()
    val frames = linkedMapOf<Int, Map<String, Int>>()
    val directHeals = events.filter {
        it.type == ArenaSupportEventType.HEAL_APPLIED && it.reason != "hot" && it.castId != null
    }.map { it.actorId to it.castId }.toSet()
    for (event in events) {
        val actor = event.actorId
        if (actor != null && event.mpAfterUnits != null) actual[actor] = event.mpAfterUnits
        if (event.type == ArenaSupportEventType.CAST_START && actor != null) {
            require(actor !in pending) { "New cast before previous MP presentation completed" }
            if (event.castTurns <= 1 && event.mpBeforeUnits != null && event.mpAfterUnits != null &&
                event.mpBeforeUnits > event.mpAfterUnits) pending[actor] = event
        }
        val owner = if (event.type == ArenaSupportEventType.ATTACK_EVADED) event.targetId else actor
        val completesPayment = when (event.type) {
            ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
            ArenaSupportEventType.ATTACK_EVADED, ArenaSupportEventType.CAST_DELAYED,
            ArenaSupportEventType.CAST_PAUSED, ArenaSupportEventType.CAST_CANCELLED_KO -> true
            ArenaSupportEventType.SUPPORT_APPLIED -> event.actionId != "ARENA_SUP_CLERIC_01" ||
                (actor to event.castId) !in directHeals
            ArenaSupportEventType.HEAL_APPLIED -> event.reason != "hot"
            else -> false
        }
        if (completesPayment && owner != null && pending[owner]?.castId == event.castId) pending.remove(owner)
        if (event.type == ArenaSupportEventType.END) require(pending.isEmpty()) { "Unpresented arena MP payment" }
        frames[event.sequence] = actual.mapValues { (id, balance) ->
            val payment = pending[id]
            balance + if (payment == null) 0 else checkNotNull(payment.mpBeforeUnits) - checkNotNull(payment.mpAfterUnits)
        }
    }
    return frames
}
