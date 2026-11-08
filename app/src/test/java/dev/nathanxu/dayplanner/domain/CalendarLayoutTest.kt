package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class CalendarLayoutTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private fun at(d: LocalDate, h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)
    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        CalEvent(id, "E$id", s, e, null, allDay)

    @Test fun overnightEventSplitsAcrossDays() {
        val e = ev(1, at(mon, 22), at(mon.plusDays(1), 2))
        val byDay = CalendarLayout.byDay(listOf(e), mon, mon.plusDays(7))
        assertEquals(22 * 60, byDay.getValue(mon).single().startMin)
        assertEquals(24 * 60, byDay.getValue(mon).single().endMin)
        assertEquals(0, byDay.getValue(mon.plusDays(1)).single().startMin)
        assertEquals(120, byDay.getValue(mon.plusDays(1)).single().endMin)
    }

    @Test fun eventEndingAtMidnightStaysOnOneDay() {
        val e = ev(1, at(mon, 20), mon.plusDays(1).atStartOfDay())
        val byDay = CalendarLayout.byDay(listOf(e), mon, mon.plusDays(7))
        assertEquals(setOf(mon), byDay.keys)
        assertEquals(24 * 60, byDay.getValue(mon).single().endMin)
    }

    @Test fun allDayUsesExclusiveEnd() {
        // A 2-day all-day event: Mon and Tue, ends Wed 00:00.
        val e = ev(1, mon.atStartOfDay(), mon.plusDays(2).atStartOfDay(), allDay = true)
        val byDay = CalendarLayout.byDay(listOf(e), mon, mon.plusDays(7))
        assertEquals(setOf(mon, mon.plusDays(1)), byDay.keys)
    }

    @Test fun rangeClipsDays() {
        val e = ev(1, at(mon.minusDays(1), 10), at(mon.minusDays(1), 11))
        assertTrue(CalendarLayout.byDay(listOf(e), mon, mon.plusDays(1)).isEmpty())
    }

    @Test fun zeroLengthGetsMinimumBlock() {
        val e = ev(1, at(mon, 9), at(mon, 9))
        val seg = CalendarLayout.byDay(listOf(e), mon, mon.plusDays(1)).getValue(mon).single()
        assertEquals(9 * 60 + CalendarLayout.MIN_BLOCK_MINUTES, seg.endMin)
    }

    @Test fun allDaySortsFirst() {
        val timed = ev(1, at(mon, 8), at(mon, 9))
        val allDay = ev(2, mon.atStartOfDay(), mon.plusDays(1).atStartOfDay(), allDay = true)
        val segs = CalendarLayout.byDay(listOf(timed, allDay), mon, mon.plusDays(1)).getValue(mon)
        assertEquals(listOf(2L, 1L), segs.map { it.event.id })
    }

    @Test fun overlapsGetLanesAndSharedWidth() {
        val segs = CalendarLayout.byDay(
            listOf(
                ev(1, at(mon, 9), at(mon, 11)),
                ev(2, at(mon, 10), at(mon, 12)),
                ev(3, at(mon, 11), at(mon, 13)), // reuses lane 0, still in the same cluster
                ev(4, at(mon, 14), at(mon, 15)), // separate cluster
            ),
            mon, mon.plusDays(1),
        ).getValue(mon)
        val placed = CalendarLayout.lanes(segs).associateBy { it.segment.event.id }
        assertEquals(0, placed.getValue(1).lane)
        assertEquals(1, placed.getValue(2).lane)
        assertEquals(0, placed.getValue(3).lane)
        assertEquals(2, placed.getValue(1).lanes)
        assertEquals(2, placed.getValue(3).lanes)
        assertEquals(1, placed.getValue(4).lanes)
    }

    @Test fun monthGridStartsMondayAndCoversMonth() {
        val grid = CalendarLayout.monthGrid(YearMonth.of(2026, 11))
        assertEquals(42, grid.size)
        assertEquals(DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertTrue(grid.contains(LocalDate.of(2026, 11, 1)))
        assertTrue(grid.contains(LocalDate.of(2026, 11, 30)))
    }

    @Test fun gridDaysAndStepping() {
        val wed = LocalDate.of(2026, 11, 11)
        assertEquals(mon, CalendarLayout.gridDays(CalendarMode.WEEK, wed).first())
        assertEquals(wed.plusDays(2), CalendarLayout.gridDays(CalendarMode.THREE_DAY, wed).last())
        assertEquals(wed.plusDays(3), CalendarLayout.step(CalendarMode.THREE_DAY, wed, true))
        assertEquals(LocalDate.of(2026, 12, 1), CalendarLayout.step(CalendarMode.MONTH, wed, true))
    }
}
