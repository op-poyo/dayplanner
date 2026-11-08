package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PacingTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private val sun = mon.plusDays(6)
    private val nov1 = LocalDate.of(2026, 11, 1)
    private val nov30 = LocalDate.of(2026, 11, 30)

    @Test fun weeklyFiveSpreadsEvenly() {
        // Doing exactly the quota each day should finish on Sunday with rest days in between.
        var done = 0
        val quotas = (0 until 7).map { i ->
            val q = Pacing.quotaToday(5, mon, sun, Cadence.DAILY, mon.plusDays(i.toLong()), done)
            done += q
            q
        }
        assertEquals(listOf(1, 1, 1, 0, 1, 1, 0), quotas)
        assertEquals(5, done)
    }

    @Test fun missedDaysAreCaughtUp() {
        // Nothing done Mon–Wed → Thursday asks for the 3 you should have done by then.
        assertEquals(3, Pacing.quotaToday(5, mon, sun, Cadence.DAILY, mon.plusDays(3), 0))
    }

    @Test fun neverAsksForMoreThanRemaining() {
        assertEquals(0, Pacing.quotaToday(5, mon, sun, Cadence.DAILY, sun, 5))
        assertEquals(1, Pacing.quotaToday(5, mon, sun, Cadence.DAILY, sun, 4))
    }

    @Test fun monthlyTwentyOverNovember() {
        assertEquals(30, Pacing.totalDays(nov1, nov30))
        assertEquals(1, Pacing.expectedBy(20, nov1, nov30, nov1))
        assertEquals(20, Pacing.expectedBy(20, nov1, nov30, LocalDate.of(2026, 11, 30)))
        assertEquals(10, Pacing.expectedBy(20, nov1, nov30, LocalDate.of(2026, 11, 15)))
    }

    @Test fun countStatusPercentAndPace() {
        val s = GoalStatus.of(5, mon, sun, mon.plusDays(2), doneUnits = 2,
            stepTotal = 0, stepDone = 0, todayUnits = 1, todayDoneUnits = 0)
        assertEquals(GoalMode.COUNT, s.mode)
        assertEquals(40, s.percent)
        assertEquals(-1, s.paceDelta) // expected 3 by Wednesday
    }

    @Test fun stepsStatus() {
        val s = GoalStatus.of(1, mon, sun, mon, doneUnits = 1,
            stepTotal = 4, stepDone = 1, todayUnits = 0, todayDoneUnits = 0)
        assertEquals(GoalMode.STEPS, s.mode)
        assertEquals(25, s.percent)
        assertNull(s.paceDelta)
    }

    @Test fun singleStatus() {
        val s = GoalStatus.of(1, mon, mon.plusDays(29), mon, doneUnits = 3,
            stepTotal = 0, stepDone = 0, todayUnits = 0, todayDoneUnits = 0)
        assertEquals(GoalMode.SINGLE, s.mode)
        assertTrue(s.complete)
        assertEquals(100, s.percent)
    }

    @Test fun weeklyBucketAsksForTheWholeWeekUpFront() {
        // 5 over Mon–Sun with a weekly breakdown: Monday's to-do is the full 5.
        assertEquals(5, Pacing.quotaToday(5, mon, sun, Cadence.WEEKLY, mon, 0))
        // Done 2 by Thursday → 3 left this week.
        assertEquals(3, Pacing.quotaToday(5, mon, sun, Cadence.WEEKLY, mon.plusDays(3), 2))
    }

    @Test fun threeDayBucketsAlignToStart() {
        // 6 over 6 days from Monday, every 3 days: Mon–Wed then Thu–Sat.
        val end = mon.plusDays(5)
        assertEquals(mon.plusDays(2), Pacing.bucketEnd(Cadence.THREE_DAY, mon, end, mon))
        assertEquals(mon.plusDays(5), Pacing.bucketEnd(Cadence.THREE_DAY, mon, end, mon.plusDays(4)))
        assertEquals(3, Pacing.quotaToday(6, mon, end, Cadence.THREE_DAY, mon, 0))
        assertEquals(3, Pacing.quotaToday(6, mon, end, Cadence.THREE_DAY, mon.plusDays(3), 3))
    }

    @Test fun monthlyBucketClampsToEnd() {
        // 10 over 9 Nov – 20 Dec, monthly: November's share then the rest in December.
        val end = LocalDate.of(2026, 12, 20)
        assertEquals(nov30, Pacing.bucketEnd(Cadence.MONTHLY, mon, end, mon))
        assertEquals(end, Pacing.bucketEnd(Cadence.MONTHLY, mon, end, LocalDate.of(2026, 12, 1)))
        val nov = Pacing.quotaToday(10, mon, end, Cadence.MONTHLY, mon, 0)
        assertEquals(6, nov) // 22 of 42 days → ceil(10·22/42) = 6
    }

    @Test fun nothingOutsideRange() {
        assertEquals(0, Pacing.quotaToday(5, mon, sun, Cadence.DAILY, mon.minusDays(1), 0))
        assertEquals(0, Pacing.quotaToday(5, mon, sun, Cadence.DAILY, sun.plusDays(1), 0))
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
