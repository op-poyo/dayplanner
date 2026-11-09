package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One row of Today's timeline: a calendar row (event, travel, free gap) or a timed to-do. */
sealed interface DayRow {
    data class Cal(val item: TimelineItem) : DayRow
    data class Todo<T>(val todo: T, val at: LocalTime) : DayRow
}

/** What Next up shows. */
data class NextUp(val title: String, val start: LocalDateTime, val end: LocalDateTime?, val location: String?, val isTodo: Boolean, val ongoing: Boolean)

object TodayView {
    /**
     * Calendar rows and timed to-dos in one time order. Events in [hiddenEventIds] (a planned
     * block or repeating to-do that's already on the list as a to-do) are dropped, along with the
     * travel row into them; free gaps that a to-do now sits in are dropped too.
     */
    fun <T> merge(
        items: List<TimelineItem>,
        todos: List<T>,
        timeOf: (T) -> LocalTime,
        minutesOf: (T) -> Int?,
        date: LocalDate,
        hiddenEventIds: Set<Long>,
    ): List<DayRow> {
        // Drop hidden events and the travel row right before each.
        val kept = mutableListOf<TimelineItem>()
        for ((i, it) in items.withIndex()) {
            when (it) {
                is TimelineItem.Event -> if (it.event.id !in hiddenEventIds) kept += it
                is TimelineItem.Travel -> {
                    val nextEvent = items.drop(i + 1).firstOrNull { x -> x is TimelineItem.Event } as? TimelineItem.Event
                    if (nextEvent == null || nextEvent.event.id !in hiddenEventIds) kept += it
                }
                is TimelineItem.Free -> kept += it
            }
        }
        val spans = todos.map { t ->
            val s = date.atTime(timeOf(t))
            s to s.plusMinutes((minutesOf(t) ?: 0).toLong())
        }
        val calRows = kept.filterNot { it is TimelineItem.Free && spans.any { (s, e) -> s < it.end && maxOf(e, s.plusMinutes(1)) > it.start } }

        // Sort key: events and gaps by start; travel just before the next event.
        val keyed = calRows.mapIndexed { i, it ->
            val key = when (it) {
                is TimelineItem.Event -> it.event.start
                is TimelineItem.Free -> it.start
                is TimelineItem.Travel -> (calRows.drop(i + 1).firstOrNull { x -> x is TimelineItem.Event } as? TimelineItem.Event)?.event?.start?.minusNanos(1)
                    ?: LocalDateTime.MAX
            }
            Triple(key, 0, DayRow.Cal(it) as DayRow)
        } + todos.map { Triple(date.atTime(timeOf(it)), 1, DayRow.Todo(it, timeOf(it)) as DayRow) }
        return keyed.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    /** The event or timed to-do happening now, else the next one today. Done to-dos are skipped. */
    fun nextUp(events: List<CalEvent>, todos: List<Triple<String, LocalDateTime, Int?>>, now: LocalDateTime): NextUp? {
        val candidates = events.filter { !it.allDay && it.end.isAfter(now) && it.start.toLocalDate() == now.toLocalDate() }
            .map { NextUp(it.title, it.start, it.end, it.location, false, !it.start.isAfter(now)) } +
            todos.mapNotNull { (title, start, minutes) ->
                val end = minutes?.let { start.plusMinutes(it.toLong()) }
                val ongoing = !start.isAfter(now) && end != null && end.isAfter(now)
                if (start.isAfter(now) || ongoing) NextUp(title, start, end, null, true, ongoing) else null
            }
        // Something on now wins; otherwise the soonest.
        return candidates.filter { it.ongoing }.minByOrNull { it.start } ?: candidates.minByOrNull { it.start }
    }

    /** "3 tomorrow · 7 this week · 5 someday" for the collapsed Later line. */
    fun laterSummary(dates: List<LocalDate?>, today: LocalDate): String {
        val tomorrow = dates.count { it == today.plusDays(1) }
        val week = dates.count { it != null && it.isAfter(today.plusDays(1)) && !it.isAfter(today.plusDays(7)) }
        val later = dates.count { it != null && it.isAfter(today.plusDays(7)) }
        val someday = dates.count { it == null }
        return listOfNotNull(
            "$tomorrow tomorrow".takeIf { tomorrow > 0 },
            "$week this week".takeIf { week > 0 },
            "$later later".takeIf { later > 0 },
            "$someday someday".takeIf { someday > 0 },
        ).joinToString(" · ")
    }
}
