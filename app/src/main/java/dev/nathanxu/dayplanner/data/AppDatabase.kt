package dev.nathanxu.dayplanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Task::class, Goal::class, Routine::class, TravelCache::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun goals(): GoalDao
    abstract fun routines(): RoutineDao
    abstract fun travel(): TravelDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dayplanner.db").build()
    }
}
