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
