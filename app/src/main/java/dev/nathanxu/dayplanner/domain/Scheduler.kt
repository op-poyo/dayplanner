package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

enum class Priority(val rank: Int, val label: String) {
    HIGH(0, "High"), NORMAL(1, "Normal"), LOW(2, "Low");

    companion object {
        /** Stored as text; null/unknown = normal. */
        fun of(s: String?): Priority = entries.firstOrNull { it.name == s } ?: NORMAL
        /** What we store: null for normal so old rows and new ones agree. */
        fun store(p: Priority): String? = if (p == NORMAL) null else p.name
    }
}

/** Time kept for one tag (e.g. quant prep), on [daysMask] days. Other to-dos can't go there. */
data class ProtectedBlock(
    val daysMask: Int,
    val start: LocalTime,
    val end: LocalTime,
    val tag: String,
    val label: String,
)

data class PlanRules(
    val workStart: LocalTime = LocalTime.of(8, 0),
    val workEnd: LocalTime = LocalTime.of(22, 0),
    /** Gap kept between anything placed and whatever's around it. */
    val breakMinutes: Int = 10,
    /** Smallest piece a long to-do is split into. */
    val minChunk: Int = 30,
    /** Longest single sitting; longer to-dos are split. */
    val maxChunk: Int = 120,
    /** For to-dos with no duration. */
    val defaultMinutes: Int = 30,
    /** Start times are rounded up to this many minutes. */
    val step: Int = 5,
)

/** A to-do the planner may place. */
data class PlanItem(
    val id: Long,
    val title: String,
    val minutes: Int?,
    val priority: Priority = Priority.NORMAL,
    val deadline: LocalDate? = null,
    /** Set when the to-do is for a particular day; it's then only placed on that day (today if overdue). */
    val day: LocalDate? = null,
    val tag: String? = null,
    val splittable: Boolean = true,
    /** Minutes needed to get there first (kept free before the block). */
    val travelBefore: Int = 0,
)

data class Block(
    val itemId: Long,
    val title: String,
    val date: LocalDate,
    val startMin: Int,
    val endMin: Int,
    val part: Int = 1,
    val parts: Int = 1,
    val tag: String? = null,
    val travelBefore: Int = 0,
    /** True when it sits in protected time for its tag. */
    val protected: Boolean = false,
) {
    val start: LocalTime get() = LocalTime.of(startMin / 60, startMin % 60)
    val end: LocalTime get() = if (endMin >= 24 * 60) LocalTime.MAX else LocalTime.of(endMin / 60, endMin % 60)
    val minutes get() = endMin - startMin
}

data class Unplaced(val itemId: Long, val title: String, val reason: String)

data class Plan(
    val blocks: List<Block>,
    val unplaced: List<Unplaced>,
    /** Protected time nothing was put in (still kept free). */
    val reserved: List<Busy>,
) {
    val placedIds: Set<Long> get() = blocks.map { it.itemId }.toSet()
}

/**
 * Greedy planner: earliest deadline first, then priority, then longest first; each to-do goes in
 * the earliest free slot it's allowed in. Free time = working hours minus [busy] (padded by the
 * break), with protected blocks kept for their tag. Long to-dos are split into sittings.
 * Deterministic, so the same inputs always give the same plan.
 */
object Scheduler {

    internal data class Slot(val date: LocalDate, val start: Int, val end: Int, val tag: String?) {
        val length get() = end - start
    }

