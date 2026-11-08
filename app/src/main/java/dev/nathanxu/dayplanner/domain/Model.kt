package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Category(val label: String, val emoji: String) {
    STUDY("Study", "📘"),
    EXERCISE("Exercise", "🏃"),
    COOKING("Cooking", "🍳"),
    SHOPPING("Shopping", "🛒"),
    PROJECT("Project", "🛠"),
    QUANT("Quant prep", "📈"),
    OTHER("Other", "•");

    companion object {
        private val aliases: Map<String, Category> = buildMap {
            listOf("study", "uni", "coursework", "hw", "lecture", "revise").forEach { put(it, STUDY) }
            listOf("exercise", "ex", "gym", "run", "sport", "workout").forEach { put(it, EXERCISE) }
            listOf("cook", "cooking", "meal", "food").forEach { put(it, COOKING) }
            listOf("shop", "shopping", "groceries", "buy").forEach { put(it, SHOPPING) }
            listOf("project", "proj", "build", "code").forEach { put(it, PROJECT) }
            listOf("quant", "lc", "leetcode", "zetamac", "interview").forEach { put(it, QUANT) }
            listOf("other", "misc").forEach { put(it, OTHER) }
        }

        fun fromTag(tag: String): Category? = aliases[tag.lowercase()]
    }
}

enum class Period(val label: String) {
    WEEK("This week"),
    MONTH("This month");

    fun startOf(date: LocalDate): LocalDate = when (this) {
        WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> date.withDayOfMonth(1)
    }

    /** Exclusive end of the period that starts on [start]. */
    fun endAfter(start: LocalDate): LocalDate = when (this) {
        WEEK -> start.plusWeeks(1)
        MONTH -> start.plusMonths(1)
    }

    fun contains(start: LocalDate, date: LocalDate): Boolean =
        !date.isBefore(start) && date.isBefore(endAfter(start))
}

/** Weekday bitmask helpers. Bit 0 = Monday … bit 6 = Sunday. */
object Days {
    const val ALL = 0b1111111
    const val WEEKDAYS = 0b0011111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun has(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0
    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)

    fun describe(mask: Int): String = when (mask) {
        ALL -> "Every day"
        WEEKDAYS -> "Weekdays"
        0b1100000 -> "Weekends"
        0 -> "Never"
        else -> DayOfWeek.entries.filter { has(mask, it) }
            .joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
    }
}
