package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodoGroupsTest {
    private val mon = LocalDate.of(2026, 11, 9)

    @Test fun labels() {
        assertEquals("Overdue", TodoGroups.of(mon.minusDays(3), mon).label)
        assertEquals("Today", TodoGroups.of(mon, mon).label)
        assertEquals("Tomorrow", TodoGroups.of(mon.plusDays(1), mon).label)
        assertEquals("Friday 13 Nov", TodoGroups.of(mon.plusDays(4), mon).label)
        // 7–27 days out: by week (Monday of that week)
        assertEquals("Week of 16 Nov", TodoGroups.of(mon.plusDays(9), mon).label)
        assertEquals("Week of 30 Nov", TodoGroups.of(mon.plusDays(21), mon).label)
        // 28+ days: by month, with the year when it isn't this year
        assertEquals("December", TodoGroups.of(LocalDate.of(2026, 12, 20), mon).label)
        assertEquals("January 2027", TodoGroups.of(LocalDate.of(2027, 1, 5), mon).label)
        assertEquals("Someday", TodoGroups.of(null, mon).label)
    }

    @Test fun orderAndGrouping() {
        val dates = listOf(
            null, LocalDate.of(2027, 1, 5), mon.plusDays(9), mon.plusDays(10), mon,
            mon.minusDays(1), mon.plusDays(1), mon.plusDays(3), LocalDate.of(2026, 12, 20),
        )
        val groups = TodoGroups.group(dates, mon) { it }
        assertEquals(
            listOf("Overdue", "Today", "Tomorrow", "Thursday 12 Nov", "Week of 16 Nov", "December", "January 2027", "Someday"),
            groups.map { it.first },
        )
        assertEquals(2, groups.first { it.first == "Week of 16 Nov" }.second.size)
    }
}

class V161Test {
    private val mon = LocalDate.of(2026, 11, 9)

    @Test fun durations() {
        assertEquals(120, QuickAdd.parseDuration("2hr"))
        assertEquals(120, QuickAdd.parseDuration("2hrs"))
        assertEquals(120, QuickAdd.parseDuration("2hours"))
        assertEquals(90, QuickAdd.parseDuration("1.5h"))
        assertEquals(90, QuickAdd.parseDuration("1h30"))
        assertEquals(90, QuickAdd.parseDuration("1hr30min"))
        assertEquals(45, QuickAdd.parseDuration("45mins"))
        assertEquals(null, QuickAdd.parseDuration("2"))
        val t = QuickAdd.parse("revise mechanics 2hr tmr", mon) as TaskDraft
        assertEquals("revise mechanics", t.title)
        assertEquals(120, t.minutes)
    }

    @Test fun remainingTodayShrinksWithProgressAndNeverInflates() {
        val sun = mon.plusDays(6)
        // Weekly bucket, 5 this week: nothing done → 5; 2 done today → 3; spam past target → 0.
        assertEquals(5, Pacing.remainingToday(5, mon, sun, Cadence.WEEKLY, mon, 0, 0, 0))
        assertEquals(3, Pacing.remainingToday(5, mon, sun, Cadence.WEEKLY, mon, 0, 2, 2))
        assertEquals(0, Pacing.remainingToday(5, mon, sun, Cadence.WEEKLY, mon, 0, 9, 9))
        // Undoing back down restores exactly what's left, no more.
        assertEquals(1, Pacing.remainingToday(5, mon, sun, Cadence.WEEKLY, mon, 0, 4, 4))
    }

    @Test fun ongoingTasks() {
        val end = Pacing.ONGOING_END
        // 3 a week, ongoing: Wed with 1 done Mon → 2 left this week.
        assertEquals(mon, Pacing.bucketStart(Cadence.WEEKLY, LocalDate.of(2026, 10, 1), mon.plusDays(2)))
        assertEquals(2, Pacing.remainingToday(3, LocalDate.of(2026, 10, 1), end, Cadence.WEEKLY, mon.plusDays(2), 7, 0, 1))
        // Daily, 1 a day: done today → 0.
        assertEquals(0, Pacing.remainingToday(1, mon, end, Cadence.DAILY, mon.plusDays(5), 5, 1, 1))
        assertEquals(LocalDate.of(2026, 11, 1), Pacing.bucketStart(Cadence.MONTHLY, mon, mon.plusDays(3)))
        assertEquals(mon.plusDays(3), Pacing.bucketStart(Cadence.THREE_DAY, mon, mon.plusDays(4)))
        val s = GoalStatus.ongoing(3, 1, 0, 0)
        assertEquals(33, s.percent)
    }
}
