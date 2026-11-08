package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

sealed interface Parsed

data class TaskDraft(
    val title: String,
    val date: LocalDate?,
    val category: Category,
    val minutes: Int?,
    val goalHint: String?,
) : Parsed

data class GoalDraft(
    val title: String,
    val period: Period,
    val target: Int,
    val recurring: Boolean,
) : Parsed

/**
 * One-box quick add. See PLAN.md §5 for the syntax.
 *
 * Date words are only recognised after the first word, so titles like
 * "Sun cream" or "Today show notes" aren't eaten.
 * Returns null if nothing usable remains for a title.
 */
object QuickAdd {
    private val duration1 = Regex("""^(\d{1,2})h(?:(\d{1,2})m?)?$""")
    private val duration2 = Regex("""^(\d{1,3})m(?:in)?$""")
    private val targetRx = Regex("""^x(\d{1,3})$""")

    private val weekdays: Map<String, DayOfWeek> = buildMap {
        mapOf(
            DayOfWeek.MONDAY to listOf("mon", "monday"),
            DayOfWeek.TUESDAY to listOf("tue", "tues", "tuesday"),
            DayOfWeek.WEDNESDAY to listOf("wed", "weds", "wednesday"),
            DayOfWeek.THURSDAY to listOf("thu", "thur", "thurs", "thursday"),
            DayOfWeek.FRIDAY to listOf("fri", "friday"),
            DayOfWeek.SATURDAY to listOf("sat", "saturday"),
            DayOfWeek.SUNDAY to listOf("sun", "sunday"),
        ).forEach { (day, names) -> names.forEach { put(it, day) } }
    }

    fun parse(input: String, today: LocalDate): Parsed? {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        var date: LocalDate? = today
        var category = Category.OTHER
        var minutes: Int? = null
        var goalHint: String? = null
        var period: Period? = null
        var target = 1
        var recurring = false
        val title = mutableListOf<String>()

        words.forEachIndexed { i, w ->
            val lw = w.lowercase()
            val dateAllowed = i > 0
            when {
                dateAllowed && (lw == "today" || lw == "tod") -> date = today
                dateAllowed && lw in setOf("tmr", "tmrw", "tomorrow") -> date = today.plusDays(1)
                dateAllowed && lw == "someday" -> date = null
                dateAllowed && lw in weekdays ->
                    date = today.with(TemporalAdjusters.nextOrSame(weekdays.getValue(lw)))
                lw == "@week" || lw == "@w" -> period = Period.WEEK
                lw == "@month" || lw == "@m" -> period = Period.MONTH
                lw == "!r" -> recurring = true
                lw.length > 1 && lw.startsWith("#") && Category.fromTag(lw.drop(1)) != null ->
                    category = Category.fromTag(lw.drop(1))!!
                lw.length > 1 && lw.startsWith("^") -> goalHint = lw.drop(1)
                targetRx.matches(lw) -> target = targetRx.find(lw)!!.groupValues[1].toInt().coerceAtLeast(1)
                duration1.matches(lw) -> {
                    val g = duration1.find(lw)!!.groupValues
                    minutes = g[1].toInt() * 60 + (g[2].toIntOrNull() ?: 0)
                }
                duration2.matches(lw) -> minutes = duration2.find(lw)!!.groupValues[1].toInt()
                else -> title += w
            }
        }

        val t = title.joinToString(" ").trim()
        if (t.isEmpty()) return null
        return if (period != null) {
            GoalDraft(t, period!!, target, recurring)
        } else {
            TaskDraft(t, date, category, minutes?.takeIf { it > 0 }, goalHint)
        }
    }
}
