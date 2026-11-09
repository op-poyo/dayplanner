package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

sealed interface Parsed

data class TaskDraft(
    val title: String,
    val date: LocalDate?,
    /** Raw tag text after '#', lower-cased; resolved (or created) by the repository. */
    val tag: String?,
    val minutes: Int?,
    val goalHint: String?,
    val time: LocalTime? = null,
    val priority: Priority = Priority.NORMAL,
    val deadline: LocalDate? = null,
    /** Set when it repeats ("every mon,wed", "daily"…): it becomes a repeating to-do. */
    val repeat: Repeat? = null,
) : Parsed

data class GoalDraft(
    val title: String,
    val period: Period,
    val target: Int,
    val recurring: Boolean,
    val tag: String? = null,
) : Parsed

/**
 * One-box quick add. See PLAN.md §5 for the syntax.
 *
 * Date words are only recognised after the first word, so titles like
 * "Sun cream" or "Today show notes" aren't eaten.
 * Returns null if nothing usable remains for a title.
 */
object QuickAdd {
    private val hoursRx = Regex("""^(\d{1,2}(?:\.\d{1,2})?)(?:h|hr|hrs|hour|hours)$""")
    private val hoursMinsRx = Regex("""^(\d{1,2})(?:h|hr|hrs)(\d{1,2})(?:m|min|mins)?$""")
    private val minsRx = Regex("""^(\d{1,3})(?:m|min|mins|minute|minutes)$""")

    /** "2h", "2hr", "2hrs", "1.5h", "1h30", "1hr30min", "45m", "45min", "90mins" → minutes. */
    fun parseDuration(token: String): Int? {
        val t = token.lowercase()
        hoursMinsRx.find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        hoursRx.find(t)?.let { return Math.round(it.groupValues[1].toDouble() * 60).toInt() }
        minsRx.find(t)?.let { return it.groupValues[1].toInt() }
        return null
    }
    private const val WEEKENDS = 0b1100000
    private val targetRx = Regex("""^x(\d{1,3})$""")
    private val time24 = Regex("""^([01]?\d|2[0-3])[:.]([0-5]\d)$""")
    private val time12 = Regex("""^(1[0-2]|0?[1-9])(?:[:.]([0-5]\d))?(am|pm)$""")

