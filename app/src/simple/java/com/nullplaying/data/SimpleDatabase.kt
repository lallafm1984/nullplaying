package com.nullplaying.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "simple_game_state")
data class SimpleStateEntity(
    @PrimaryKey val id: Int = 1,
    val payload: String,
    val updatedAt: Long,
)

@Entity(tableName = "simple_account_progress")
data class SimpleAccountProgressEntity(
    @PrimaryKey val id: Int = 1,
    val unlockedCharacterSlots: Int = 1,
    val activeCharacterSlotId: Int? = null,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0L,
)

@Entity(
    tableName = "recent_adventure_events",
    indices = [Index(value = ["characterSlotId", "occurredAt"])],
)
data class RecentAdventureEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val characterSlotId: Int,
    val occurredAt: Long,
    val eventType: String,
    val subjectId: String = "",
    val subjectName: String = "",
    val contextName: String = "",
    val previousName: String = "",
    val currentName: String = "",
    val previousValue: Long? = null,
    val currentValue: Long? = null,
    val equipmentSlot: String = "",
    val rarity: String = "",
)

@Dao
interface SimpleStateDao {
    @Query("SELECT * FROM simple_game_state WHERE id = 1")
    suspend fun load(): SimpleStateEntity?

    @Query("SELECT * FROM simple_game_state WHERE id BETWEEN 1 AND 3 ORDER BY id")
    suspend fun loadAllCharacterSlots(): List<SimpleStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SimpleStateEntity)

    @Query("DELETE FROM simple_game_state WHERE id = :slotId")
    suspend fun delete(slotId: Int)

    @Query("DELETE FROM recent_adventure_events WHERE characterSlotId = :slotId")
    suspend fun deleteRecentAdventureEvents(slotId: Int)

    @Query(
        "UPDATE recent_adventure_events SET characterSlotId = :newSlotId " +
            "WHERE characterSlotId = :oldSlotId",
    )
    suspend fun moveRecentAdventureEvents(oldSlotId: Int, newSlotId: Int)

    @Transaction
    suspend fun deleteAndCompactCharacterSlots(slotId: Int) {
        delete(slotId)
        deleteRecentAdventureEvents(slotId)
        compactCharacterSlots()
    }

    @Transaction
    suspend fun compactCharacterSlots() {
        loadAllCharacterSlots().forEachIndexed { index, entity ->
            val compactedSlotId = index + 1
            if (entity.id != compactedSlotId) {
                deleteRecentAdventureEvents(compactedSlotId)
                delete(entity.id)
                save(entity.copy(id = compactedSlotId))
                moveRecentAdventureEvents(entity.id, compactedSlotId)
            }
        }
    }
}

@Dao
interface RecentAdventureEventDao {
    @Insert
    suspend fun insertAll(events: List<RecentAdventureEventEntity>): List<Long>

    @Query(
        "SELECT * FROM recent_adventure_events WHERE characterSlotId = :slotId " +
            "ORDER BY occurredAt DESC, id DESC LIMIT :limit",
    )
    fun observeRecent(slotId: Int, limit: Int): Flow<List<RecentAdventureEventEntity>>

    @Query(
        "SELECT * FROM recent_adventure_events WHERE characterSlotId = :slotId " +
            "ORDER BY occurredAt DESC, id DESC LIMIT :limit",
    )
    suspend fun loadRecent(slotId: Int, limit: Int): List<RecentAdventureEventEntity>

    @Query(
        """
        UPDATE recent_adventure_events
        SET occurredAt = :trustedNow
        WHERE characterSlotId = :slotId AND occurredAt > :trustedNow
        """,
    )
    suspend fun clampFutureTimestamps(slotId: Int, trustedNow: Long)

    @Query(
        "DELETE FROM recent_adventure_events WHERE characterSlotId = :slotId AND id NOT IN " +
            "(SELECT id FROM recent_adventure_events WHERE characterSlotId = :slotId " +
            "ORDER BY occurredAt DESC, id DESC LIMIT :limit)",
    )
    suspend fun trimToLimit(slotId: Int, limit: Int)
}

@Dao
interface SimpleAccountProgressDao {
    @Query("SELECT * FROM simple_account_progress WHERE id = 1")
    suspend fun load(): SimpleAccountProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SimpleAccountProgressEntity)
}

@Database(
    entities = [
        SimpleStateEntity::class,
        SimpleAccountProgressEntity::class,
        RecentAdventureEventEntity::class,
    ],
    version = 16,
    exportSchema = true,
    autoMigrations = [
        androidx.room.AutoMigration(from = 12, to = 13),
        androidx.room.AutoMigration(from = 13, to = 14),
        androidx.room.AutoMigration(from = 14, to = 15),
        androidx.room.AutoMigration(from = 15, to = 16),
    ],
)
abstract class SimpleDatabase : RoomDatabase() {
    abstract fun stateDao(): SimpleStateDao
    abstract fun accountProgressDao(): SimpleAccountProgressDao
    abstract fun recentAdventureEventDao(): RecentAdventureEventDao
}
