package com.nuva.assistant.memory

import com.nuva.assistant.core.security.SecurityPolicy
import com.nuva.assistant.core.security.SensitiveAppPolicy
import com.nuva.assistant.database.AppDatabase
import com.nuva.assistant.database.dao.LocalMemoryDao
import com.nuva.assistant.database.dao.put
import com.nuva.assistant.supabase.SupabaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Local-first memory (roadmap step 16): Room is the source of truth; Supabase
 * is a mirror. Credential-like keys are refused — memory stores preferences
 * only (§17), mirroring /api/memory's guard.
 */
class MemoryManager(
    database: AppDatabase,
    private val supabaseProvider: () -> SupabaseRepository,
) {

    private val dao: LocalMemoryDao = database.localMemoryDao()

    val all: Flow<List<com.nuva.assistant.database.entities.LocalMemoryEntity>> = dao.all()

    suspend fun remember(key: String, value: String): Result<Unit> {
        val cleanValue = value.trim()
        if (
            !SecurityPolicy.isMemoryKeyAllowed(key) ||
            cleanValue.isEmpty() ||
            SensitiveAppPolicy.mentionsCredentials(cleanValue)
        ) {
            return Result.failure(IllegalArgumentException("NUVA stores non-sensitive, non-empty preferences only"))
        }
        dao.put(key.trim().lowercase(), cleanValue.take(4000))
        return Result.success(Unit)
    }

    suspend fun recall(key: String): String? {
        if (!SecurityPolicy.isMemoryKeyAllowed(key)) return null
        return dao.get(key.trim().lowercase())?.value
    }

    suspend fun forget(key: String): Boolean {
        if (!SecurityPolicy.isMemoryKeyAllowed(key)) return false
        val existing = dao.get(key.trim().lowercase()) ?: return false
        dao.delete(key.trim().lowercase())
        runCatchingCancellable { supabaseProvider().forgetMemory(existing.key) }
        return true
    }

    /** Push unsynced rows to Supabase (called by SyncManager). */
    suspend fun pushUnsynced(): Int {
        val unsynced = dao.unsynced()
        var pushed = 0
        for (row in unsynced) {
            if (!SecurityPolicy.isMemoryKeyAllowed(row.key) || SensitiveAppPolicy.mentionsCredentials(row.value)) {
                continue
            }
            val ok = runCatchingCancellable {
                supabaseProvider().saveMemory(row.key, row.value)
            }.getOrDefault(false)
            if (ok) {
                dao.markSynced(row.key, System.currentTimeMillis())
                pushed += 1
            }
        }
        return pushed
    }

    /** Pull the server mirror into local storage (last-write-wins by updated_at). */
    suspend fun pull(): Int {
        val remote = runCatchingCancellable { supabaseProvider().listMemory() }.getOrNull() ?: return 0
        var pulled = 0
        for (row in remote) {
            if (!SecurityPolicy.isMemoryKeyAllowed(row.key) || SensitiveAppPolicy.mentionsCredentials(row.value)) continue
            dao.put(row.key, row.value)
            dao.markSynced(row.key, System.currentTimeMillis())
            pulled += 1
        }
        return pulled
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
