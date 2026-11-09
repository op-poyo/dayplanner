package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Something already occupying [startMin, endMin) minutes of [date]. */
data class Busy(val date: LocalDate, val startMin: Int, val endMin: Int, val label: String)

/** One overlap between a routine occurrence and [busy]. */
data class Clash(val date: LocalDate, val busy: Busy)

object Clashes {
    /** Dates in [from, from+days) that fall on the routine's weekdays. */
    fun occurrences(mask: Int, from: LocalDate, days: Int): List<LocalDate> = Repeat.weekly(mask).occurrences(from, days)

    /** Every [Busy] block a routine at [time] for [minutes] on [mask] days would overlap. */
    fun find(mask: Int, time: LocalTime, minutes: Int, from: LocalDate, days: Int, busy: List<Busy>): List<Clash> =
        find(Repeat.weekly(mask), time, minutes, from, days, busy)

    /** Every [Busy] block a routine at [time] for [minutes], repeating per [repeat], would overlap. */
    fun find(repeat: Repeat, time: LocalTime, minutes: Int, from: LocalDate, days: Int, busy: List<Busy>): List<Clash> {
        val s = time.hour * 60 + time.minute
        val e = (s + minutes.coerceAtLeast(1)).coerceAtMost(24 * 60)
        val dates = repeat.occurrences(from, days).toSet()
        return busy
            .filter { it.date in dates && it.startMin < e && s < it.endMin }
            .sortedWith(compareBy({ it.date }, { it.startMin }))
            .map { Clash(it.date, it) }
    }

    /** Busy blocks for another routine's occurrences in the window. */
    fun routineBusy(
        mask: Int, time: LocalTime, minutes: Int, label: String, from: LocalDate, days: Int,
    ): List<Busy> = routineBusy(Repeat.weekly(mask), time, minutes, label, from, days)

    fun routineBusy(
        repeat: Repeat, time: LocalTime, minutes: Int, label: String, from: LocalDate, days: Int,
    ): List<Busy> {
        val s = time.hour * 60 + time.minute
        val e = (s + minutes.coerceAtLeast(1)).coerceAtMost(24 * 60)
        return repeat.occurrences(from, days).map { Busy(it, s, e, label) }
    }

    /**
     * Human summary. Weekly repeats (same label, weekday and time) collapse into one line, e.g.
     * "Mon 09:00–11:00 MATH0005 lecture (×4)". At most [limit] lines.
     */
    fun summarise(clashes: List<Clash>, limit: Int = 3): List<String> {
        val groups = clashes.groupBy { Triple(it.busy.label, it.date.dayOfWeek, it.busy.startMin to it.busy.endMin) }
        val lines = groups.entries
            .sortedWith(compareBy({ it.value.first().date }, { it.key.third.first }))
            .map { (key, list) ->
                val (label, dow, span) = key
                val times = "${hhmm(span.first)}–${hhmm(span.second)}"
                val count = if (list.size > 1) " (×${list.size})" else ""
                "${dayName(dow)} $times $label$count"
            }
        return if (lines.size <= limit) lines else lines.take(limit) + "+${lines.size - limit} more"
    }

    private fun hhmm(m: Int) = if (m >= 24 * 60) "24:00" else "%02d:%02d".format(m / 60, m % 60)
    private fun dayName(d: DayOfWeek) = d.name.take(3).lowercase().replaceFirstChar(Char::uppercase)
}
