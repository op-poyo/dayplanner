package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuickAddTest {
    // Sunday 8 Nov 2026
    private val today = LocalDate.of(2026, 11, 8)

    private fun task(s: String) = QuickAdd.parse(s, today) as TaskDraft
    private fun goal(s: String) = QuickAdd.parse(s, today) as GoalDraft

    @Test fun plainTitleDefaultsToToday() {
        val t = task("Buy milk")
        assertEquals("Buy milk", t.title)
        assertEquals(today, t.date)
        assertNull(t.tag)
        assertNull(t.minutes)
    }

    @Test fun fullSyntax() {
        val t = task("Problem sheet 4 fri #study 1h30 ^mech")
        assertEquals("Problem sheet 4", t.title)
        assertEquals(LocalDate.of(2026, 11, 13), t.date)
        assertEquals("study", t.tag)
        assertEquals(90, t.minutes)
        assertEquals("mech", t.goalHint)
    }

    @Test fun weekdayIsNextOrSame() {
        assertEquals(today, task("Long run sun").date)
        assertEquals(LocalDate.of(2026, 11, 9), task("Lab mon").date)
    }

    @Test fun tomorrowAndSomeday() {
        assertEquals(today.plusDays(1), task("Gym tmr").date)
        assertNull(task("Read Hull someday").date)
    }

    @Test fun firstWordIsNeverADate() {
        val t = task("Sun cream")
        assertEquals("Sun cream", t.title)
        assertEquals(today, t.date)
    }

    @Test fun minutesFormats() {
        assertEquals(45, task("Zetamac 45m").minutes)
        assertEquals(10, task("Zetamac 10min").minutes)
        assertEquals(60, task("Review 1h").minutes)
    }

    @Test fun anyWordTagIsATag() {
        val t = task("Call mum #family")
        assertEquals("Call mum", t.title)
        assertEquals("family", t.tag)
    }

    @Test fun numericHashStaysInTitle() {
        val t = task("Fix issue #12")
        assertEquals("Fix issue #12", t.title)
        assertNull(t.tag)
    }

    @Test fun goalWithTargetAndRepeat() {
        val g = goal("LeetCode mediums x5 @week !r")
        assertEquals("LeetCode mediums", g.title)
        assertEquals(Period.WEEK, g.period)
        assertEquals(5, g.target)
        assertTrue(g.recurring)
    }

    @Test fun monthGoalDefaults() {
        val g = goal("Finish prototype @month")
        assertEquals(Period.MONTH, g.period)
        assertEquals(1, g.target)
    }

    @Test fun emptyTitleIsNull() {
        assertNull(QuickAdd.parse("   ", today))
        assertNull(QuickAdd.parse("#study 1h", today))
    }
}
