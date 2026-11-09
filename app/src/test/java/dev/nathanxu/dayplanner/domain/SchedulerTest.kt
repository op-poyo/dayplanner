package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SchedulerTest {
    private val mon = LocalDate.of(2026, 11, 9)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)
    private val early = mon.atTime(6, 0)
    private val rules = PlanRules(workStart = LocalTime.of(9, 0), workEnd = LocalTime.of(17, 0), breakMinutes = 10)
    private fun m(h: Int, min: Int = 0) = h * 60 + min
    private fun item(id: Long, minutes: Int?, vararg mods: (PlanItem) -> PlanItem): PlanItem =
        mods.fold(PlanItem(id, "T$id", minutes)) { acc, f -> f(acc) }

    @Test fun placesAroundBusyWithBreaks() {
        val busy = listOf(Busy(mon, m(9), m(11), "Lecture"))
        val p = Scheduler.plan(listOf(item(1, 60)), listOf(mon), busy, rules = rules, now = early)
        val b = p.blocks.single()
        assertEquals(m(11, 10), b.startMin) // 10-min break after the lecture
        assertEquals(m(12, 10), b.endMin)
    }

    @Test fun skipsGapTooSmallIncludingBreaks() {
        // 11:00–11:50 gap is 30 min after breaks: too small for 45.
        val busy = listOf(Busy(mon, m(9), m(11), "A"), Busy(mon, m(11, 50), m(13), "B"))
        val p = Scheduler.plan(listOf(item(1, 45, { it.copy(splittable = false) })), listOf(mon), busy, rules = rules, now = early)
        assertEquals(m(13, 10), p.blocks.single().startMin)
    }

    @Test fun deadlineFirstThenPriority() {
        val items = listOf(
            item(1, 120, { it.copy(priority = Priority.HIGH) }),
            item(2, 120, { it.copy(deadline = mon) }),
            item(3, 60, { it.copy(priority = Priority.LOW) }),
            item(4, 60),
        )
        val p = Scheduler.plan(items, listOf(mon), emptyList(), rules = rules, now = early)
        assertEquals(listOf(2L, 1L, 4L, 3L), p.blocks.map { it.itemId })
        assertEquals(m(9), p.blocks[0].startMin)
    }

    @Test fun startsNoEarlierThanNowRounded() {
        val p = Scheduler.plan(listOf(item(1, 30)), listOf(mon), emptyList(), rules = rules, now = mon.atTime(13, 2))
        assertEquals(m(13, 5), p.blocks.single().startMin)
    }

    @Test fun fixedDayStaysOnItsDayAndOverdueMeansToday() {
        val items = listOf(item(1, 30, { it.copy(day = wed) }), item(2, 30, { it.copy(day = mon.minusDays(3)) }))
        val p = Scheduler.plan(items, listOf(mon, tue, wed), emptyList(), rules = rules, now = early)
        assertEquals(wed, p.blocks.first { it.itemId == 1L }.date)
        assertEquals(mon, p.blocks.first { it.itemId == 2L }.date)
    }

    @Test fun doOnDayAfterDeadlineMovesToDeadline() {
        val p = Scheduler.plan(listOf(item(1, 30, { it.copy(day = wed, deadline = tue) })), listOf(mon, tue, wed), emptyList(), rules = rules, now = early)
        assertEquals(tue, p.blocks.single().date)
    }

    @Test fun fullDayIsUnplacedWithReason() {
        val busy = listOf(Busy(mon, m(8), m(18), "Lab"))
        val p = Scheduler.plan(listOf(item(1, 30, { it.copy(day = mon) })), listOf(mon), busy, rules = rules, now = early)
        assertTrue(p.blocks.isEmpty())
        assertTrue(p.unplaced.single().reason, p.unplaced.single().reason.startsWith("Not enough free time on today"))
    }

    @Test fun deadlineLimitsDays() {
        val busy = listOf(Busy(mon, m(8), m(18), "Full"))
        val items = listOf(item(1, 60, { it.copy(deadline = mon) }), item(2, 60, { it.copy(deadline = tue) }))
        val p = Scheduler.plan(items, listOf(mon, tue, wed), busy, rules = rules, now = early)
        assertEquals(listOf(2L), p.blocks.map { it.itemId })
        assertEquals(tue, p.blocks.single().date)
        assertEquals(1L, p.unplaced.single().itemId)
    }

    @Test fun longTaskSplitIntoSittings() {
        val p = Scheduler.plan(listOf(item(1, 300)), listOf(mon), emptyList(), rules = rules, now = early)
        assertEquals(listOf(120, 120, 60), p.blocks.map { it.minutes })
        assertEquals(listOf(1, 2, 3), p.blocks.map { it.part })
        assertTrue(p.blocks.all { it.parts == 3 })
        // Breaks between sittings.
        assertEquals(m(11, 10), p.blocks[1].startMin)
    }

    @Test fun splitAcrossGapsNoScraps() {
        // Gaps: 09:00–10:20 (80) and 12:10–17:00. 100 min → 70 + 30? No: no piece under 30.
        val busy = listOf(Busy(mon, m(10, 30), m(12), "X"))
        val p = Scheduler.plan(listOf(item(1, 100)), listOf(mon), busy, rules = rules, now = early)
        assertEquals(100, p.blocks.sumOf { it.minutes })
        assertTrue(p.blocks.all { it.minutes >= 30 })
    }

    @Test fun unsplittableNeedsOneGap() {
        val busy = (9 until 17 step 2).map { Busy(mon, m(it + 1), m(it + 2), "B$it") } // 1-h gaps
        val p = Scheduler.plan(listOf(item(1, 90, { it.copy(splittable = false) })), listOf(mon), busy, rules = rules, now = early)
        assertTrue(p.blocks.isEmpty())
        assertTrue(p.unplaced.single().reason.contains("No single gap"))
    }

    @Test fun protectedTimeKeptForItsTag() {
        val prot = listOf(ProtectedBlock(Days.ALL, LocalTime.of(9, 0), LocalTime.of(11, 0), "quant", "Quant prep"))
        val items = listOf(item(1, 60), item(2, 60, { it.copy(tag = "quant") }))
        val p = Scheduler.plan(items, listOf(mon), emptyList(), prot, rules, early)
        val other = p.blocks.first { it.itemId == 1L }
        val quant = p.blocks.first { it.itemId == 2L }
        assertEquals(m(11), other.startMin) // not in protected time
        assertEquals(m(9), quant.startMin)
        assertTrue(quant.protected)
        // The unused protected time is reported (minus the break before the 11:00 block).
        assertEquals(listOf(m(10, 10) to m(10, 50)), p.reserved.map { it.startMin to it.endMin })
        assertEquals("Quant prep", p.reserved.single().label)
    }

    @Test fun travelKeptFreeBeforeBlock() {
        val p = Scheduler.plan(listOf(item(1, 60, { it.copy(travelBefore = 25) })), listOf(mon), emptyList(), rules = rules, now = early)
        assertEquals(m(9, 25), p.blocks.single().startMin)
    }

    @Test fun defaultDurationAndDeterminism() {
        val items = (1L..5L).map { item(it, null) }
        val a = Scheduler.plan(items, listOf(mon), emptyList(), rules = rules, now = early)
        val b = Scheduler.plan(items.reversed(), listOf(mon), emptyList(), rules = rules, now = early)
        assertEquals(a, b)
        assertTrue(a.blocks.all { it.minutes == 30 })
    }

    @Test fun pastDaysAndAfterHoursIgnored() {
        val p = Scheduler.plan(listOf(item(1, 30)), listOf(mon), emptyList(), rules = rules, now = mon.atTime(16, 50))
        assertTrue(p.blocks.isEmpty())
        val q = Scheduler.plan(listOf(item(1, 30)), listOf(mon, tue), emptyList(), rules = rules, now = mon.atTime(16, 50))
        assertEquals(tue, q.blocks.single().date)
    }

    @Test fun durationsShort() {
        assertEquals("45 min", Durations.short(45))
        assertEquals("2 h", Durations.short(120))
        assertEquals("1 h 30", Durations.short(90))
    }
}

