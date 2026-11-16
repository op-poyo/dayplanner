package dev.nathanxu.dayplanner.domain

import java.time.LocalDate

/**
 * A goal's period for "N per …" (since v1.12; NONE / null = N in total over the goal's dates).
 * Stored by name, so the names stay as they were when it meant "how to split into to-dos".
 */
enum class Cadence(val label: String, val short: String) {
    DAILY("Daily", "today"),
    THREE_DAY("Every 3 days", "these 3 days"),
    WEEKLY("Weekly", "this week"),
    MONTHLY("Monthly", "this month"),
    NONE("No breakdown", ""),
}

object Pacing {
    /** End date meaning "no end". */
    val ONGOING_END: LocalDate = LocalDate.of(9999, 12, 31)

    fun isOngoing(end: LocalDate): Boolean = end == ONGOING_END
}
