package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RemindersTest {
    private val d = LocalDate.of(2026, 11, 9)
    private val now = d.atTime(12, 0)

    private fun r(id: Long, time: LocalTime, lead: Int, date: LocalDate = d, minutes: Int? = null) =
        ReminderInput(id, "T$id", date, time, lead, minutes)

    @Test fun atTimeAndBefore() {
        val alerts = AlertPlanner.todoReminders(
            listOf(r(1, LocalTime.of(18, 0), 0, minutes = 90), r(2, LocalTime.of(18, 0), 15)), now,
        )
        assertEquals(listOf(d.atTime(18, 0), d.atTime(17, 45)), alerts.map { it.at })
        assertEquals("Now · 1 h 30 min", alerts[0].body)
        assertEquals("In 15 min · at 18:00", alerts[1].body)
        assertEquals(1L, alerts[0].taskId)
        assertEquals(Alert.Kind.TODO, alerts[0].kind)
    }

    @Test fun skipsPastNoneAndBeyondHorizon() {
        val alerts = AlertPlanner.todoReminders(
            listOf(
                r(1, LocalTime.of(11, 0), 0),               // already past
                r(2, LocalTime.of(12, 10), 15),             // lead puts it in the past
                r(3, LocalTime.of(18, 0), -1),              // "no reminder"
                r(4, LocalTime.of(9, 0), 0, d.plusDays(3)), // beyond 36 h
                r(5, LocalTime.of(9, 0), 0, d.plusDays(1)), // tomorrow morning: kept
            ),
            now,
        )
        assertEquals(listOf(5L), alerts.map { it.taskId })
    }

    @Test fun keyChangesWhenMoved() {
        val a = AlertPlanner.todoReminders(listOf(r(1, LocalTime.of(18, 0), 0)), now).single()
        val b = AlertPlanner.todoReminders(listOf(r(1, LocalTime.of(19, 0), 0)), now).single()
        assertTrue(a.key != b.key)
    }
}
