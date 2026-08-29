package com.nuva.assistant.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit trail — every command, executed or not (§ audit / history screen).
 * `error` holds the user-facing failure reason for failed/blocked commands so
 * History can explain *why* something did not work and offer a retry.
 */
@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val intent: String,
    val risk: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Server row id when the command was interpreted online. */
    val serverCommandId: String? = null,
    /** Why the command failed / was blocked (null when it did not). */
    val error: String? = null,
)

/**
 * A medium/high-risk decision parked until the user answers the blocking
 * confirmation dialog. Status: pending | confirmed | rejected | expired | blocked.
 */
@Entity(tableName = "pending_actions", indices = [Index("status")])
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localCommandId: Long?,
    val commandText: String,
    /** Serialized validated action (ActionJson) — decoded THROUGH the validator. */
    val actionJson: String,
    val risk: String,
    val serverCommandId: String?,
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Local long-term memory — preferences only, never credentials (§17). */
@Entity(tableName = "local_memory", indices = [Index(value = ["key"], unique = true)])
data class LocalMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Set when this row was confirmed synced to Supabase. */
    val syncedAt: Long? = null,
)

/**
 * Notes & to-dos captured by voice (v1.1). `kind` is "note" or "todo";
 * to-dos carry a `done` flag. Local-first: never leaves the device unless the
 * user turns on Supabase memory sync later.
 */
@Entity(tableName = "notes", indices = [Index("kind")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val kind: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Persistent user-created email/SMS draft reminder; never an automatic send. */
@Entity(tableName = "scheduled_drafts", indices = [Index("status"), Index("triggerAt")])
data class ScheduledDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val recipient: String?,
    val subject: String?,
    val body: String,
    val triggerAt: Long,
    val recurrence: String,
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
)
