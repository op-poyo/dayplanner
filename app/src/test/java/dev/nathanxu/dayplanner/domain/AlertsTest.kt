package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlertsTest {
    private val d = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)
    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime, loc: String?) = CalEvent(id, "E$id", s, e, loc, false)

    private val day = listOf(
        ev(1, at(9), at(10), "Roberts Building"),
        ev(2, at(11), at(12), "Gym"),
        ev(3, at(12), at(13), null),
        ev(4, at(14), at(15), "Gym"),
    )

    @Test fun legsIncludeHomeFirstLeg() {
        val legs = DayPlan.legs(day, home = "Flat")
        assertEquals(listOf("Flat" to "Roberts Building", "Roberts Building" to "Gym"), legs.map { it.from to it.destination })
        assertNull(legs.first().fromEnd)
    }

    @Test fun noHomeNoFirstLeg() {
        assertEquals(1, DayPlan.legs(day).size)
    }

    @Test fun homeSameAsFirstPlaceIsNoTrip() {
        assertTrue(DayPlan.legs(listOf(ev(1, at(9), at(10), "flat ")), home = "Flat").isEmpty())
    }

    @Test fun leaveAlertTimes() {
        val alerts = AlertPlanner.leaveAlerts(day, "Flat", { _, _ -> 30 }, bufferMinutes = 10, now = at(7))
        assertEquals(listOf(at(8, 20), at(10, 20)), alerts.map { it.at })
        assertTrue(alerts.first().body.contains("from home"))
        assertEquals("Leave now for E1", alerts.first().title)
    }

    @Test fun pastAndUnknownAreSkipped() {
        // 8:30 now: the 8:20 alert has passed. Unknown travel time for the second leg.
        val alerts = AlertPlanner.leaveAlerts(
            day, "Flat", { from, _ -> if (from == "Flat") 30 else null }, bufferMinutes = 10, now = at(8, 30),
        )
        assertTrue(alerts.isEmpty())
    }

    @Test fun keysAreStable() {
        val a = AlertPlanner.leaveAlerts(day, "Flat", { _, _ -> 30 }, 10, at(7))
        val b = AlertPlanner.leaveAlerts(day, "Flat", { _, _ -> 25 }, 5, at(7, 30))
        assertEquals(a.map { it.key }, b.map { it.key })
    }

    @Test fun timelineShowsHomeLegOnlyAhead() {
        val items = DayPlan.buildTimeline(day, { _, _ -> 30 }, at(7), at(23), home = "Flat")
        val first = items.filterIsInstance<TimelineItem.Travel>().first()
        assertEquals("Flat", first.from)
        assertEquals(120L, first.availableMinutes) // 7:00 → 9:00
        // Once the first event has started, the home leg is irrelevant.
        val later = DayPlan.buildTimeline(day, { _, _ -> 30 }, at(9, 30), at(23), home = "Flat")
        assertTrue(later.filterIsInstance<TimelineItem.Travel>().none { it.from == "Flat" })
    }

    @Test fun morningTimeAndSummary() {
        assertEquals(at(7, 30), AlertPlanner.morningAt(d, LocalTime.of(7, 30), at(6)))
        assertNull(AlertPlanner.morningAt(d, LocalTime.of(7, 30), at(8)))
        val (title, body) = AlertPlanner.summary(day, listOf("LeetCode ×2", "Gym", "A", "B", "C"))
        assertEquals("Today: 4 events · 5 to-dos", title)
        assertEquals("First: 09:00 E1 (Roberts Building)\nTo-do: LeetCode ×2, Gym, A, B +1 more", body)
        assertEquals("Nothing planned. Enjoy it.", AlertPlanner.summary(emptyList(), emptyList()).second)
    }
}
