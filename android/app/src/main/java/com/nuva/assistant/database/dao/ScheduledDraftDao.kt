package com.nuva.assistant.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.nuva.assistant.database.entities.ScheduledDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledDraftDao {
    @Insert
    suspend fun insert(row: ScheduledDraftEntity): Long

    @Query("SELECT * FROM scheduled_drafts WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ScheduledDraftEntity?

    @Query("SELECT * FROM scheduled_drafts WHERE status = 'pending' ORDER BY triggerAt ASC")
    suspend fun pendingOnce(): List<ScheduledDraftEntity>

    @Query("SELECT * FROM scheduled_drafts WHERE status = 'pending' ORDER BY triggerAt ASC")
    fun pending(): Flow<List<ScheduledDraftEntity>>

    @Query("UPDATE scheduled_drafts SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE scheduled_drafts SET triggerAt = :triggerAt, status = 'pending' WHERE id = :id")
    suspend fun reschedule(id: Long, triggerAt: Long)
}
