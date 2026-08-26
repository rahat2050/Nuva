package com.nuva.assistant.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nuva.assistant.database.dao.CommandHistoryDao
import com.nuva.assistant.database.dao.LocalMemoryDao
import com.nuva.assistant.database.dao.NoteDao
import com.nuva.assistant.database.dao.PendingActionDao
import com.nuva.assistant.database.entities.CommandHistoryEntity
import com.nuva.assistant.database.entities.LocalMemoryEntity
import com.nuva.assistant.database.entities.NoteEntity
import com.nuva.assistant.database.entities.PendingActionEntity

/**
 * Room database (roadmap step 14): CommandHistory, LocalMemory, PendingAction,
 * and (v1.1) Notes/To-dos. UserPreferences live in DataStore.
 */
@Database(
    entities = [
        CommandHistoryEntity::class,
        PendingActionEntity::class,
        LocalMemoryEntity::class,
        NoteEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun localMemoryDao(): LocalMemoryDao
    abstract fun noteDao(): NoteDao

    companion object {

        /** v1 → v2: notes table + command_history.error column (no data loss). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, `kind` TEXT NOT NULL, `done` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_kind` ON `notes` (`kind`)")
                db.execSQL("ALTER TABLE `command_history` ADD COLUMN `error` TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nuva.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
