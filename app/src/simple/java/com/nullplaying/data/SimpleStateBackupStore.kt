package com.nullplaying.data

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SimpleStateBackupStore {
    suspend fun load(slotId: Int): SimpleStateEntity?

    suspend fun save(entity: SimpleStateEntity)

    suspend fun clear(slotId: Int)

    suspend fun loadAccountProgress(): SimpleAccountProgressEntity?

    suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity)
}

/** Keeps character recovery copies and account metadata outside the Room database. */
class FileSimpleStateBackupStore(directory: File) : SimpleStateBackupStore {
    private val backupDirectory = directory
    private val json = Json { encodeDefaults = true }

    override suspend fun load(slotId: Int): SimpleStateEntity? = withContext(Dispatchers.IO) {
        val atomicFile = atomicFile(slotId)
        if (!atomicFile.baseFile.exists()) return@withContext null
        val envelope = json.decodeFromString<BackupEnvelope>(
            atomicFile.readFully().toString(Charsets.UTF_8),
        )
        require(envelope.formatVersion == BACKUP_FORMAT_VERSION) {
            "Unsupported save backup format ${envelope.formatVersion}"
        }
        require(envelope.checksum == checksum(envelope.payload, envelope.updatedAt)) {
            "Save backup checksum mismatch"
        }
        SimpleStateEntity(id = slotId, payload = envelope.payload, updatedAt = envelope.updatedAt)
    }

    override suspend fun save(entity: SimpleStateEntity) = withContext(Dispatchers.IO) {
        val atomicFile = atomicFile(entity.id)
        atomicFile.baseFile.parentFile?.mkdirs()
        val encoded = json.encodeToString(
            BackupEnvelope(
                payload = entity.payload,
                updatedAt = entity.updatedAt,
                checksum = checksum(entity.payload, entity.updatedAt),
            ),
        ).toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(encoded)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    override suspend fun clear(slotId: Int) = withContext(Dispatchers.IO) {
        atomicFile(slotId).delete()
    }

    override suspend fun loadAccountProgress(): SimpleAccountProgressEntity? =
        withContext(Dispatchers.IO) {
            val atomicFile = accountProgressAtomicFile()
            if (!atomicFile.baseFile.exists()) return@withContext null
            val envelope = json.decodeFromString<AccountProgressBackupEnvelope>(
                atomicFile.readFully().toString(Charsets.UTF_8),
            )
            require(envelope.formatVersion == ACCOUNT_BACKUP_FORMAT_VERSION) {
                "Unsupported account backup format ${envelope.formatVersion}"
            }
            require(
                envelope.checksum == accountProgressChecksum(
                    unlockedCharacterSlots = envelope.unlockedCharacterSlots,
                    activeCharacterSlotId = envelope.activeCharacterSlotId,
                    revision = envelope.revision,
                ),
            ) {
                "Account backup checksum mismatch"
            }
            require(envelope.unlockedCharacterSlots in 1..MAX_CHARACTER_SLOTS) {
                "Invalid unlocked character slot count"
            }
            require(
                envelope.activeCharacterSlotId == null ||
                    envelope.activeCharacterSlotId in 1..MAX_CHARACTER_SLOTS,
            ) {
                "Invalid active character slot"
            }
            require(envelope.revision >= 0L) { "Invalid account backup revision" }
            SimpleAccountProgressEntity(
                unlockedCharacterSlots = envelope.unlockedCharacterSlots,
                activeCharacterSlotId = envelope.activeCharacterSlotId,
                revision = envelope.revision,
            )
        }

    override suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity) =
        withContext(Dispatchers.IO) {
            require(entity.id == 1) { "Unsupported account progress id ${entity.id}" }
            require(entity.unlockedCharacterSlots in 1..MAX_CHARACTER_SLOTS) {
                "Invalid unlocked character slot count"
            }
            require(
                entity.activeCharacterSlotId == null ||
                    entity.activeCharacterSlotId in 1..MAX_CHARACTER_SLOTS,
            ) {
                "Invalid active character slot"
            }
            require(entity.revision >= 0L) { "Invalid account progress revision" }
            val atomicFile = accountProgressAtomicFile()
            atomicFile.baseFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(
                AccountProgressBackupEnvelope(
                    unlockedCharacterSlots = entity.unlockedCharacterSlots,
                    activeCharacterSlotId = entity.activeCharacterSlotId,
                    revision = entity.revision,
                    checksum = accountProgressChecksum(
                        unlockedCharacterSlots = entity.unlockedCharacterSlots,
                        activeCharacterSlotId = entity.activeCharacterSlotId,
                        revision = entity.revision,
                    ),
                ),
            ).toByteArray(Charsets.UTF_8)
            val output = atomicFile.startWrite()
            try {
                output.write(encoded)
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
        }

    private fun atomicFile(slotId: Int): AtomicFile {
        require(slotId in 1..MAX_CHARACTER_SLOTS) { "Unsupported character slot $slotId" }
        val fileName = if (slotId == 1) {
            LEGACY_SLOT_ONE_BACKUP_FILE_NAME
        } else {
            "simple_game_state.slot_$slotId.previous.json"
        }
        return AtomicFile(File(backupDirectory, fileName))
    }

    private fun accountProgressAtomicFile(): AtomicFile =
        AtomicFile(File(backupDirectory, ACCOUNT_PROGRESS_BACKUP_FILE_NAME))

    private fun checksum(payload: String, updatedAt: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(updatedAt.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(payload.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun accountProgressChecksum(
        unlockedCharacterSlots: Int,
        activeCharacterSlotId: Int?,
        revision: Long,
    ): String = checksum(
        payload = "$unlockedCharacterSlots:${activeCharacterSlotId ?: "none"}",
        updatedAt = revision,
    )

    @Serializable
    private data class BackupEnvelope(
        val formatVersion: Int = BACKUP_FORMAT_VERSION,
        val payload: String,
        val updatedAt: Long,
        val checksum: String,
    )

    @Serializable
    private data class AccountProgressBackupEnvelope(
        val formatVersion: Int = ACCOUNT_BACKUP_FORMAT_VERSION,
        val unlockedCharacterSlots: Int,
        val activeCharacterSlotId: Int? = null,
        val revision: Long,
        val checksum: String,
    )

    private companion object {
        const val BACKUP_FORMAT_VERSION = 1
        const val ACCOUNT_BACKUP_FORMAT_VERSION = 1
        const val LEGACY_SLOT_ONE_BACKUP_FILE_NAME = "simple_game_state.previous.json"
        const val ACCOUNT_PROGRESS_BACKUP_FILE_NAME = "simple_account_progress.json"
    }
}
