package com.alarmquest.data

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
}

/** Keeps the previous valid save outside the Room database, like Progress Quest's .bak file. */
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

    private fun atomicFile(slotId: Int): AtomicFile {
        require(slotId in 1..MAX_CHARACTER_SLOTS) { "Unsupported character slot $slotId" }
        val fileName = if (slotId == 1) {
            LEGACY_SLOT_ONE_BACKUP_FILE_NAME
        } else {
            "simple_game_state.slot_$slotId.previous.json"
        }
        return AtomicFile(File(backupDirectory, fileName))
    }

    private fun checksum(payload: String, updatedAt: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(updatedAt.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(payload.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    @Serializable
    private data class BackupEnvelope(
        val formatVersion: Int = BACKUP_FORMAT_VERSION,
        val payload: String,
        val updatedAt: Long,
        val checksum: String,
    )

    private companion object {
        const val BACKUP_FORMAT_VERSION = 1
        const val LEGACY_SLOT_ONE_BACKUP_FILE_NAME = "simple_game_state.previous.json"
    }
}
