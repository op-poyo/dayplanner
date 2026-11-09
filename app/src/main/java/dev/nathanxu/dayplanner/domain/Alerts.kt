package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A notification to fire at [at]. [key] is stable across reschedules so alarms replace rather than stack. */
data class Alert(val key: String, val at: LocalDateTime, val title: String, val body: String, val kind: Kind) {
    enum class Kind { LEAVE, MORNING }
}

object AlertPlanner {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * "Leave now" alerts: for each journey with a known travel time, fire at
     * start − travel − buffer. Journeys whose leave time has passed, or whose travel time is
     * unknown, get nothing (no guessing).
     */
    fun leaveAlerts(
        events: List<CalEvent>,
        home: String?,
        travelMinutes: (from: String, to: String) -> Int?,
        bufferMinutes: Int,
        now: LocalDateTime,
    ): List<Alert> = DayPlan.legs(events, home).mapNotNull { leg ->
        val minutes = travelMinutes(leg.from, leg.destination) ?: return@mapNotNull null
        val at = leg.to.start.minusMinutes((minutes + bufferMinutes.coerceAtLeast(0)).toLong())
        if (!at.isAfter(now)) return@mapNotNull null
        val fromText = if (leg.fromEnd == null) "from home" else "from ${leg.from}"
        Alert(
            key = "leave:${leg.to.id}:${leg.to.start}",
            at = at,
            title = "Leave now for ${leg.to.title}",
            body = "$minutes min $fromText to ${leg.destination} · starts ${leg.to.start.format(hm)}",
            kind = Alert.Kind.LEAVE,
        )
    }

    /** When today's morning summary should fire, or null if that time has already passed. */
    fun morningAt(today: LocalDate, time: LocalTime, now: LocalDateTime): LocalDateTime? =
        today.atTime(time).takeIf { it.isAfter(now) }

    /**
     * Morning summary text: counts in the title, then the first event and up to [maxTodos] to-dos.
     * Built when the notification fires, so it's current.
     */
    fun summary(events: List<CalEvent>, todos: List<String>, maxTodos: Int = 4): Pair<String, String> {
        val timed = events.filter { !it.allDay }.sortedBy { it.start }
        val eventCount = events.size
        val title = buildList {
            add(if (eventCount == 1) "1 event" else "$eventCount events")
            add(if (todos.size == 1) "1 to-do" else "${todos.size} to-dos")
        }.joinToString(" · ", prefix = "Today: ")
        val body = buildList {
            timed.firstOrNull()?.let { e ->
                add("First: ${e.start.format(hm)} ${e.title}" + (e.location?.let { " ($it)" } ?: ""))
            }
            if (todos.isNotEmpty()) {
                val shown = todos.take(maxTodos).joinToString(", ")
                add("To-do: $shown" + if (todos.size > maxTodos) " +${todos.size - maxTodos} more" else "")
            }
            if (isEmpty()) add("Nothing planned. Enjoy it.")
        }.joinToString("\n")
        return title to body
    }
}
