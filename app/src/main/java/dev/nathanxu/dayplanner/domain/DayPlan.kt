package dev.nathanxu.dayplanner.domain

import java.time.Duration
import java.time.LocalDateTime

data class CalEvent(
    val id: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String?,
    val allDay: Boolean,
    val color: Int? = null,
)

sealed interface TimelineItem {
    data class Event(val event: CalEvent) : TimelineItem

    /** Travel leg into the next event. [minutes] is null if unknown (no API key / offline). */
    data class Travel(
        val from: String,
        val to: String,
        val minutes: Int?,
        val availableMinutes: Long,
    ) : TimelineItem {
        val tight: Boolean get() = minutes != null && minutes > availableMinutes
    }

    data class Free(val start: LocalDateTime, val end: LocalDateTime) : TimelineItem {
        val minutes: Long get() = Duration.between(start, end).toMinutes()
    }
}

fun later(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isAfter(b)) a else b
fun earlier(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isBefore(b)) a else b

object DayPlan {
    fun sameLocation(a: String?, b: String?): Boolean =
        a?.trim()?.lowercase() == b?.trim()?.lowercase()

    private fun located(e: CalEvent) = !e.location.isNullOrBlank()

    /** Legs that need a travel-time lookup: consecutive timed events at different known places. */
    fun travelLegs(events: List<CalEvent>): List<Pair<String, String>> {
        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        return timed.zipWithNext()
            .filter { (a, b) -> located(a) && located(b) && !sameLocation(a.location, b.location) }
            .map { (a, b) -> a.location!!.trim() to b.location!!.trim() }
            .distinct()
    }

    /**
     * Builds today's timeline: all-day events first, then timed events in order, with a travel leg
     * before any event whose location differs from the previous one, and free slots of at least
     * [minFreeMinutes] between [windowStart] and [windowEnd] (after subtracting travel).
     */
    fun buildTimeline(
        events: List<CalEvent>,
        travelMinutes: (from: String, to: String) -> Int?,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime,
        minFreeMinutes: Long = 30,
    ): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        events.filter { it.allDay }.forEach { out += TimelineItem.Event(it) }

        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        var cursor = windowStart
        var prev: CalEvent? = null

        fun addFree(from: LocalDateTime, to: LocalDateTime) {
            val s = later(from, windowStart)
            val e = earlier(to, windowEnd)
            if (Duration.between(s, e).toMinutes() >= minFreeMinutes) out += TimelineItem.Free(s, e)
        }

        for (e in timed) {
            var travel: TimelineItem.Travel? = null
            val p = prev
            if (p != null && located(p) && located(e) && !sameLocation(p.location, e.location)) {
                val from = p.location!!.trim()
                val to = e.location!!.trim()
                val available = Duration.between(p.end, e.start).toMinutes().coerceAtLeast(0)
                travel = TimelineItem.Travel(from, to, travelMinutes(from, to), available)
            }
            val leaveBy = e.start.minusMinutes((travel?.minutes ?: 0).toLong())
            addFree(cursor, leaveBy)
            travel?.let { out += it }
            out += TimelineItem.Event(e)
            if (e.end.isAfter(cursor)) cursor = e.end
            prev = e
        }
        addFree(cursor, windowEnd)
        return out
    }
}
