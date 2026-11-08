package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PacingTest {
    private val mon = LocalDate.of(2026, 11, 9)

    @Test fun weeklyFiveSpreadsEvenly() {
        // Doing exactly the quota each day should finish on Sunday with rest days in between.
        var done = 0
        val quotas = (0 until 7).map { i ->
            val q = Pacing.quotaToday(5, Period.WEEK, mon, mon.plusDays(i.toLong()), done)
            done += q
            q
        }
        assertEquals(listOf(1, 1, 1, 0, 1, 1, 0), quotas)
        assertEquals(5, done)
    }

    @Test fun missedDaysAreCaughtUp() {
        // Nothing done Mon–Wed → Thursday asks for the 3 you should have done by then.
        assertEquals(3, Pacing.quotaToday(5, Period.WEEK, mon, mon.plusDays(3), 0))
    }

    @Test fun neverAsksForMoreThanRemaining() {
        assertEquals(0, Pacing.quotaToday(5, Period.WEEK, mon, mon.plusDays(6), 5))
        assertEquals(1, Pacing.quotaToday(5, Period.WEEK, mon, mon.plusDays(6), 4))
    }

    @Test fun monthlyTwentyOverNovember() {
        val nov1 = LocalDate.of(2026, 11, 1)
        assertEquals(30, Pacing.totalDays(Period.MONTH, nov1))
        assertEquals(1, Pacing.expectedBy(20, Period.MONTH, nov1, nov1))
        assertEquals(20, Pacing.expectedBy(20, Period.MONTH, nov1, LocalDate.of(2026, 11, 30)))
        assertEquals(10, Pacing.expectedBy(20, Period.MONTH, nov1, LocalDate.of(2026, 11, 15)))
    }

    @Test fun countStatusPercentAndPace() {
        val s = GoalStatus.of(5, Period.WEEK, mon, mon.plusDays(2), doneUnits = 2,
            stepTotal = 0, stepDone = 0, todayUnits = 1, todayDoneUnits = 0)
        assertEquals(GoalMode.COUNT, s.mode)
        assertEquals(40, s.percent)
        assertEquals(-1, s.paceDelta) // expected 3 by Wednesday
    }

    @Test fun stepsStatus() {
        val s = GoalStatus.of(1, Period.WEEK, mon, mon, doneUnits = 1,
            stepTotal = 4, stepDone = 1, todayUnits = 0, todayDoneUnits = 0)
        assertEquals(GoalMode.STEPS, s.mode)
        assertEquals(25, s.percent)
        assertNull(s.paceDelta)
    }

    @Test fun singleStatus() {
        val s = GoalStatus.of(1, Period.MONTH, mon, mon, doneUnits = 3,
            stepTotal = 0, stepDone = 0, todayUnits = 0, todayDoneUnits = 0)
        assertEquals(GoalMode.SINGLE, s.mode)
        assertTrue(s.complete)
        assertEquals(100, s.percent)
    }

    @Test fun timeTokens() {
        assertEquals(LocalTime.of(18, 30), QuickAdd.parseTime("18:30"))
        assertEquals(LocalTime.of(7, 5), QuickAdd.parseTime("7.05"))
        assertEquals(LocalTime.of(18, 0), QuickAdd.parseTime("6pm"))
        assertEquals(LocalTime.of(0, 15), QuickAdd.parseTime("12:15am"))
        assertEquals(LocalTime.of(12, 0), QuickAdd.parseTime("12pm"))
        assertNull(QuickAdd.parseTime("4"))
        assertNull(QuickAdd.parseTime("25:00"))
        val t = QuickAdd.parse("Gym 6pm #gym 1h", mon) as TaskDraft
        assertEquals("Gym", t.title)
        assertEquals(LocalTime.of(18, 0), t.time)
        assertEquals(60, t.minutes)
    }
}
