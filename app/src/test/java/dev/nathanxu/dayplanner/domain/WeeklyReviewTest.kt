package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeeklyReviewTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private val sun = mon.plusDays(6)
    private fun t(id: Long, date: LocalDate?, done: Boolean = false, doneOn: LocalDate? = if (done) date else null, tag: String = "study",
                  minutes: Int? = null, routineId: Long? = null, deadline: LocalDate? = null, isLog: Boolean = false) =
        ReviewTask(id, "T$id", date, done, doneOn, tag, minutes, routineId, deadline, isLog)

    @Test fun countsDoneByDayAndTag() {
        val s = WeeklyReview.summarise(
            listOf(
                t(1, mon, done = true, minutes = 60),
                t(2, mon.plusDays(2), done = true, tag = "gym", minutes = 90),
                t(3, null, done = true, doneOn = mon.plusDays(2), minutes = 30), // undated, ticked Wed
                t(4, mon.minusDays(1), done = true),                             // last week
                t(5, mon, done = true, isLog = true),                            // +1 log: not a to-do
            ),
            emptyList(), today = sun,
        )
        assertEquals(3, s.done)
        assertEquals(180, s.doneMinutes)
        assertEquals(listOf(1, 0, 2, 0, 0, 0, 0), s.perDay)
        assertEquals(listOf(TagTotal("study", 2, 90), TagTotal("gym", 1, 90)), s.byTag)
        assertEquals(DayOfWeek.WEDNESDAY, s.busiestDay)
    }

    @Test fun routinesCountOnlyDaysSoFar() {
        val gym = RoutineInput(1, "Gym", Days.bit(DayOfWeek.MONDAY) or Days.bit(DayOfWeek.WEDNESDAY) or Days.bit(DayOfWeek.FRIDAY), true)
        val off = RoutineInput(2, "Off", Days.ALL, false)
        val s = WeeklyReview.summarise(
            listOf(t(10, mon, done = true, routineId = 1), t(11, mon.plusDays(2), routineId = 1)),
            listOf(gym, off), today = mon.plusDays(3), // Thursday
        )
        assertEquals(listOf(RoutineScore(1, "Gym", 1, 2)), s.routines)
    }

    @Test fun slippedAndNextWeek() {
        val s = WeeklyReview.summarise(
            listOf(
                t(1, mon.plusDays(1)),                                // Tue, open, today is Thu → slipped
                t(2, mon.plusDays(3)),                                // today → not slipped
                t(3, null, deadline = mon.plusDays(2)),               // deadline passed → slipped
                t(4, mon.minusDays(3)),                               // last week: old, not this review
                t(5, mon.plusDays(1), routineId = 9),                 // missed routine: shown under routines
                t(6, sun.plusDays(2)),                                // next week
                t(7, null, deadline = sun.plusDays(5)),               // due next week
                t(8, null, deadline = sun.plusDays(1)),
            ),
            emptyList(), today = mon.plusDays(3),
        )
        assertEquals(listOf(1L, 3L), s.slipped.map { it.id }.sorted())
        assertEquals(1, s.nextWeekCount)
        assertEquals(listOf(8L, 7L), s.nextWeekDeadlines.map { it.id })
    }

    @Test fun weekStartsMonday() {
        assertEquals(mon, WeeklyReview.weekOf(sun))
        assertEquals(mon, WeeklyReview.weekOf(mon))
        assertEquals(sun, WeeklyReview.nextReviewDate(mon, DayOfWeek.SUNDAY))
        assertEquals(sun, WeeklyReview.nextReviewDate(sun, DayOfWeek.SUNDAY))
    }
}
