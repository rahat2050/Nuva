package com.nuva.assistant.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nuva.assistant.database.entities.CommandHistoryEntity
import com.nuva.assistant.database.entities.LocalMemoryEntity
import com.nuva.assistant.database.entities.NoteEntity
import com.nuva.assistant.database.entities.PendingActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandHistoryDao {

    @Insert
    suspend fun insertRow(row: CommandHistoryEntity): Long

    @Query("UPDATE command_history SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE command_history SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatusAndError(id: Long, status: String, error: String?)

    @Query("SELECT * FROM command_history WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): CommandHistoryEntity?

    @Query("SELECT * FROM command_history ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int = 100): List<CommandHistoryEntity>

    @Query("DELETE FROM command_history")
    suspend fun clear()
}

/** Convenience wrappers so callers read like prose. */
suspend fun CommandHistoryDao.insert(
    text: String,
    intent: String,
    risk: String,
    status: String,
): Long = insertRow(CommandHistoryEntity(text = text, intent = intent, risk = risk, status = status))

@Dao
interface PendingActionDao {

    @Insert
    suspend fun insertRow(row: PendingActionEntity): Long

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun get(id: Long): PendingActionEntity?

    @Query("UPDATE pending_actions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** Re-parks a pending action after e.g. a contact choice resolves a number. */
    @Query("UPDATE pending_actions SET actionJson = :actionJson WHERE id = :id")
    suspend fun updateAction(id: Long, actionJson: String)

    @Query("SELECT * FROM pending_actions WHERE status = 'pending' ORDER BY createdAt DESC")
    fun pending(): Flow<List<PendingActionEntity>>

    @Query("UPDATE pending_actions SET status = 'expired' WHERE status = 'pending' AND createdAt < :threshold")
    suspend fun expireOlderThan(threshold: Long)

    /** Called once at boot: anything still pending from a previous run is stale. */
    @Query("UPDATE pending_actions SET status = 'expired' WHERE status = 'pending'")
    suspend fun expireAllPending()
}

suspend fun PendingActionDao.insert(
    localCommandId: Long?,
    commandText: String,
    actionJson: String,
    risk: String,
    serverCommandId: String?,
): Long = insertRow(
    PendingActionEntity(
        localCommandId = localCommandId,
        commandText = commandText,
        actionJson = actionJson,
        risk = risk,
        serverCommandId = serverCommandId,
    ),
)

@Dao
interface LocalMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LocalMemoryEntity)

    @Query("SELECT * FROM local_memory WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): LocalMemoryEntity?

    @Query("SELECT * FROM local_memory ORDER BY updatedAt DESC")
    fun all(): Flow<List<LocalMemoryEntity>>

    @Query("SELECT * FROM local_memory WHERE syncedAt IS NULL")
    suspend fun unsynced(): List<LocalMemoryEntity>

    @Query("UPDATE local_memory SET syncedAt = :timestamp WHERE `key` = :key")
    suspend fun markSynced(key: String, timestamp: Long)

    @Query("DELETE FROM local_memory WHERE `key` = :key")
    suspend fun delete(key: String)
}

suspend fun LocalMemoryDao.put(key: String, value: String) {
    upsert(LocalMemoryEntity(key = key, value = value, updatedAt = System.currentTimeMillis(), syncedAt = null))
}

@Dao
interface NoteDao {

    @Insert
    suspend fun insertRow(row: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    fun byKind(kind: String, limit: Int = 100): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byKindOnce(kind: String, limit: Int = 100): List<NoteEntity>

    @Query("UPDATE notes SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notes WHERE kind = :kind AND done = 1")
    suspend fun clearDone(kind: String)
}