    fun parseTime(token: String): LocalTime? {
        val t = token.lowercase()
        time24.find(t)?.let { return LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        time12.find(t)?.let {
            val h = it.groupValues[1].toInt() % 12 + if (it.groupValues[3] == "pm") 12 else 0
            return LocalTime.of(h, it.groupValues[2].toIntOrNull() ?: 0)
        }
        return null
    }

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

    private val dayMonth = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?$""")

    /** "today", "tmr", a weekday (this one or the next), or "14/11" (UK order; next one if past). */
    fun parseDate(word: String, today: LocalDate): LocalDate? {
        val lw = word.lowercase()
        return when {
            lw == "today" || lw == "tod" -> today
            lw in setOf("tmr", "tmrw", "tomorrow") -> today.plusDays(1)
            lw in weekdays -> today.with(TemporalAdjusters.nextOrSame(weekdays.getValue(lw)))
            else -> dayMonth.find(lw)?.let { m ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt()
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                runCatching {
                    val candidate = LocalDate.of(y ?: today.year, mo, d)
                    if (y == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }.getOrNull()
            }
        }
    }

    private fun priorityOf(lw: String): Priority? = when (lw) {
        "!!", "!high", "!h", "!1" -> Priority.HIGH
        "!low", "!l", "!3" -> Priority.LOW
        "!normal", "!n", "!2" -> Priority.NORMAL
        else -> null
    }

    fun parse(input: String, today: LocalDate): Parsed? {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        var date: LocalDate? = today
        var tag: String? = null
        var minutes: Int? = null
        var goalHint: String? = null
        var period: Period? = null
        var target = 1
        var recurring = false
        var time: LocalTime? = null
        var priority = Priority.NORMAL
        var deadline: LocalDate? = null
        var dateGiven = false
        val title = mutableListOf<String>()
        // Repeat: unit + every + days; FROM_DAY = "the weekday it starts on" (filled in at the end).
        var rUnit: Repeat.Unit? = null
        var rEvery = 1
        var rMask = 0

        /** Weekdays starting at words[j] ("mon,wed" or "mon wed fri"); returns mask and words used. */
        fun dayList(j: Int): Pair<Int, Int> {
            var mask = 0
            var k = j
            while (k < words.size) {
                val parts = words[k].lowercase().split(',').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { it !in weekdays }) break
                parts.forEach { mask = mask or Days.bit(weekdays.getValue(it)) }
                k++
            }
            return mask to (k - j)
        }

        var i = 0
        while (i < words.size) {
            val w = words[i]
            val lw = w.lowercase()
            val dateAllowed = i > 0
            val next = words.getOrNull(i + 1)
            // "by fri", "due 14/11": a deadline (consumes both words).
            if (dateAllowed && (lw == "by" || lw == "due") && next != null) {
                val dl = parseDate(next, today)
                if (dl != null) {
                    deadline = dl
                    i += 2
                    continue
                }
            }
            // Repeats: "daily", "weekdays", "weekly", "monthly", "every mon,wed", "every 2 weeks tue", "every 3 days".
            if (dateAllowed && rUnit == null) {
                when (lw) {
                    "daily", "everyday" -> { rUnit = Repeat.Unit.WEEK; rMask = Days.ALL; i++; continue }
                    "weekdays" -> { rUnit = Repeat.Unit.WEEK; rMask = Days.WEEKDAYS; i++; continue }
                    "weekends" -> { rUnit = Repeat.Unit.WEEK; rMask = WEEKENDS; i++; continue }
                    "weekly" -> { rUnit = Repeat.Unit.WEEK; i++; continue }
                    "fortnightly" -> { rUnit = Repeat.Unit.WEEK; rEvery = 2; i++; continue }
                    "monthly" -> { rUnit = Repeat.Unit.MONTH; i++; continue }
                }
                if (lw == "every" && next != null) {
                    val ln = next.lowercase()
                    val n = ln.toIntOrNull()?.takeIf { it in 1..52 }
                    val unitWord = words.getOrNull(i + 2)?.lowercase()
                    val (mask, used) = dayList(i + 1)
                    when {
                        ln == "day" -> { rUnit = Repeat.Unit.WEEK; rMask = Days.ALL; i += 2; continue }
                        ln == "weekday" || ln == "weekdays" -> { rUnit = Repeat.Unit.WEEK; rMask = Days.WEEKDAYS; i += 2; continue }
                        ln == "week" -> { rUnit = Repeat.Unit.WEEK; i += 2; continue }
                        ln == "fortnight" -> { rUnit = Repeat.Unit.WEEK; rEvery = 2; i += 2; continue }
                        ln == "month" -> { rUnit = Repeat.Unit.MONTH; i += 2; continue }
                        n != null && unitWord in setOf("day", "days") -> { rUnit = Repeat.Unit.DAY; rEvery = n; i += 3; continue }
                        n != null && unitWord in setOf("month", "months") -> { rUnit = Repeat.Unit.MONTH; rEvery = n; i += 3; continue }
                        n != null && unitWord in setOf("week", "weeks") -> {
                            rUnit = Repeat.Unit.WEEK; rEvery = n
                            val (m, u) = dayList(i + 3)
                            rMask = m
                            i += 3 + u
                            continue
                        }
                        mask != 0 -> { rUnit = Repeat.Unit.WEEK; rMask = mask; i += 1 + used; continue }
                    }
                }
            }
            if (dateAllowed && (lw == "someday" || parseDate(lw, today) != null)) dateGiven = true
            when {
                dateAllowed && (lw == "today" || lw == "tod") -> date = today
                dateAllowed && lw in setOf("tmr", "tmrw", "tomorrow") -> date = today.plusDays(1)
                dateAllowed && lw == "someday" -> date = null
                dateAllowed && lw in weekdays ->
                    date = today.with(TemporalAdjusters.nextOrSame(weekdays.getValue(lw)))
                dateAllowed && dayMonth.matches(lw) && parseDate(lw, today) != null -> date = parseDate(lw, today)
                lw == "@week" || lw == "@w" -> period = Period.WEEK
                lw == "@month" || lw == "@m" -> period = Period.MONTH
                lw == "!r" -> recurring = true
                lw.startsWith("#") && Tags.isValidName(lw.drop(1)) -> tag = lw.drop(1)
                lw.length > 1 && lw.startsWith("^") -> goalHint = lw.drop(1)
                targetRx.matches(lw) -> target = targetRx.find(lw)!!.groupValues[1].toInt().coerceAtLeast(1)
                parseDuration(lw) != null -> minutes = parseDuration(lw)
                parseTime(lw) != null -> time = parseTime(lw)
                priorityOf(lw) != null -> priority = priorityOf(lw)!!
                else -> title += w
            }
            i++
        }
        // "Essay 3h by fri": no day of its own, so the planner can pick one before Friday.
        if (deadline != null && !dateGiven) date = null

        val t = title.joinToString(" ").trim()
        if (t.isEmpty()) return null
        val repeat = rUnit?.let { u ->
            val from = date ?: today
            val mask = if (rMask != 0) rMask else Days.bit(from.dayOfWeek)
            if (u == Repeat.Unit.WEEK && rEvery == 1) Repeat.weekly(mask) else Repeat(u, rEvery, mask, from)
        }
        return if (period != null) {
            GoalDraft(t, period!!, target, recurring, tag)
        } else {
            TaskDraft(t, date, tag, minutes?.takeIf { it > 0 }, goalHint, time, priority, deadline, repeat)
        }
    }
}
