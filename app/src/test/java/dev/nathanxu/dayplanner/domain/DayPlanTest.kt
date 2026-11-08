package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class DayPlanTest {
    private val d = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)
    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime, loc: String?, allDay: Boolean = false) =
        CalEvent(id, "E$id", s, e, loc, allDay)

    @Test fun travelLegsOnlyBetweenDifferentKnownPlaces() {
        val events = listOf(
            ev(1, at(9), at(10), "Roberts Building"),
            ev(2, at(10), at(11), "roberts building "), // same place
            ev(3, at(12), at(13), null),                // unknown
            ev(4, at(14), at(15), "Gym"),
            ev(5, at(16), at(17), "Home"),
        )
        assertEquals(listOf("Gym" to "Home"), DayPlan.travelLegs(events))
    }

    @Test fun timelineFlagsTightTravelAndFindsFreeSlots() {
        val events = listOf(
            ev(1, at(9), at(10), "UCL"),
            ev(2, at(10, 15), at(11), "Canary Wharf"),
            ev(0, at(0), at(0), null, allDay = true),
        )
        val items = DayPlan.buildTimeline(
            events, { _, _ -> 40 }, windowStart = at(7), windowEnd = at(23),
        )

        assertTrue(items.first() is TimelineItem.Event && (items.first() as TimelineItem.Event).event.allDay)

        val travel = items.filterIsInstance<TimelineItem.Travel>().single()
        assertEquals(15L, travel.availableMinutes)
        assertTrue(travel.tight)

        val free = items.filterIsInstance<TimelineItem.Free>()
        assertEquals(at(7), free[0].start)   // morning before first event
        assertEquals(at(9), free[0].end)
        assertEquals(at(11), free.last().start) // after last event
        assertEquals(at(23), free.last().end)
    }

    @Test fun freeSlotEndsWhenYouMustLeave() {
        val events = listOf(
            ev(1, at(9), at(10), "A"),
            ev(2, at(14), at(15), "B"),
        )
        val items = DayPlan.buildTimeline(events, { _, _ -> 30 }, at(7), at(23))
        val mid = items.filterIsInstance<TimelineItem.Free>()[1]
        assertEquals(at(10), mid.start)
        assertEquals(at(13, 30), mid.end)
        assertFalse(items.filterIsInstance<TimelineItem.Travel>().single().tight)
    }

    @Test fun pastIsNotFree() {
        val events = listOf(ev(1, at(9), at(10), null))
        val items = DayPlan.buildTimeline(events, { _, _ -> null }, windowStart = at(12), windowEnd = at(23))
        val free = items.filterIsInstance<TimelineItem.Free>().single()
        assertEquals(at(12), free.start)
    }

    @Test fun shortGapsAreSkipped() {
        val events = listOf(ev(1, at(9), at(10), null), ev(2, at(10, 20), at(11), null))
        val items = DayPlan.buildTimeline(events, { _, _ -> null }, at(9), at(11))
        assertTrue(items.none { it is TimelineItem.Free })
    }

    @Test fun periodsAndDays() {
        assertEquals(LocalDate.of(2026, 11, 2), Period.WEEK.startOf(LocalDate.of(2026, 11, 8)))
        assertEquals(LocalDate.of(2026, 11, 1), Period.MONTH.startOf(LocalDate.of(2026, 11, 30)))
        assertTrue(Period.WEEK.contains(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8)))
        assertFalse(Period.WEEK.contains(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)))
        assertTrue(Days.has(Days.WEEKDAYS, DayOfWeek.FRIDAY))
        assertFalse(Days.has(Days.WEEKDAYS, DayOfWeek.SATURDAY))
        assertEquals("Tue Thu", Days.describe(Days.bit(DayOfWeek.TUESDAY) or Days.bit(DayOfWeek.THURSDAY)))
    }
}
