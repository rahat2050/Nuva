package com.nuva.assistant.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nuva.assistant.database.dao.CommandHistoryDao
import com.nuva.assistant.database.dao.LocalMemoryDao
import com.nuva.assistant.database.dao.PendingActionDao
import com.nuva.assistant.database.entities.CommandHistoryEntity
import com.nuva.assistant.database.entities.LocalMemoryEntity
import com.nuva.assistant.database.entities.PendingActionEntity

/**
 * Room database (roadmap step 14): CommandHistory, LocalMemory, PendingAction.
 * UserPreferences live in DataStore (see memory/UserPreferences.kt).
 */
@Database(
    entities = [
        CommandHistoryEntity::class,
        PendingActionEntity::class,
        LocalMemoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun localMemoryDao(): LocalMemoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nuva.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
