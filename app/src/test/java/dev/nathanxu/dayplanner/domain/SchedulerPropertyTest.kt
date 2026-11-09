package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** Random inputs; checks the plan's invariants rather than exact output. */
class SchedulerPropertyTest {
    @Test fun invariantsHoldOnRandomInputs() {
        val start = LocalDate.of(2026, 11, 9)
        repeat(400) { seed ->
            val rnd = Random(seed)
            val days = (0 until rnd.nextInt(1, 8)).map { start.plusDays(it.toLong()) }
            val rules = PlanRules(
                workStart = LocalTime.of(rnd.nextInt(6, 11), 0),
                workEnd = LocalTime.of(rnd.nextInt(16, 24), 0),
                breakMinutes = rnd.nextInt(0, 20),
            )
            val busy = days.flatMap { d ->
                (0 until rnd.nextInt(0, 6)).map {
                    val s = rnd.nextInt(6 * 60, 22 * 60)
                    Busy(d, s, (s + rnd.nextInt(15, 180)).coerceAtMost(24 * 60), "b")
                }
            }
            val prot = if (rnd.nextBoolean()) listOf(ProtectedBlock(Days.WEEKDAYS, LocalTime.of(18, 0), LocalTime.of(20, 0), "q", "Q")) else emptyList()
            val items = (1L..rnd.nextLong(1, 15)).map { id ->
                PlanItem(
                    id, "T$id",
                    minutes = if (rnd.nextInt(5) == 0) null else rnd.nextInt(10, 400),
                    priority = Priority.entries.random(rnd),
                    deadline = if (rnd.nextBoolean()) start.plusDays(rnd.nextLong(-1, 8)) else null,
                    day = if (rnd.nextInt(4) == 0) start.plusDays(rnd.nextLong(-2, 7)) else null,
                    tag = if (rnd.nextInt(3) == 0) "q" else null,
                    splittable = rnd.nextInt(4) != 0,
                    travelBefore = if (rnd.nextInt(4) == 0) rnd.nextInt(5, 40) else 0,
                )
            }
            val now = start.atTime(rnd.nextInt(0, 24), rnd.nextInt(0, 60))
            val p = Scheduler.plan(items, days, busy, prot, rules, now)
            val ctx = "seed $seed"

            // Every item exactly once: placed or unplaced.
            assertEquals(ctx, items.map { it.id }.toSet(), p.placedIds + p.unplaced.map { it.itemId })
            assertTrue(ctx, p.placedIds.intersect(p.unplaced.map { it.itemId }.toSet()).isEmpty())

            val byItem = p.blocks.groupBy { it.itemId }
            for ((id, bs) in byItem) {
                val it = items.first { x -> x.id == id }
                assertEquals("$ctx minutes for $id", it.minutes?.coerceAtLeast(rules.step) ?: rules.defaultMinutes, bs.sumOf { b -> b.minutes })
                if (!it.splittable) assertEquals(ctx, 1, bs.size)
                bs.forEach { b ->
                    assertTrue("$ctx chunk", bs.size == 1 || b.minutes >= rules.minChunk)
                    assertTrue("$ctx max", bs.size == 1 || b.minutes <= rules.maxChunk)
                    it.deadline?.let { dl -> assertTrue("$ctx deadline", !b.date.isAfter(maxOf(dl, start))) }
                    if (it.day != null) assertEquals("$ctx day", Scheduler.fixedDay(it, start), b.date)
                    if (b.protected) assertEquals(ctx, "q", it.tag)
                }
            }
            for (b in p.blocks) {
                val work0 = rules.workStart.hour * 60
                val work1 = rules.workEnd.hour * 60
                assertTrue("$ctx in hours", b.startMin - b.travelBefore >= work0 && b.endMin <= work1)
                if (b.date == now.toLocalDate()) assertTrue("$ctx after now", b.startMin >= now.hour * 60 + now.minute)
                // Clear of busy, with the break.
                busy.filter { it.date == b.date }.forEach { x ->
                    assertTrue("$ctx busy", b.endMin + rules.breakMinutes <= x.startMin || b.startMin - b.travelBefore >= x.endMin + rules.breakMinutes)
                }
                // Not in someone else's protected time.
                if (b.tag != "q") prot.filter { Days.has(it.daysMask, b.date.dayOfWeek) }.forEach { pr ->
                    val ps = pr.start.hour * 60; val pe = pr.end.hour * 60
                    assertTrue("$ctx protected", b.endMin <= ps || b.startMin >= pe)
                }
            }
            // Blocks don't overlap each other (break between).
            p.blocks.groupBy { it.date }.values.forEach { list ->
                list.sortedBy { it.startMin }.zipWithNext().forEach { (a, b) ->
                    assertTrue("$ctx overlap", a.endMin + rules.breakMinutes <= b.startMin - b.travelBefore)
                }
            }
        }
    }
}
