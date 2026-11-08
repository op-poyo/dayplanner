package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A coloured label for tasks, plans and routines. [key] is what's stored in the database
 * (built-in keys match the old category names, so existing rows keep their tag).
 */
data class Tag(val key: String, val name: String, val color: Int)

object Tags {
    const val OTHER = "OTHER"

    /** Readable with white text in both light and dark themes. */
    val PALETTE: List<Int> = listOf(
        0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00897B, 0xFFE53935,
        0xFF3949AB, 0xFFD81B60, 0xFF6D4C41, 0xFF0097A7, 0xFF7CB342, 0xFFF4511E,
        0xFF5E35B1, 0xFF757575,
    ).map { it.toInt() }

    val DEFAULTS: List<Tag> = listOf(
        Tag("STUDY", "Study", PALETTE[0]),
        Tag("EXERCISE", "Exercise", PALETTE[1]),
        Tag("COOKING", "Cooking", PALETTE[2]),
        Tag("SHOPPING", "Shopping", PALETTE[11]),
        Tag("PROJECT", "Project", PALETTE[3]),
        Tag("QUANT", "Quant", PALETTE[4]),
        Tag(OTHER, "Other", PALETTE[13]),
    )

    /** Built-in shortcuts so #gym, #lc, #uni etc. land on the default tags. */
    private val aliases: Map<String, String> = buildMap {
        listOf("study", "uni", "coursework", "hw", "lecture", "revise").forEach { put(it, "STUDY") }
        listOf("exercise", "ex", "gym", "run", "sport", "workout").forEach { put(it, "EXERCISE") }
        listOf("cook", "cooking", "meal", "food").forEach { put(it, "COOKING") }
        listOf("shop", "shopping", "groceries", "buy").forEach { put(it, "SHOPPING") }
        listOf("project", "proj", "build", "code").forEach { put(it, "PROJECT") }
        listOf("quant", "lc", "leetcode", "zetamac", "interview").forEach { put(it, "QUANT") }
        listOf("other", "misc").forEach { put(it, OTHER) }
    }

    private val nameRx = Regex("""^[A-Za-z][A-Za-z0-9-]{0,19}$""")

    fun isValidName(raw: String): Boolean = nameRx.matches(raw.trim())

    fun keyFor(name: String): String = name.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_")

    /** Existing tag for "#raw": by name, then key, then built-in alias. */
    fun resolve(raw: String, tags: List<Tag>): Tag? {
        val r = raw.trim().lowercase()
        return tags.firstOrNull { it.name.lowercase() == r }
            ?: tags.firstOrNull { it.key.lowercase() == r }
            ?: aliases[r]?.let { key -> tags.firstOrNull { it.key == key } }
    }

    /** First palette colour no tag uses yet (cycles once all are taken). */
    fun nextColor(tags: List<Tag>): Int =
        PALETTE.firstOrNull { c -> tags.none { it.color == c } } ?: PALETTE[tags.size % PALETTE.size]

    fun find(key: String?, tags: List<Tag>): Tag =
        tags.firstOrNull { it.key == key } ?: tags.firstOrNull { it.key == OTHER } ?: DEFAULTS.last()
}

enum class Period(val label: String) {
    WEEK("This week"),
    MONTH("This month"),
    /** Explicit start and end dates. */
    CUSTOM("Custom");

    fun startOf(date: LocalDate): LocalDate = when (this) {
        WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> date.withDayOfMonth(1)
        CUSTOM -> date
    }

    /** Exclusive end of the period that starts on [start]. */
    fun endAfter(start: LocalDate): LocalDate = when (this) {
        WEEK -> start.plusWeeks(1)
        MONTH -> start.plusMonths(1)
        CUSTOM -> start.plusDays(1)
    }

    /** Inclusive last day of the period that starts on [start]. */
    fun lastDay(start: LocalDate): LocalDate = endAfter(start).minusDays(1)

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
