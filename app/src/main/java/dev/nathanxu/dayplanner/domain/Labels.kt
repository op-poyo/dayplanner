package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Labels {
    private val dow = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** "due today", "due tomorrow", "due Fri", "due 14 Nov", "past due · 6 Nov"; urgent = today or earlier. */
    fun deadline(deadline: LocalDate, today: LocalDate): Pair<String, Boolean> {
        val days = ChronoUnit.DAYS.between(today, deadline)
        val text = when {
            days < 0 -> "past due · ${dm.format(deadline)}"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            days < 7 -> "due ${dow.format(deadline)}"
            else -> "due ${dm.format(deadline)}"
        }
        return text to (days <= 0)
    }
}
