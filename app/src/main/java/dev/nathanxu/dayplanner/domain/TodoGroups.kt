package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Sections for the "all to-dos" list: Overdue, Today, Tomorrow, then each of the next few days,
 * then by week, then by month, then Someday. Near things get detail; far things get grouped.
 */
object TodoGroups {
    data class Group(val order: Long, val label: String)

    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMM")
    private val weekFmt = DateTimeFormatter.ofPattern("d MMM")
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM")
    private val monthYearFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

    private const val TIER = 1_000_000_000L

    fun of(date: LocalDate?, today: LocalDate): Group {
        if (date == null) return Group(9 * TIER, "Someday")
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days < 0 -> Group(0, "Overdue")
            days == 0L -> Group(1, "Today")
            days == 1L -> Group(2, "Tomorrow")
            days < 7 -> Group(3 * TIER + days, date.format(dayFmt))
            days < 28 -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Group(4 * TIER + monday.toEpochDay(), "Week of ${monday.format(weekFmt)}")
            }
            else -> Group(
                5 * TIER + date.year * 12L + date.monthValue,
                date.format(if (date.year == today.year) monthFmt else monthYearFmt),
            )
        }
    }

    /** Groups items by their date, in display order. */
    fun <T> group(items: List<T>, today: LocalDate, dateOf: (T) -> LocalDate?): List<Pair<String, List<T>>> =
        items.groupBy { of(dateOf(it), today) }
            .entries.sortedBy { it.key.order }
            .map { (g, list) -> g.label to list }
}