    fun plan(
        items: List<PlanItem>,
        days: List<LocalDate>,
        busy: List<Busy>,
        protectedBlocks: List<ProtectedBlock> = emptyList(),
        rules: PlanRules = PlanRules(),
        now: LocalDateTime,
    ): Plan {
        if (days.isEmpty()) return Plan(emptyList(), items.map { Unplaced(it.id, it.title, "No days to plan") }, emptyList())
        val sortedDays = days.distinct().sorted()
        var slots: List<Slot> = sortedDays.flatMap { freeSlots(it, busy, protectedBlocks, rules, now) }

        val first = sortedDays.first()
        val last = sortedDays.last()
        val order = items.sortedWith(
            compareBy<PlanItem>(
                { effectiveDue(it, first) ?: LocalDate.MAX },
                { it.priority.rank },
                { -(it.minutes ?: rules.defaultMinutes) },
                { it.id },
            )
        )

        val blocks = mutableListOf<Block>()
        val unplaced = mutableListOf<Unplaced>()
        for (item in order) {
            val need = (item.minutes ?: rules.defaultMinutes).coerceAtLeast(rules.step)
            val fixed = fixedDay(item, first)
            val allowed: (LocalDate) -> Boolean = when {
                fixed != null -> { d -> d == fixed }
                item.deadline != null -> { d -> d <= maxOf(item.deadline, first) }
                else -> { _ -> true }
            }
            val ownTagged = item.tag != null && protectedBlocks.any { it.tag == item.tag }
            val result = place(item, need, slots, allowed, ownTagged, rules)
            if (result == null) {
                unplaced += Unplaced(item.id, item.title, reason(item, need, slots, allowed, ownTagged, first, last))
            } else {
                blocks += result.first
                slots = result.second
            }
        }
        val reserved = slots.filter { it.tag != null && it.length >= rules.minChunk }.map { s ->
            Busy(s.date, s.start, s.end, protectedBlocks.firstOrNull { it.tag == s.tag }?.label ?: "Protected")
        }
        return Plan(blocks.sortedWith(compareBy({ it.date }, { it.startMin })), unplaced, reserved)
    }

    /** The day a to-do should be finished by: its own day (today if overdue), or its deadline. */
    fun effectiveDue(item: PlanItem, first: LocalDate): LocalDate? = when {
        item.day != null -> fixedDay(item, first)
        item.deadline != null -> maxOf(item.deadline, first)
        else -> null
    }

    /** The one day a dated to-do may go on: its day, pulled forward to the deadline, never before [first]. */
    fun fixedDay(item: PlanItem, first: LocalDate): LocalDate? {
        val d = item.day ?: return null
        val dl = item.deadline
        return maxOf(if (dl != null && dl < d) dl else d, first)
    }

    /** Working hours of [date] minus [busy] ± break, cut into general and protected slots. */
    internal fun freeSlots(
        date: LocalDate, busy: List<Busy>, protectedBlocks: List<ProtectedBlock>, rules: PlanRules, now: LocalDateTime,
    ): List<Slot> {
        var start = rules.workStart.toMin()
        val end = if (rules.workEnd == LocalTime.MAX || rules.workEnd == LocalTime.MIDNIGHT) 24 * 60 else rules.workEnd.toMin()
        if (date.isBefore(now.toLocalDate())) return emptyList()
        if (date == now.toLocalDate()) start = maxOf(start, roundUp(now.toLocalTime().toMin(), rules.step))
        if (end - start < rules.step) return emptyList()

        // Free intervals.
        var free = listOf(start to end)
        busy.filter { it.date == date }.forEach { b ->
            val bs = b.startMin - rules.breakMinutes
            val be = b.endMin + rules.breakMinutes
            free = free.flatMap { (s, e) ->
                if (be <= s || bs >= e) listOf(s to e)
                else listOfNotNull((s to bs).takeIf { bs > s }, (be to e).takeIf { be < e })
            }
        }
        // Cut by today's protected blocks.
        val prot = protectedBlocks.filter { Days.has(it.daysMask, date.dayOfWeek) && it.end > it.start }
            .map { Triple(it.start.toMin(), it.end.toMin(), it.tag) }
        val out = mutableListOf<Slot>()
        for ((s, e) in free) {
            val cuts = (listOf(s, e) + prot.flatMap { listOf(it.first, it.second) }.filter { it in s..e }).distinct().sorted()
            for (i in 0 until cuts.size - 1) {
                val a = cuts[i]; val z = cuts[i + 1]
                if (z <= a) continue
                val tag = prot.firstOrNull { a >= it.first && z <= it.second }?.third
                val last = out.lastOrNull()
                if (last != null && last.date == date && last.end == a && last.tag == tag) out[out.lastIndex] = last.copy(end = z)
                else out += Slot(date, a, z, tag)
            }
        }
        return out
    }

