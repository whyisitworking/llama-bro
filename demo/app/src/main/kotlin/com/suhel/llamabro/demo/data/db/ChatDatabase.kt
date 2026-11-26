package com.suhel.llamabro.demo.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * `chats.db`; each version's schema is exported to `demo/app/schemas/`. Version 2 adds a reply's measurements
 * ([ReplyStats]), version 3 a chat's own sampling values (D60), version 4 voice messages (D76): nullable columns, so
 * Room migrates on its own. Version 5 adds the Hub cache and the download records, new tables only;
 * version 6 a record's model-file bytes (a column with a default), so a download's fit takes its speed from the model
 * file alone; version 7 Hugging Face search's pages with their cursors in place of the one-page answer per query
 * (models-search design, D127); version 8 a chat's "Use my library" and a reply's sources (docs/design/rag.md): a
 * column with a default and a nullable one.
 */
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        HubResultEntity::class,
        HubSearchPageEntity::class,
        HubRepoEntity::class,
        HubFilesEntity::class,
        HubPageEntity::class,
        RecentSearchEntity::class,
        DownloadRecordEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = ChatDatabase.SearchPages::class),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class ChatDatabase : RoomDatabase() {
    /** The one-page answers are dropped: a search is paged now, and its first refresh fetches it again. */
    @DeleteTable(tableName = "hub_search")
    class SearchPages : AutoMigrationSpec

    abstract fun chatDao(): ChatDao

    abstract fun hubCacheDao(): HubCacheDao
}
