package com.nullplaying.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

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

    @Transaction
    suspend fun deleteAndCompactCharacterSlots(slotId: Int) {
        delete(slotId)
        compactCharacterSlots()
    }

    @Transaction
    suspend fun compactCharacterSlots() {
        loadAllCharacterSlots().forEachIndexed { index, entity ->
            val compactedSlotId = index + 1
            if (entity.id != compactedSlotId) {
                delete(entity.id)
                save(entity.copy(id = compactedSlotId))
            }
        }
    }
}

@Dao
interface SimpleAccountProgressDao {
    @Query("SELECT * FROM simple_account_progress WHERE id = 1")
    suspend fun load(): SimpleAccountProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SimpleAccountProgressEntity)
}

@Database(
    entities = [SimpleStateEntity::class, SimpleAccountProgressEntity::class],
    version = 15,
    exportSchema = true,
    autoMigrations = [
        androidx.room.AutoMigration(from = 12, to = 13),
        androidx.room.AutoMigration(from = 13, to = 14),
        androidx.room.AutoMigration(from = 14, to = 15),
    ],
)
abstract class SimpleDatabase : RoomDatabase() {
    abstract fun stateDao(): SimpleStateDao
    abstract fun accountProgressDao(): SimpleAccountProgressDao
}
