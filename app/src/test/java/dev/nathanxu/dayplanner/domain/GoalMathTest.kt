package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalMathTest {
    private val mon = LocalDate.of(2026, 11, 16)
    private val ongoing = Pacing.ONGOING_END
    private fun weekly(target: Int, start: LocalDate = LocalDate.of(2026, 11, 1)) = GoalSpec(GoalKind.COUNT, target, Cadence.WEEKLY, start, ongoing)
    private fun done(d: LocalDate, units: Int = 1) = Linked(true, d, units)

    @Test fun weeklyCountsOnlyThisWeekAndKeepsHistory() {
        val linked = listOf(
            done(mon.minusDays(7)), done(mon.minusDays(6)), done(mon.minusDays(5)), // last week: 3
            done(mon.minusDays(1)),                                                // Sun of last week → 4
            done(mon), done(mon.plusDays(2)),                                      // this week: 2
            Linked(false, mon.plusDays(3)),                                        // open: doesn't count
        )
        val s = GoalMath.state(weekly(3), linked, mon.plusDays(2)) // Wednesday
        assertEquals(2, s.done)
        assertEquals("this week", s.label)
        assertEquals(5, s.daysLeft)
        assertEquals(0, s.behindBy)
        assertEquals(listOf(4, 2), s.history.takeLast(2))
        assertFalse(s.needsAttention)
    }

    @Test fun behindAndAttention() {
        // 3 a week, nothing done by Saturday: behind, and 3 left with 2 days to go.
        val s = GoalMath.state(weekly(3), emptyList(), mon.plusDays(5))
        assertEquals(2, s.behindBy)
        assertTrue(s.needsAttention)
        // Monday with nothing done: on pace, not flagged.
        assertFalse(GoalMath.state(weekly(3), emptyList(), mon).needsAttention)
    }

    @Test fun monthlyQuietUntilBehind() {
        val g = GoalSpec(GoalKind.COUNT, 8, Cadence.MONTHLY, LocalDate.of(2026, 11, 1), ongoing)
        val early = GoalMath.state(g, listOf(done(LocalDate.of(2026, 11, 2))), LocalDate.of(2026, 11, 3))
        assertFalse(early.needsAttention)
        assertEquals("this month", early.label)
        val late = GoalMath.state(g, listOf(done(LocalDate.of(2026, 11, 2))), LocalDate.of(2026, 11, 20))
        assertTrue(late.needsAttention)
        assertEquals(4, late.behindBy) // 8 × 19/30 = 5 expected, 1 done
    }

    @Test fun totalByDate() {
        val g = GoalSpec(GoalKind.COUNT, 15, null, mon, mon.plusDays(14))
        val s = GoalMath.state(g, (0..4).map { done(mon.plusDays(it.toLong())) }, mon.plusDays(5))
        assertEquals(5, s.done)
        assertEquals("by 30 Nov", s.label)
        assertEquals(10, s.daysLeft)
        assertEquals(emptyList<Int>(), s.history)
        assertEquals(33, s.percent)
    }

    @Test fun unitsAndStartAndEnd() {
        val g = weekly(3, start = mon.plusDays(2))
        val before = GoalMath.state(g, emptyList(), mon)
        assertNull(before.window)
        assertEquals("starts Wed 18 Nov", before.label)
        // First week is clipped to the start day.
        val s = GoalMath.state(g, listOf(done(mon.plusDays(2), units = 2)), mon.plusDays(3))
        assertEquals(Window(mon.plusDays(2), mon.plusDays(6)), s.window)
        assertEquals(2, s.done)
    }

    @Test fun threeDayAndDailyWindows() {
        val start = LocalDate.of(2026, 11, 1)
        assertEquals(Window(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 18)), GoalMath.window(Cadence.THREE_DAY, start, ongoing, mon))
        assertEquals(Window(mon, mon), GoalMath.window(Cadence.DAILY, start, ongoing, mon))
        assertEquals(Window(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)), GoalMath.window(Cadence.MONTHLY, start, ongoing, mon))
    }

    @Test fun checklist() {
        val g = GoalSpec(GoalKind.CHECKLIST, 1, null, mon, mon.plusDays(9))
        val steps = listOf(Linked(true, mon), Linked(false, null), Linked(false, null), Linked(false, null), Linked(true, mon, repeating = true))
        val s = GoalMath.state(g, steps, mon.plusDays(5))
        assertEquals(1, s.done)
        assertEquals(4, s.target) // the repeating one isn't a step
        assertEquals(1, s.behindBy) // 4 × 5/10 = 2 expected
        assertEquals("by 25 Nov", s.label)
        val none = GoalMath.state(GoalSpec(GoalKind.CHECKLIST, 1, null, mon, ongoing), emptyList(), mon)
        assertEquals(0, none.target)
        assertFalse(none.needsAttention)
    }

    @Test fun suggestions() {
        val days = (0L..6L).map { mon.plusDays(it) }
        assertEquals(listOf(mon, mon.plusDays(2), mon.plusDays(4)), GoalMath.suggestionDays(3, days))
        assertEquals(days.take(2), GoalMath.suggestionDays(5, days.take(2)))
        assertEquals(emptyList<LocalDate>(), GoalMath.suggestionDays(0, days))
    }

    @Test fun describe() {
        assertEquals("3 a week", GoalMath.describe(weekly(3)))
        assertEquals("15 by 30 Nov", GoalMath.describe(GoalSpec(GoalKind.COUNT, 15, null, mon, mon.plusDays(14))))
        assertEquals("Checklist", GoalMath.describe(GoalSpec(GoalKind.CHECKLIST, 1, null, mon, ongoing)))
        assertEquals("2 a month until 31 Dec", GoalMath.describe(GoalSpec(GoalKind.COUNT, 2, Cadence.MONTHLY, mon, LocalDate.of(2026, 12, 31))))
    }
}
