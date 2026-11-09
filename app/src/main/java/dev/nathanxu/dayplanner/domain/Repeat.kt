package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * When a routine happens: every [every] days, every [every] weeks on [daysMask] days, or every
 * [every] months on [from]'s day of the month (the last day in shorter months). [from] is the
 * first possible day and the anchor for the counting; null (older routines) means weekly from
 * any time.
 */
data class Repeat(
    val unit: Unit = Unit.WEEK,
    val every: Int = 1,
    val daysMask: Int = Days.ALL,
    val from: LocalDate? = null,
) {
    enum class Unit { DAY, WEEK, MONTH }

    val isValid: Boolean
        get() = every >= 1 && (unit != Unit.WEEK || daysMask != 0) && (from != null || (unit == Unit.WEEK && every == 1))

    fun occursOn(d: LocalDate): Boolean {
        if (!isValid) return false
        if (from != null && d.isBefore(from)) return false
        return when (unit) {
            Unit.DAY -> ChronoUnit.DAYS.between(from, d) % every == 0L
            Unit.WEEK -> Days.has(daysMask, d.dayOfWeek) &&
                (every == 1 || ChronoUnit.WEEKS.between(monday(from!!), monday(d)) % every == 0L)
            Unit.MONTH -> {
                val months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(d))
                months % every == 0L && d.dayOfMonth == minOf(from!!.dayOfMonth, d.lengthOfMonth())
            }
        }
    }

    /** Dates in [start, start+days) it happens on. */
    fun occurrences(start: LocalDate, days: Int): List<LocalDate> =
        (0 until days).map { start.plusDays(it.toLong()) }.filter(::occursOn)

    /** First date on or after [d] (within ~3 years), or null. */
    fun first(d: LocalDate): LocalDate? {
        var x = if (from != null && from.isAfter(d)) from else d
        repeat(366 * 3) {
            if (occursOn(x)) return x
            x = x.plusDays(1)
        }
        return null
    }

    fun describe(): String = when (unit) {
        Unit.DAY -> if (every == 1) "Every day" else "Every $every days"
        Unit.WEEK -> if (every == 1) Days.describe(daysMask) else "Every $every weeks · ${Days.describe(daysMask)}"
        Unit.MONTH -> {
            val day = from?.dayOfMonth?.let { ordinal(it) } ?: "?"
            if (every == 1) "Monthly on the $day" else "Every $every months on the $day"
        }
    }

    /** RFC 5545 rule for the matching Google Calendar event (its first instance is [first]). */
    fun rrule(): String = when (unit) {
        Unit.DAY -> "FREQ=DAILY;INTERVAL=$every"
        Unit.WEEK -> {
            val byDay = DayOfWeek.entries.filter { Days.has(daysMask, it) }.joinToString(",") { it.name.take(2) }
            "FREQ=WEEKLY;INTERVAL=$every;WKST=MO;BYDAY=$byDay"
        }
        Unit.MONTH -> {
            val d = from!!.dayOfMonth
            // 29th–31st: "that day, or the month's last day if it's shorter" = the last of 28..d.
            if (d <= 28) "FREQ=MONTHLY;INTERVAL=$every;BYMONTHDAY=$d"
            else "FREQ=MONTHLY;INTERVAL=$every;BYMONTHDAY=${(28..d).joinToString(",")};BYSETPOS=-1"
        }
    }

    companion object {
        fun weekly(mask: Int) = Repeat(Unit.WEEK, 1, mask, null)

        /** From stored columns; missing ones mean the original weekly-by-days routine. */
        fun of(unit: String?, every: Int?, daysMask: Int, from: LocalDate?): Repeat {
            val u = Unit.entries.firstOrNull { it.name == unit } ?: Unit.WEEK
            return Repeat(u, (every ?: 1).coerceAtLeast(1), daysMask, from)
        }

        private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun ordinal(n: Int): String = n.toString() + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}
