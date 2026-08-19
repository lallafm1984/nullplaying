package com.alarmquest.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "simple_game_state")
data class SimpleStateEntity(
    @PrimaryKey val id: Int = 1,
    val payload: String,
    val updatedAt: Long,
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
}

@Database(
    entities = [SimpleStateEntity::class],
    version = 13,
    exportSchema = true,
    autoMigrations = [
        androidx.room.AutoMigration(from = 12, to = 13),
    ],
)
abstract class SimpleDatabase : RoomDatabase() {
    abstract fun stateDao(): SimpleStateDao
}
