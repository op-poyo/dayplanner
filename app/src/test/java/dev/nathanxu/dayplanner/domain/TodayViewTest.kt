package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayViewTest {
    private val d = LocalDate.of(2026, 11, 9)
    private fun ev(id: Long, h: Int, h2: Int, title: String = "E$id") = CalEvent(id, title, d.atTime(h, 0), d.atTime(h2, 0), "UCL", false)
    private data class T(val name: String, val at: LocalTime, val min: Int?)

    @Test fun mergesInTimeOrderAndHidesDuplicates() {
        val lecture = ev(1, 10, 11, "Lecture")
        val gymEvent = ev(2, 18, 19, "Gym")
        val items = listOf(
            TimelineItem.Event(lecture),
            TimelineItem.Free(d.atTime(11, 0), d.atTime(18, 0)),
            TimelineItem.Travel("UCL", "Gym", 15, 60),
            TimelineItem.Event(gymEvent),
        )
        val todos = listOf(T("Essay", LocalTime.of(14, 0), 60), T("Gym", LocalTime.of(18, 0), 60), T("Call", LocalTime.of(9, 0), null))
        val rows = TodayView.merge(items, todos, { it.at }, { it.min }, d, hiddenEventIds = setOf(2L))
        val names = rows.map {
            when (it) {
                is DayRow.Cal -> when (val x = it.item) { is TimelineItem.Event -> x.event.title; is TimelineItem.Free -> "free"; is TimelineItem.Travel -> "travel" }
                is DayRow.Todo<*> -> (it.todo as T).name
            }
        }
        // Gym event hidden (its to-do is there), with its travel row; the free gap the essay sits in is gone.
        assertEquals(listOf("Call", "Lecture", "Essay", "Gym"), names)
    }

    @Test fun travelStaysBeforeItsEvent() {
        val items = listOf(TimelineItem.Travel("Home", "UCL", 30, 60), TimelineItem.Event(ev(1, 10, 11)))
        val rows = TodayView.merge(items, listOf(T("x", LocalTime.of(10, 0), null)), { it.at }, { it.min }, d, emptySet())
        assertEquals(listOf("Travel", "Event", "Todo"), rows.map { if (it is DayRow.Cal) it.item.javaClass.simpleName else "Todo" })
    }

    @Test fun nextUpPrefersWhatsOnNow() {
        val now = d.atTime(10, 30)
        val events = listOf(ev(1, 10, 11, "Lecture"), ev(2, 12, 13, "Lab"))
        assertEquals("Lecture", TodayView.nextUp(events, emptyList(), now)?.title)
        val n = TodayView.nextUp(events, listOf(Triple("Essay", d.atTime(11, 15), 30)), d.atTime(11, 5))
        assertEquals("Essay", n?.title)
        assertEquals(true, n?.isTodo)
        assertNull(TodayView.nextUp(events, emptyList(), d.atTime(14, 0)))
    }

    @Test fun laterLine() {
        val s = TodayView.laterSummary(listOf(d.plusDays(1), d.plusDays(1), d.plusDays(3), d.plusDays(20), null), d)
        assertEquals("2 tomorrow · 1 this week · 1 later · 1 someday", s)
        assertEquals("", TodayView.laterSummary(emptyList(), d))
    }
}
