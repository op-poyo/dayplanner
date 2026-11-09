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

    /** A journey to [to]'s location from [from]. [fromEnd] is when the previous event ends; null = from home. */
    data class Leg(val to: CalEvent, val from: String, val fromEnd: LocalDateTime?) {
        val destination: String get() = to.location!!.trim()
    }

    /**
     * Journeys for the day: between consecutive timed events at different known places, plus, if
     * [home] is set, from home to the first timed event (when that has a different known place).
     */
    fun legs(events: List<CalEvent>, home: String? = null): List<Leg> {
        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        val out = mutableListOf<Leg>()
        var prev: CalEvent? = null
        for (e in timed) {
            if (located(e)) {
                val p = prev
                when {
                    p == null && !home.isNullOrBlank() && !sameLocation(home, e.location) ->
                        out += Leg(e, home.trim(), null)
                    p != null && located(p) && !sameLocation(p.location, e.location) ->
                        out += Leg(e, p.location!!.trim(), p.end)
                }
            }
            prev = e
        }
        return out
    }

    /** Distinct (from, to) pairs that need a travel-time lookup. */
    fun travelLegs(events: List<CalEvent>, home: String? = null): List<Pair<String, String>> =
        legs(events, home).map { it.from to it.destination }.distinct()

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
        home: String? = null,
    ): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        events.filter { it.allDay }.forEach { out += TimelineItem.Event(it) }

        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        val legsByEvent = legs(events, home).associateBy { it.to }
        var cursor = windowStart

        fun addFree(from: LocalDateTime, to: LocalDateTime) {
            val s = later(from, windowStart)
            val e = earlier(to, windowEnd)
            if (Duration.between(s, e).toMinutes() >= minFreeMinutes) out += TimelineItem.Free(s, e)
        }

        for (e in timed) {
            val leg = legsByEvent[e]
            // A leg from home only matters if the event is still ahead of us.
            val travel = leg?.takeIf { it.fromEnd != null || e.start.isAfter(windowStart) }?.let {
                val available = Duration.between(it.fromEnd ?: cursor, e.start).toMinutes().coerceAtLeast(0)
                TimelineItem.Travel(it.from, it.destination, travelMinutes(it.from, it.destination), available)
            }
            val leaveBy = e.start.minusMinutes((travel?.minutes ?: 0).toLong())
            addFree(cursor, leaveBy)
            travel?.let { out += it }
            out += TimelineItem.Event(e)
            if (e.end.isAfter(cursor)) cursor = e.end
        }
        addFree(cursor, windowEnd)
        return out
    }
}
