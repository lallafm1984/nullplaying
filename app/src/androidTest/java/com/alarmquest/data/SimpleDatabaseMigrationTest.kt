package com.alarmquest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SimpleDatabase::class.java,
    )

    @Test
    fun migrate12To15PreservesGameState() {
        assertGameStateSurvivesMigration(
            databaseName = "simple-migration-12",
            startVersion = 12,
            slotId = 1,
            payload = "legacy-v12",
        )
    }

    @Test
    fun migrate13To15PreservesGameState() {
        assertGameStateSurvivesMigration(
            databaseName = "simple-migration-13",
            startVersion = 13,
            slotId = 2,
            payload = "legacy-v13",
        )
    }

    @Test
    fun migrate14To15PreservesAccountProgressAndAddsDefaults() {
        val databaseName = "simple-migration-14"
        helper.createDatabase(databaseName, 14).use { database ->
            database.execSQL(
                "INSERT INTO simple_game_state (id, payload, updatedAt) VALUES (?, ?, ?)",
                arrayOf<Any>(3, "legacy-v14", 1_400L),
            )
            database.execSQL(
                "INSERT INTO simple_account_progress (id, unlockedCharacterSlots) VALUES (?, ?)",
                arrayOf<Any>(1, 3),
            )
        }

        helper.runMigrationsAndValidate(databaseName, 15, true).use { database ->
            database.query(
                "SELECT unlockedCharacterSlots, activeCharacterSlotId, revision " +
                    "FROM simple_account_progress WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertEquals(0L, cursor.getLong(2))
            }
            assertGameStateRow(database, slotId = 3, payload = "legacy-v14")
        }
    }

    private fun assertGameStateSurvivesMigration(
        databaseName: String,
        startVersion: Int,
        slotId: Int,
        payload: String,
    ) {
        helper.createDatabase(databaseName, startVersion).use { database ->
            database.execSQL(
                "INSERT INTO simple_game_state (id, payload, updatedAt) VALUES (?, ?, ?)",
                arrayOf<Any>(slotId, payload, 1_000L + startVersion),
            )
        }

        helper.runMigrationsAndValidate(databaseName, 15, true).use { database ->
            assertGameStateRow(database, slotId, payload)
        }
    }

    private fun assertGameStateRow(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        slotId: Int,
        payload: String,
    ) {
        database.query(
            "SELECT payload FROM simple_game_state WHERE id = ?",
            arrayOf<Any>(slotId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(payload, cursor.getString(0))
        }
    }
}