    /** Places [item] (whole or in sittings). Returns its blocks and the slots left, or null. */
    private fun place(
        item: PlanItem, need: Int, slots: List<Slot>, allowed: (LocalDate) -> Boolean, ownTagged: Boolean, rules: PlanRules,
    ): Pair<List<Block>, List<Slot>>? {
        // Own protected time first, then general time; chronological within each.
        fun candidates(from: List<Slot>) = from
            .filter { allowed(it.date) && (it.tag == null || it.tag == item.tag) }
            .sortedWith(compareBy({ if (ownTagged && it.tag == item.tag) 0 else 1 }, { it.date }, { it.start }))

        val split = item.splittable && need >= 2 * rules.minChunk
        val pieces = mutableListOf<Pair<Slot, IntRange>>() // slot it went in, [start, end)
        var work = slots
        var remaining = need

        fun take(s: Slot, at: Int, minutes: Int) {
            pieces += s to (at until at + minutes)
            remaining -= minutes
            work = cut(work, s.date, at - item.travelBefore - rules.breakMinutes, at + minutes + rules.breakMinutes, rules)
        }

        // Whole first, if it fits in one sitting.
        if (!split || need <= rules.maxChunk) {
            candidates(work).firstOrNull { startIn(it, item.travelBefore, rules) + need <= it.end }
                ?.let { take(it, startIn(it, item.travelBefore, rules), need) }
        }
        if (remaining > 0 && !split) return null

        // Otherwise in sittings of minChunk..maxChunk, never leaving a scrap under minChunk.
        while (remaining > 0) {
            var placed = false
            for (s in candidates(work)) {
                val at = startIn(s, item.travelBefore, rules)
                val room = s.end - at
                if (room < rules.minChunk) continue
                var chunk = minOf(remaining, rules.maxChunk, room)
                if (remaining - chunk in 1 until rules.minChunk) chunk = remaining - rules.minChunk
                if (chunk < rules.minChunk) continue
                take(s, at, chunk)
                placed = true
                break
            }
            if (!placed) return null
        }

        val ordered = pieces.sortedWith(compareBy({ it.first.date }, { it.second.first }))
        val blocks = ordered.mapIndexed { k, (s, r) ->
            Block(
                item.id, item.title, s.date, r.first, r.last + 1, k + 1, ordered.size, item.tag, item.travelBefore,
                protected = s.tag != null,
            )
        }
        return blocks to work
    }

    /** [slots] with [from, to) removed on [date]; drops leftovers shorter than a step. */
    private fun cut(slots: List<Slot>, date: LocalDate, from: Int, to: Int, rules: PlanRules): List<Slot> =
        slots.flatMap { s ->
            if (s.date != date || to <= s.start || from >= s.end) listOf(s)
            else listOfNotNull(s.copy(end = from).takeIf { from > s.start }, s.copy(start = to).takeIf { to < s.end })
        }.filter { it.length >= rules.step }

    private fun startIn(s: Slot, travel: Int, rules: PlanRules) = roundUp(s.start + travel, rules.step)

    private fun reason(
        item: PlanItem, need: Int, slots: List<Slot>, allowed: (LocalDate) -> Boolean, ownTagged: Boolean,
        first: LocalDate, last: LocalDate,
    ): String {
        val free = slots.filter { allowed(it.date) && (it.tag == null || it.tag == item.tag) }.sumOf { it.length }
        val needs = Durations.short(need)
        return when {
            item.day != null -> "Not enough free time on ${dayName(fixedDay(item, first)!!, first)} (needs $needs, ${Durations.short(free)} free)"
            item.deadline != null && item.deadline < first -> "Deadline has passed and today is full (needs $needs)"
            item.deadline != null && item.deadline <= last ->
                "Not enough free time before it's due ${dayName(item.deadline, first)} (needs $needs, ${Durations.short(free)} free)"
            !item.splittable && free >= need -> "No single gap of $needs"
            else -> "No room in these days (needs $needs, ${Durations.short(free)} free)"
        }
    }

    private fun dayName(d: LocalDate, today: LocalDate) = when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) + " " + d.dayOfMonth
    }

    private fun roundUp(m: Int, step: Int) = if (step <= 1) m else ((m + step - 1) / step) * step
    private fun LocalTime.toMin() = hour * 60 + minute
}

object Durations {
    /** 30 → "30 min", 90 → "1 h 30", 120 → "2 h". */
    fun short(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60}"
    }
}
