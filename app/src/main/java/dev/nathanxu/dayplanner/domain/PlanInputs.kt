package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A to-do as the planner sees it (kept free of Room so it's testable). */
data class PlanCandidate(
    val id: Long,
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val minutes: Int?,
    val priority: Priority,
    val deadline: LocalDate?,
    val tag: String?,
    /** The planner gave it its time last time. */
    val planned: Boolean,
    val done: Boolean = false,
    val travelBefore: Int = 0,
    /** Made by a routine or long-term task for one day; it stays on that day (today if past). */
    val generated: Boolean = false,
)

object PlanInputs {
    /**
     * Timed events → per-day [Busy] for [days]. All-day events don't block time. Multi-day events
     * are cut at midnight. [padBefore] adds travel time before an event (by event id).
     */
    fun busyFromEvents(events: List<CalEvent>, days: List<LocalDate>, padBefore: Map<Long, Int> = emptyMap()): List<Busy> {
        val set = days.toSet()
        return events.filter { !it.allDay && it.end.isAfter(it.start) }.flatMap { e ->
            val start = e.start.minusMinutes((padBefore[e.id] ?: 0).toLong())
            var d = start.toLocalDate()
            val out = mutableListOf<Busy>()
            while (!d.isAfter(e.end.toLocalDate())) {
                if (d in set) {
                    val s = if (d == start.toLocalDate()) start.hour * 60 + start.minute else 0
                    val en = if (d == e.end.toLocalDate()) e.end.hour * 60 + e.end.minute else 24 * 60
                    if (en > s) out += Busy(d, s, en, e.title)
                }
                d = d.plusDays(1)
            }
            out
        }
    }

    /** A planned block that's over and still not ticked. */
    fun slipped(c: PlanCandidate, now: LocalDateTime, rules: PlanRules = PlanRules()): Boolean {
        if (!c.planned || c.done || c.date == null || c.time == null) return false
        val sitting = minOf(c.minutes ?: rules.defaultMinutes, rules.maxChunk)
        return c.date.atTime(c.time).plusMinutes(sitting.toLong()).isBefore(now)
    }

    /**
     * Which open to-dos to plan over [days], and which timed ones block time.
     * Planned: no time yet and dated within the range (or overdue), or undated with a deadline, or
     * undated and high priority, or any undated if [includeSomeday]; plus slipped planned blocks
     * (which may then go on any day before their deadline, except generated ones: today).
     */
    fun select(
        candidates: List<PlanCandidate>,
        days: List<LocalDate>,
        now: LocalDateTime,
        includeSomeday: Boolean,
        rules: PlanRules = PlanRules(),
    ): Pair<List<PlanItem>, List<Busy>> {
        val last = days.max()
        val items = mutableListOf<PlanItem>()
        val busy = mutableListOf<Busy>()
        for (c in candidates.filter { !it.done }) {
            val slipped = slipped(c, now, rules)
            val wanted = when {
                slipped -> true
                c.time != null -> false
                c.date != null -> !c.date.isAfter(last)
                else -> c.deadline != null || c.priority == Priority.HIGH || includeSomeday
            }
            if (wanted) {
                items += PlanItem(
                    id = c.id, title = c.title, minutes = c.minutes, priority = c.priority, deadline = c.deadline,
                    day = if (slipped && !c.generated) null else c.date, tag = c.tag,
                    splittable = (c.minutes ?: 0) >= 2 * rules.minChunk, travelBefore = c.travelBefore,
                )
            } else if (c.time != null && c.date != null && c.date in days) {
                val s = c.time.hour * 60 + c.time.minute
                busy += Busy(c.date, s, (s + (c.minutes ?: rules.defaultMinutes)).coerceAtMost(24 * 60), c.title)
            }
        }
        return items to busy
    }
}
