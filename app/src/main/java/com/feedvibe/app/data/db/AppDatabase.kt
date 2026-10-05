package com.feedvibe.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.feedvibe.app.data.sources.SourceType

class Converters {
    @TypeConverter
    fun fromType(type: SourceType): String = type.name

    @TypeConverter
    fun toType(value: String): SourceType = SourceType.fromName(value)
}

@Database(
    entities = [SubscriptionEntity::class, EpisodeEntity::class, EpisodeStateEntity::class],
    version = 1,
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
                .fallbackToDestructiveMigration()
                .build()
    }
}
