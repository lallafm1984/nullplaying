package com.nullplaying.data

import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType

const val RECENT_ADVENTURE_EVENT_LIMIT = 300

data class RecentAdventureEventRecord(
    val id: Long,
    val event: RecentAdventureEvent,
)

internal fun RecentAdventureEvent.toEntity(characterSlotId: Int): RecentAdventureEventEntity =
    RecentAdventureEventEntity(
        characterSlotId = characterSlotId,
        occurredAt = occurredAt,
        eventType = type.name,
        subjectId = subjectId,
        subjectName = subjectName,
        contextName = contextName,
        previousName = previousName,
        currentName = currentName,
        previousValue = previousValue,
        currentValue = currentValue,
        equipmentSlot = equipmentSlot?.name.orEmpty(),
        rarity = rarity,
    )

internal fun RecentAdventureEventEntity.toRecordOrNull(): RecentAdventureEventRecord? {
    val type = runCatching { RecentAdventureEventType.valueOf(eventType) }.getOrNull() ?: return null
    val slot = equipmentSlot.takeIf(String::isNotBlank)?.let { encoded ->
        runCatching { EquipmentSlot.valueOf(encoded) }.getOrNull()
    }
    return RecentAdventureEventRecord(
        id = id,
        event = RecentAdventureEvent(
            occurredAt = occurredAt,
            type = type,
            subjectId = subjectId,
            subjectName = subjectName,
            contextName = contextName,
            previousName = previousName,
            currentName = currentName,
            previousValue = previousValue,
            currentValue = currentValue,
            equipmentSlot = slot,
            rarity = rarity,
        ),
    )
}
