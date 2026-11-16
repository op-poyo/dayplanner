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
    version = 8,
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

        /** v1.6.2: per-to-do reminder lead time and a per-routine reminder switch. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `remindBefore` INTEGER")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `remind` INTEGER")
            }
        }

        /** v1.7: priority, deadline, place, and the planner's calendar blocks. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `priority` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `deadline` INTEGER")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `place` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `plannedEvents` TEXT")
                db.execSQL("ALTER TABLE `goals` ADD COLUMN `priority` TEXT")
            }
        }

        /** v1.8.1: remind at an exact time. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `remindAt` INTEGER")
            }
        }

        /** v1.9: repeat rules, skipped generated to-dos, sittings of split plans. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `sittings` TEXT")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `skipped` INTEGER")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `repeatUnit` TEXT")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `repeatEvery` INTEGER")
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `repeatFrom` INTEGER")
            }
        }

        /**
         * v1.12: goals stop making to-dos and count linked ones per period.
         * - Repeating (!r) week/month goals become one ongoing "N a week/month" goal; `cadence` now
         *   means "per" (null = N in total over the goal's dates).
         * - Open auto-made daily to-dos are removed; +1 logs become ordinary done to-dos (they
         *   keep counting, and now show in history).
         * - Repeating to-dos can count towards a goal (routines.goalId).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_7_8_SQL.forEach { db.execSQL(it) }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dayplanner.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                // Only for very old test installs (v1.0/1.1) that have no migration path.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}

/** Kept apart (and plain SQL) so it can be checked against SQLite off-device. 2932896 = 9999-12-31, "no end". */
internal val MIGRATION_7_8_SQL = listOf(
    "ALTER TABLE `routines` ADD COLUMN `goalId` INTEGER",
    // Repeating weekly/monthly goals → ongoing "per" goals.
    "UPDATE `goals` SET `cadence` = CASE `period` WHEN 'WEEK' THEN 'WEEKLY' ELSE 'MONTHLY' END, `endDate` = 2932896, `recurring` = 0 " +
        "WHERE `recurring` = 1 AND `archived` = 0 AND `period` IN ('WEEK', 'MONTH')",
    // A repeating custom 7-day goal is a weekly one; other repeating custom ones just end.
    "UPDATE `goals` SET `cadence` = 'WEEKLY', `endDate` = 2932896, `recurring` = 0 " +
        "WHERE `recurring` = 1 AND `archived` = 0 AND `period` = 'CUSTOM' AND `endDate` - `periodStart` = 6",
    "UPDATE `goals` SET `recurring` = 0 WHERE `archived` = 0",
    // Ongoing goals keep their per-period ("3 a week"); none/unset meant daily.
    "UPDATE `goals` SET `cadence` = CASE WHEN `cadence` IS NULL OR `cadence` = 'NONE' THEN 'DAILY' ELSE `cadence` END WHERE `endDate` = 2932896",
    // Everything else is "N in total by the end": the old breakdown setting goes.
    "UPDATE `goals` SET `cadence` = NULL WHERE `endDate` IS NULL OR `endDate` != 2932896",
    // Auto-made to-dos that weren't ticked go; ticked ones stay (and count).
    "DELETE FROM `tasks` WHERE `genKey` LIKE 'g:%' AND `done` = 0",
    // +1 logs become ordinary done to-dos.
    "UPDATE `tasks` SET `genKey` = NULL WHERE `genKey` LIKE 'l:%'",
)

