package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TagsAndClashesTest {
    private val mon = LocalDate.of(2026, 11, 9)

    @Test fun resolvesByNameKeyOrAlias() {
        val tags = Tags.DEFAULTS + Tag("FAMILY", "Family", Tags.PALETTE[5])
        assertEquals("STUDY", Tags.resolve("Study", tags)?.key)
        assertEquals("EXERCISE", Tags.resolve("gym", tags)?.key)
        assertEquals("QUANT", Tags.resolve("lc", tags)?.key)
        assertEquals("FAMILY", Tags.resolve("family", tags)?.key)
        assertNull(Tags.resolve("piano", tags))
    }

    @Test fun nameRulesAndKeys() {
        assertTrue(Tags.isValidName("piano"))
        assertTrue(Tags.isValidName("comp0205"))
        assertFalse(Tags.isValidName("12"))
        assertFalse(Tags.isValidName("way-too-long-tag-name-here"))
        assertEquals("COMP_0205", Tags.keyFor("comp 0205"))
    }

    @Test fun nextColourSkipsUsedOnes() {
        val used = Tags.PALETTE.take(3).mapIndexed { i, c -> Tag("T$i", "t$i", c) }
        assertEquals(Tags.PALETTE[3], Tags.nextColor(used))
    }

    @Test fun findFallsBackToOther() {
        assertEquals(Tags.OTHER, Tags.find("DELETED", Tags.DEFAULTS).key)
    }

    @Test fun clashWithEventAndAdjacentIsFine() {
        val mask = Days.bit(DayOfWeek.MONDAY) or Days.bit(DayOfWeek.WEDNESDAY)
        val busy = listOf(
            Busy(mon, 9 * 60, 11 * 60, "MATH0005"),           // overlaps 10:00–11:00
            Busy(mon.plusDays(2), 11 * 60, 12 * 60, "Lab"),   // starts when routine ends: OK
            Busy(mon.plusDays(1), 10 * 60, 11 * 60, "Tue thing"), // wrong day
        )
        val clashes = Clashes.find(mask, LocalTime.of(10, 0), 60, mon, 7, busy)
        assertEquals(listOf("MATH0005"), clashes.map { it.busy.label })
    }

    @Test fun weeklyRepeatsCollapseInSummary() {
        val mask = Days.bit(DayOfWeek.MONDAY)
        val busy = (0 until 4).map { Busy(mon.plusWeeks(it.toLong()), 9 * 60, 11 * 60, "MATH0005") }
        val clashes = Clashes.find(mask, LocalTime.of(10, 0), 30, mon, 28, busy)
        assertEquals(listOf("Mon 09:00–11:00 MATH0005 (×4)"), Clashes.summarise(clashes))
    }

    @Test fun routineAgainstRoutine() {
        val other = Clashes.routineBusy(Days.WEEKDAYS, LocalTime.of(18, 0), 60, "Gym", mon, 7)
        val clashes = Clashes.find(Days.bit(DayOfWeek.FRIDAY), LocalTime.of(18, 30), 30, mon, 7, other)
        assertEquals(1, clashes.size)
        assertEquals(DayOfWeek.FRIDAY, clashes.single().date.dayOfWeek)
    }

    @Test fun summaryLimits() {
        val busy = (0 until 5).map { Busy(mon.plusDays(it.toLong()), 600, 660, "E$it") }
        val clashes = Clashes.find(Days.ALL, LocalTime.of(10, 0), 60, mon, 7, busy)
        val s = Clashes.summarise(clashes, limit = 3)
        assertEquals(4, s.size)
        assertEquals("+2 more", s.last())
    }
}