class QuickAddV17Test {
    private val mon = java.time.LocalDate.of(2026, 11, 9)
    private fun task(s: String) = QuickAdd.parse(s, mon) as TaskDraft

    @Test fun priorityTokens() {
        assertEquals(Priority.HIGH, task("Essay !high").priority)
        assertEquals(Priority.HIGH, task("Essay !!").priority)
        assertEquals(Priority.HIGH, task("Essay !1").priority)
        assertEquals(Priority.LOW, task("Tidy desk !low").priority)
        assertEquals(Priority.NORMAL, task("Essay").priority)
        assertEquals("Essay", task("Essay !h").title)
    }

    @Test fun deadlineWithoutDayLeavesDayOpen() {
        val t = task("Essay 3h by fri #uni")
        assertEquals(mon.plusDays(4), t.deadline)
        assertEquals(null, t.date)
        assertEquals(180, t.minutes)
        assertEquals("Essay", t.title)
    }

    @Test fun deadlineWithDayKeepsDay() {
        val t = task("Revise tmr due 14/11")
        assertEquals(mon.plusDays(1), t.date)
        assertEquals(java.time.LocalDate.of(2026, 11, 14), t.deadline)
    }

    @Test fun byNotFollowedByDateIsTitle() {
        val t = task("Stand by me")
        assertEquals("Stand by me", t.title)
        assertEquals(null, t.deadline)
        assertEquals(mon, t.date)
    }

