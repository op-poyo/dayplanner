package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** The part of an event that falls on one [day], in minutes from midnight. */
data class DaySegment(val event: CalEvent, val day: LocalDate, val startMin: Int, val endMin: Int) {
    val allDay: Boolean get() = event.allDay
}

/** A timed segment placed in one of [lanes] side-by-side columns (for overlaps). */
data class Placed(val segment: DaySegment, val lane: Int, val lanes: Int)

enum class CalendarMode(val label: String) {
    SCHEDULE("Schedule"),
    THREE_DAY("3 day"),
    WEEK("Week"),
    MONTH("Month"),
}

object CalendarLayout {
    const val DAY_MINUTES = 24 * 60
    /** Zero-length events still get a visible block. */
    const val MIN_BLOCK_MINUTES = 15

    private fun minuteOfDay(t: LocalDateTime) = t.hour * 60 + t.minute

    /**
     * Splits events across the days of [start, endExclusive). Multi-day events appear on each day
     * they touch; all-day events use their (exclusive) end date. Each day's list is sorted:
     * all-day first, then by start, longer first.
     */
    fun byDay(events: List<CalEvent>, start: LocalDate, endExclusive: LocalDate): Map<LocalDate, List<DaySegment>> {
        val out = mutableMapOf<LocalDate, MutableList<DaySegment>>()
        fun add(seg: DaySegment) {
            if (!seg.day.isBefore(start) && seg.day.isBefore(endExclusive)) out.getOrPut(seg.day) { mutableListOf() } += seg
        }
        for (e in events) {
            if (e.allDay) {
                val first = e.start.toLocalDate()
                val lastExcl = e.end.toLocalDate().let { if (it.isAfter(first)) it else first.plusDays(1) }
                var d = first
                while (d.isBefore(lastExcl)) {
                    add(DaySegment(e, d, 0, DAY_MINUTES))
                    d = d.plusDays(1)
                }
            } else {
                val d0 = e.start.toLocalDate()
                val d1 = if (!e.end.isAfter(e.start)) d0 else e.end.minusNanos(1).toLocalDate()
                var d = d0
                while (!d.isAfter(d1)) {
                    val s = if (d == d0) minuteOfDay(e.start) else 0
                    val en = when {
                        !e.end.isAfter(e.start) -> s // zero-length: widened to the minimum below
                        d == e.end.toLocalDate() -> minuteOfDay(e.end)
                        else -> DAY_MINUTES
                    }
                    add(DaySegment(e, d, s, maxOf(en, (s + MIN_BLOCK_MINUTES).coerceAtMost(DAY_MINUTES))))
                    d = d.plusDays(1)
                }
            }
        }
        return out.mapValues { (_, segs) ->
            segs.sortedWith(
                compareByDescending<DaySegment> { it.allDay }
                    .thenBy { it.startMin }
                    .thenByDescending { it.endMin - it.startMin }
            )
        }
    }

    /**
     * Assigns overlapping timed segments to side-by-side lanes. Segments that overlap (directly or
     * through a chain) form a cluster; every segment in a cluster shares its lane count so widths
     * line up.
     */
    fun lanes(segments: List<DaySegment>): List<Placed> {
        val timed = segments.filter { !it.allDay }.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
        val out = mutableListOf<Placed>()
        var cluster = mutableListOf<Pair<DaySegment, Int>>()
        val laneEnds = mutableListOf<Int>()
        var clusterEnd = -1

        fun flush() {
            val n = laneEnds.size
            cluster.forEach { (seg, lane) -> out += Placed(seg, lane, n) }
            cluster = mutableListOf()
            laneEnds.clear()
        }

        for (seg in timed) {
            if (cluster.isNotEmpty() && seg.startMin >= clusterEnd) flush()
            val free = laneEnds.indexOfFirst { it <= seg.startMin }
            val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = seg.endMin
            cluster += seg to lane
            clusterEnd = if (cluster.size == 1) seg.endMin else maxOf(clusterEnd, seg.endMin)
        }
        if (cluster.isNotEmpty()) flush()
        return out
    }

    /** 42 days (6 weeks, Monday-first) covering [month]. */
    fun monthGrid(month: YearMonth): List<LocalDate> {
        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 42).map { first.plusDays(it.toLong()) }
    }

    /** Days shown by a time-grid mode around [anchor]. */
    fun gridDays(mode: CalendarMode, anchor: LocalDate): List<LocalDate> = when (mode) {
        CalendarMode.THREE_DAY -> (0 until 3).map { anchor.plusDays(it.toLong()) }
        CalendarMode.WEEK -> {
            val mon = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (0 until 7).map { mon.plusDays(it.toLong()) }
        }
        CalendarMode.SCHEDULE, CalendarMode.MONTH -> listOf(anchor)
    }

    /** Where the ‹ › arrows move [anchor] to. */
    fun step(mode: CalendarMode, anchor: LocalDate, forward: Boolean): LocalDate {
        val sign = if (forward) 1L else -1L
        return when (mode) {
            CalendarMode.SCHEDULE -> anchor.plusWeeks(sign)
            CalendarMode.THREE_DAY -> anchor.plusDays(3 * sign)
            CalendarMode.WEEK -> anchor.plusWeeks(sign)
            CalendarMode.MONTH -> anchor.plusMonths(sign).withDayOfMonth(1)
        }
    }
}
