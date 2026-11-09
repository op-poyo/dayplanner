package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RepeatTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private fun mask(vararg d: DayOfWeek) = d.fold(0) { m, x -> m or Days.bit(x) }

    @Test fun legacyWeeklyMatchesOldBehaviour() {
        val r = Repeat.weekly(mask(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        assertTrue(r.isValid)
        assertEquals(listOf(mon, mon.plusDays(3), mon.plusDays(7)), r.occurrences(mon, 8))
        assertTrue(r.occursOn(mon.minusWeeks(52))) // no start: any time
    }

    @Test fun everyNDays() {
        val r = Repeat(Repeat.Unit.DAY, 3, from = mon)
        assertEquals(listOf(mon, mon.plusDays(3), mon.plusDays(6)), r.occurrences(mon.minusDays(2), 9))
        assertEquals("Every 3 days", r.describe())
        assertEquals("FREQ=DAILY;INTERVAL=3", r.rrule())
    }

    @Test fun fortnightlyFromStartWeek() {
        // Starts Wed 11 Nov; Mon+Wed every 2 weeks → Wed 11, Mon 23, Wed 25 (not Mon 9: before start).
        val r = Repeat(Repeat.Unit.WEEK, 2, mask(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), mon.plusDays(2))
        assertEquals(listOf(mon.plusDays(2), mon.plusDays(14), mon.plusDays(16)), r.occurrences(mon, 21))
        assertEquals("Every 2 weeks · " + Days.describe(mask(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)), r.describe())
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=MO,WE", r.rrule())
    }

    @Test fun monthlyClampsToShortMonths() {
        val r = Repeat(Repeat.Unit.MONTH, 1, from = LocalDate.of(2026, 1, 31))
        val hits = r.occurrences(LocalDate.of(2026, 1, 1), 120)
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)), hits)
        assertEquals("Monthly on the 31st", r.describe())
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=28,29,30,31;BYSETPOS=-1", r.rrule())
        val q = Repeat(Repeat.Unit.MONTH, 3, from = LocalDate.of(2026, 11, 15))
        assertEquals(listOf(LocalDate.of(2026, 11, 15), LocalDate.of(2027, 2, 15)), q.occurrences(LocalDate.of(2026, 11, 1), 120))
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=15", q.rrule())
        assertEquals("Every 3 months on the 15th", q.describe())
    }

    @Test fun firstAndValidity() {
        assertEquals(mon.plusDays(2), Repeat(Repeat.Unit.WEEK, 2, mask(DayOfWeek.WEDNESDAY), mon).first(mon))
        assertFalse(Repeat(Repeat.Unit.WEEK, 1, 0, mon).isValid)
        assertFalse(Repeat(Repeat.Unit.DAY, 2, from = null).isValid)
        assertNull(Repeat(Repeat.Unit.WEEK, 1, 0, mon).first(mon))
        assertEquals(Repeat(Repeat.Unit.WEEK, 1, 5, null), Repeat.of(null, null, 5, null))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 31).map { Repeat.ordinal(it) })
    }
}