    @Test fun dayMonthDates() {
        assertEquals(java.time.LocalDate.of(2026, 12, 3), task("Dentist 3/12").date)
        assertEquals(java.time.LocalDate.of(2027, 1, 2), task("Flight 2/1").date) // past → next year
        assertEquals(null, QuickAdd.parseDate("31/2", mon))
        assertEquals(java.time.LocalDate.of(2027, 3, 1), QuickAdd.parseDate("1/3/27", mon))
    }

    @Test fun firstWordNeverDeadline() {
        assertEquals("By the river walk", task("By the river walk").title)
    }
}

class QuickAddRepeatTest {
    private val mon = java.time.LocalDate.of(2026, 11, 9)
    private fun task(s: String) = QuickAdd.parse(s, mon) as TaskDraft
    private fun bits(vararg d: java.time.DayOfWeek) = d.fold(0) { m, x -> m or Days.bit(x) }

    @Test fun weeklyForms() {
        assertEquals(Repeat.weekly(Days.ALL), task("Stretch daily").repeat)
        assertEquals(Repeat.weekly(Days.WEEKDAYS), task("Standup 09:30 weekdays").repeat)
        assertEquals(Repeat.weekly(bits(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY)), task("Gym 18:00 1h every mon,wed").repeat)
        assertEquals(Repeat.weekly(bits(java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.FRIDAY)), task("Run every tue fri").repeat)
        val g = task("Gym 18:00 1h every mon,wed #gym")
        assertEquals("Gym", g.title); assertEquals(60, g.minutes); assertEquals("gym", g.tag)
        // "weekly" with no day: the weekday it starts on (today = Monday).
        assertEquals(Repeat.weekly(bits(java.time.DayOfWeek.MONDAY)), task("Laundry weekly").repeat)
    }

    @Test fun intervals() {
        assertEquals(Repeat(Repeat.Unit.DAY, 3, Days.bit(java.time.DayOfWeek.MONDAY), mon), task("Water plants every 3 days").repeat)
        assertEquals(Repeat(Repeat.Unit.WEEK, 2, bits(java.time.DayOfWeek.TUESDAY), mon), task("Supervision every 2 weeks tue").repeat)
        assertEquals(Repeat(Repeat.Unit.WEEK, 2, bits(java.time.DayOfWeek.MONDAY), mon), task("Clean fortnightly").repeat)
        val rent = task("Rent 1/12 monthly")
        assertEquals(Repeat(Repeat.Unit.MONTH, 1, Days.bit(java.time.DayOfWeek.TUESDAY), java.time.LocalDate.of(2026, 12, 1)), rent.repeat)
        assertEquals(Repeat.Unit.MONTH, task("Haircut every 2 months").repeat?.unit)
    }

    @Test fun notARepeat() {
        assertEquals(null, task("Every little thing").repeat) // first word is title
        assertEquals("Read every page", task("Read every page").title)
        assertEquals(null, task("Read every page").repeat)
        assertEquals(mon.plusDays(4), task("Essay fri").date) // plain weekday is still a date
    }
}
