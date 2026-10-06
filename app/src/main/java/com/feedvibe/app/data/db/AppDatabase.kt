package com.feedvibe.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.feedvibe.app.data.sources.SourceType

class Converters {
    @TypeConverter
    fun fromType(type: SourceType): String = type.name

    @TypeConverter
    fun toType(value: String): SourceType = SourceType.fromName(value)
}

@Database(
    entities = [SubscriptionEntity::class, EpisodeEntity::class, EpisodeStateEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao
    abstract fun episodes(): EpisodeDao
    abstract fun states(): EpisodeStateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "feedvibe.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: canales en pausa y marca de Shorts. Conserva todos los datos. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN isShort INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE episodes SET isShort = 1 WHERE url LIKE '%/shorts/%'")
            }
        }
    }
}
