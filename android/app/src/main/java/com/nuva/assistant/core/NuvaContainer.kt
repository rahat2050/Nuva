package com.nuva.assistant.core

import android.content.Context
import com.nuva.assistant.ai.AIRepository
import com.nuva.assistant.command.CommandExecutor
import com.nuva.assistant.database.AppDatabase
import com.nuva.assistant.memory.MemoryManager
import com.nuva.assistant.memory.UserPreferences
import com.nuva.assistant.supabase.SupabaseRepository
import com.nuva.assistant.supabase.SyncManager

/**
 * Tiny service locator — one place that owns object creation and wiring.
 * Initialized from [com.nuva.assistant.NuvaApplication]; nothing else builds
 * singletons.
 */
object NuvaContainer {

    lateinit var appContext: Context
        private set

    val preferences: UserPreferences by lazy { UserPreferences(appContext) }

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val memory: MemoryManager by lazy { MemoryManager(database) { supabaseRepository } }

    val supabaseRepository: SupabaseRepository by lazy {
        SupabaseRepository(
            baseUrlProvider = { preferences.baseUrlBlocking() },
            supabaseUrlProvider = { preferences.supabaseUrlBlocking() },
            anonKeyProvider = { preferences.supabaseAnonKeyBlocking() },
        ).also { repo ->
            repo.tokenProvider = { preferences.accessToken() }
        }
    }

    val aiRepository: AIRepository by lazy {
        AIRepository(
            baseUrlProvider = { preferences.baseUrlBlocking() },
            tokenProvider = { preferences.accessToken() },
            deviceIdProvider = { DeviceId.get(appContext) },
        )
    }

    val commandExecutor: CommandExecutor by lazy {
        CommandExecutor(
            contextProvider = { appContext },
            aiRepository = aiRepository,
            preferences = preferences,
            history = database.commandHistoryDao(),
            pendingActions = database.pendingActionDao(),
            supabaseRepository = supabaseRepository,
            notes = database.noteDao(),
        )
    }

    val syncManager: SyncManager by lazy {
        SyncManager(
            aiRepository = aiRepository,
            supabaseRepository = supabaseRepository,
            memoryManager = memory,
        )
    }

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }
}
