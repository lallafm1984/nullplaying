package com.nullplaying.data

import com.nullplaying.model.SimpleGameState

data class GameProgressEvent(
    val heroName: String,
    val offlineAdventureDepleted: Boolean,
) {
    val hasEvents: Boolean
        get() = offlineAdventureDepleted
}

fun interface GameProgressEventSink {
    fun onGameProgress(event: GameProgressEvent)
}

internal data class GameProgressCheckpoint(
    val offlineAdventureMillis: Long,
)

internal fun SimpleGameState.progressCheckpoint(): GameProgressCheckpoint =
    GameProgressCheckpoint(
        offlineAdventureMillis = offlineAdventureMillis,
    )

internal fun gameProgressEventBetween(
    before: GameProgressCheckpoint,
    after: SimpleGameState,
): GameProgressEvent? {
    val event = GameProgressEvent(
        heroName = after.hero.name,
        offlineAdventureDepleted = before.offlineAdventureMillis > 0L &&
            after.offlineAdventureMillis == 0L,
    )
    return event.takeIf(GameProgressEvent::hasEvents)
}

internal val NoOpGameProgressEventSink = GameProgressEventSink { }
