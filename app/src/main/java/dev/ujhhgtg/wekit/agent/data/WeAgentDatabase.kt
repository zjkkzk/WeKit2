package dev.ujhhgtg.wekit.agent.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.ujhhgtg.wekit.agent.data.dao.ConditionalPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.MessageDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelDao
import dev.ujhhgtg.wekit.agent.data.dao.ModelProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.PerTurnPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.PresetPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ProviderDao
import dev.ujhhgtg.wekit.agent.data.dao.SessionDao
import dev.ujhhgtg.wekit.agent.data.dao.SettingDao
import dev.ujhhgtg.wekit.agent.data.dao.SystemPromptDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolCallDao
import dev.ujhhgtg.wekit.agent.data.dao.ToolPermissionDao
import dev.ujhhgtg.wekit.agent.data.dao.WorkspaceDao
import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.MessageEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.PerTurnPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.PresetPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.SessionEntity
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.data.entity.SystemPromptEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolCallEntity
import dev.ujhhgtg.wekit.agent.data.entity.ToolPermissionEntity
import dev.ujhhgtg.wekit.agent.data.entity.WorkspaceEntity
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        ToolPermissionEntity::class,
        ModelProviderEntity::class,
        ModelEntity::class,
        SystemPromptEntity::class,
        PerTurnPromptEntity::class,
        ConditionalPromptEntity::class,
        PresetPromptEntity::class,
        WorkspaceEntity::class,
        SettingEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(WeAgentConverters::class)
abstract class WeAgentDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerDao(): ProviderDao
    abstract fun toolPermissionDao(): ToolPermissionDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun perTurnPromptDao(): PerTurnPromptDao
    abstract fun conditionalPromptDao(): ConditionalPromptDao
    abstract fun presetPromptDao(): PresetPromptDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        /**
         * v3 → v4: adds the nullable `maxTokens` column to `models`. Additive, so existing models,
         * provider API keys, and chat history are preserved (unlike the destructive fallback, which
         * wipes everything). [fallbackToDestructiveMigration] stays as the last-resort safety net.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `maxTokens` INTEGER")
            }
        }

        /**
         * v4 → v5: adds the `supportsVision` flag to `models` (gates the `ui-screenshot` tool).
         * Additive & non-destructive, so existing models/keys/history survive.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun build(): WeAgentDatabase {
            val dbFile = KnownPaths.moduleData
                .resolve("agent")
                .createDirsSafe()
                .resolve("weagent.db")
            return Room.databaseBuilder(
                HostInfo.application,
                WeAgentDatabase::class.java,
                dbFile.toString())
                // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
                // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
