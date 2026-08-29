package com.nuva.assistant.supabase

import android.os.Build
import com.nuva.assistant.ai.AIRepository
import com.nuva.assistant.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud sync (roadmap steps 15–16): memory push/pull + device registration.
 * Local-first: every failure degrades silently to "sync later" and NEVER
 * breaks command execution.
 */
class SyncManager(
    private val aiRepository: AIRepository,
    private val supabaseRepository: SupabaseRepository,
    private val memoryManager: MemoryManager,
) {

    data class SyncReport(val pushedMemories: Int, val pulledMemories: Int, val deviceRegistered: Boolean)

    suspend fun syncAll(): SyncReport = withContext(Dispatchers.IO) {
        val pushed = runCatchingCancellable { memoryManager.pushUnsynced() }.getOrDefault(0)
        val pulled = runCatchingCancellable { memoryManager.pull() }.getOrDefault(0)

        val registered = runCatchingCancellable {
            supabaseRepository.registerDevice(
                deviceName = "${Build.MANUFACTURER ?: "Android"} ${Build.MODEL ?: ""}".trim().ifEmpty { "Android device" },
                androidVersion = Build.VERSION.RELEASE,
            )
        }.getOrDefault(false)

        SyncReport(pushed, pulled, registered)
    }

    /** Best-effort health probe for the settings screen. */
    suspend fun healthOk(): Boolean = withContext(Dispatchers.IO) {
        runCatchingCancellable { aiRepository.healthOk() }.getOrDefault(false)
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
