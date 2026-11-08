package dev.nathanxu.dayplanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Goal::class, Routine::class, TravelCache::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun goals(): GoalDao
    abstract fun routines(): RoutineDao
    abstract fun travel(): TravelDao

    companion object {
        /**
         * v1.3: plans get an explicit end date and breakdown size. Both nullable with no default,
         * so existing goals keep working (null end = end of their week/month, null cadence = daily).
         * Categories became tag keys: same TEXT column, and the built-in keys equal the old enum
         * names, so no data change is needed.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `goals` ADD COLUMN `endDate` INTEGER")
                db.execSQL("ALTER TABLE `goals` ADD COLUMN `cadence` TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dayplanner.db")
                .addMigrations(MIGRATION_2_3)
                // Only for very old test installs (v1.0/1.1) that have no migration path.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
