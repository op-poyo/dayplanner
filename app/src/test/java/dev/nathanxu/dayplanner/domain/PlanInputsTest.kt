package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlanInputsTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private val days = (0L..2L).map { mon.plusDays(it) }
    private val now = mon.atTime(12, 0)
    private fun c(id: Long, date: LocalDate? = mon, time: LocalTime? = null, minutes: Int? = 60, planned: Boolean = false,
                  priority: Priority = Priority.NORMAL, deadline: LocalDate? = null, done: Boolean = false) =
        PlanCandidate(id, "T$id", date, time, minutes, priority, deadline, null, planned, done)

    @Test fun eventsBecomeBusyCutAtMidnightWithTravel() {
        val ev = listOf(
            CalEvent(1, "Night shift", mon.atTime(22, 0), mon.plusDays(1).atTime(2, 0), null, false),
            CalEvent(2, "Holiday", mon.atStartOfDay(), mon.plusDays(1).atStartOfDay(), null, true),
            CalEvent(3, "Lecture", mon.atTime(10, 0), mon.atTime(11, 0), "UCL", false),
        )
        val b = PlanInputs.busyFromEvents(ev, days, mapOf(3L to 30))
        assertEquals(
            listOf(Busy(mon, 22 * 60, 24 * 60, "Night shift"), Busy(mon.plusDays(1), 0, 120, "Night shift"), Busy(mon, 9 * 60 + 30, 11 * 60, "Lecture")),
            b,
        )
    }

    @Test fun slippedOnlyWhenPlannedPastAndOpen() {
        assertTrue(PlanInputs.slipped(c(1, time = LocalTime.of(10, 0), planned = true), now))
        assertFalse(PlanInputs.slipped(c(1, time = LocalTime.of(11, 30), planned = true), now)) // still in its hour
        assertFalse(PlanInputs.slipped(c(1, time = LocalTime.of(10, 0), planned = false), now))
        assertFalse(PlanInputs.slipped(c(1, time = LocalTime.of(10, 0), planned = true, done = true), now))
    }

    @Test fun selectsWhatToPlan() {
        val (items, busy) = PlanInputs.select(
            listOf(
                c(1),                                            // today, no time → plan
                c(2, date = mon.minusDays(2)),                   // overdue → plan
                c(3, date = mon.plusDays(5)),                    // beyond range → skip
                c(4, date = null),                               // someday → skip
                c(5, date = null, deadline = mon.plusDays(3)),   // undated with deadline → plan
                c(6, date = null, priority = Priority.HIGH),     // undated high → plan
                c(7, time = LocalTime.of(14, 0), minutes = 90),  // timed → busy
                c(8, time = LocalTime.of(9, 0), planned = true), // slipped → re-plan on any day
                c(9, done = true),
            ),
            days, now, includeSomeday = false,
        )
        assertEquals(listOf(1L, 2L, 5L, 6L, 8L), items.map { it.id })
        assertEquals(null, items.first { it.id == 8L }.day)
        assertEquals(listOf(Busy(mon, 14 * 60, 15 * 60 + 30, "T7")), busy)
        val gen = PlanInputs.select(
            listOf(c(20, date = mon.minusDays(1), time = LocalTime.of(9, 0), planned = true).copy(generated = true)), days, now, false,
        ).first.single()
        assertEquals(mon.minusDays(1), gen.day) // the scheduler pulls it to today
        val withSomeday = PlanInputs.select(listOf(c(4, date = null)), days, now, includeSomeday = true).first
        assertEquals(listOf(4L), withSomeday.map { it.id })
    }
}

class LabelsTest {
    private val mon = LocalDate.of(2026, 11, 9)
    @Test fun deadlineLabels() {
        assertEquals("due today" to true, Labels.deadline(mon, mon))
        assertEquals("due tomorrow" to false, Labels.deadline(mon.plusDays(1), mon))
        assertEquals("due Fri" to false, Labels.deadline(mon.plusDays(4), mon))
        assertEquals("due 20 Nov" to false, Labels.deadline(mon.plusDays(11), mon))
        assertEquals("past due · 6 Nov" to true, Labels.deadline(mon.minusDays(3), mon))
    }
}
