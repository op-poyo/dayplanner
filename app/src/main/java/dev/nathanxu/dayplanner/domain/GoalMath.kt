package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Goals measure; they never create to-dos.
 * - COUNT: [target] linked to-dos ticked per window ([per] = day / 3 days / week / month), or in
 *   total over start–end when [per] is null.
 * - CHECKLIST: its steps (linked one-off to-dos) done out of all of them.
 */
enum class GoalKind { COUNT, CHECKLIST }

data class GoalSpec(
    val kind: GoalKind,
    val target: Int,
    /** Window for "N per …"; null = N in total over [start]–[end]. */
    val per: Cadence?,
    val start: LocalDate,
    /** Inclusive; [Pacing.ONGOING_END] = no end. */
    val end: LocalDate,
)

/** A linked to-do as progress sees it. */
data class Linked(
    val done: Boolean,
    /** The day it counts for: the day it was ticked, else its date. */
    val on: LocalDate?,
    val units: Int = 1,
    /** Made by a repeating to-do (not a step). */
    val repeating: Boolean = false,
)

data class Window(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}

data class GoalState(
    val kind: GoalKind,
    val done: Int,
    val target: Int,
    /** Current window (or the whole range for totals/checklists); null before it starts. */
    val window: Window?,
    /** Days left in the window, today included. */
    val daysLeft: Int,
    /** How many short of an even pace (0 = on pace or ahead). */
    val behindBy: Int,
    /** Worth flagging: behind, or needs at least one a day from now on. */
    val needsAttention: Boolean,
    /** Earlier windows then this one (count, oldest first), for "3 2 3 1". Empty for totals. */
    val history: List<Int>,
    /** "this week", "by 30 Nov", "steps", "starts Mon 16 Nov". */
    val label: String,
) {
    val remaining: Int get() = (target - done).coerceAtLeast(0)
    val complete: Boolean get() = target > 0 && done >= target
    val fraction: Float get() = if (target <= 0) 0f else (done.toFloat() / target).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).roundToInt()
}

object GoalMath {
    private val dMMM = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val eeeDMMM = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** The window containing [day] for "per" goals, clipped to the goal's own start and end. */
    fun window(per: Cadence?, start: LocalDate, end: LocalDate, day: LocalDate): Window {
        val w = when (per) {
            null, Cadence.NONE -> Window(start, end)
            Cadence.DAILY -> Window(day, day)
            Cadence.THREE_DAY -> {
                val k = Math.floorDiv(ChronoUnit.DAYS.between(start, day), 3L)
                val s = start.plusDays(3 * k)
                Window(s, s.plusDays(2))
            }
            Cadence.WEEKLY -> {
                val mon = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Window(mon, mon.plusDays(6))
            }
            Cadence.MONTHLY -> Window(day.withDayOfMonth(1), day.withDayOfMonth(day.lengthOfMonth()))
        }
        return Window(maxOf(w.start, start), minOf(w.end, end))
    }

    fun state(spec: GoalSpec, linked: List<Linked>, today: LocalDate): GoalState {
        if (spec.kind == GoalKind.CHECKLIST) {
            val steps = linked.filter { !it.repeating }
            val done = steps.count { it.done }
            val all = Window(spec.start, spec.end)
            val ongoing = Pacing.isOngoing(spec.end)
            val left = if (ongoing) 0 else (ChronoUnit.DAYS.between(today, spec.end) + 1).toInt().coerceAtLeast(0)
            // Behind: fewer steps done than an even spread over the range would have by now.
            val behind = if (ongoing || steps.isEmpty() || today.isBefore(spec.start)) 0 else {
                val elapsed = ChronoUnit.DAYS.between(spec.start, today).toInt().coerceIn(0, all.days)
                (steps.size * elapsed / all.days - done).coerceAtLeast(0)
            }
            val open = steps.size - done
            return GoalState(
                GoalKind.CHECKLIST, done, steps.size, all, left, behind,
                needsAttention = open > 0 && !ongoing && (behind > 0 || open >= left.coerceAtLeast(1)),
                history = emptyList(),
                label = if (ongoing) "steps" else "by " + spec.end.format(dMMM),
            )
        }

        val target = spec.target.coerceAtLeast(1)
        if (today.isBefore(spec.start)) {
            return GoalState(GoalKind.COUNT, 0, target, null, 0, 0, false, emptyList(), "starts " + spec.start.format(eeeDMMM))
        }
        val day = minOf(today, spec.end)
        val w = window(spec.per, spec.start, spec.end, day)
        val count = { win: Window -> linked.filter { it.done && it.on != null && it.on in win }.sumOf { it.units } }
        val done = count(w)
        val daysLeft = (ChronoUnit.DAYS.between(today, w.end) + 1).toInt().coerceAtLeast(0)
        val elapsed = ChronoUnit.DAYS.between(w.start, today).toInt().coerceIn(0, w.days)
        val behind = (target * elapsed / w.days - done).coerceAtLeast(0)
        val remaining = (target - done).coerceAtLeast(0)

        val history = if (spec.per == null || spec.per == Cadence.NONE) emptyList() else {
            val wins = mutableListOf(w)
            while (wins.size < 6) {
                val prev = wins.first().start.minusDays(1)
                if (prev.isBefore(spec.start)) break
                wins.add(0, window(spec.per, spec.start, spec.end, prev))
            }
            wins.map(count)
        }
        val label = when (spec.per) {
            null, Cadence.NONE -> "by " + spec.end.format(dMMM)
            Cadence.DAILY -> "today"
            Cadence.THREE_DAY -> "these 3 days"
            Cadence.WEEKLY -> "this week"
            Cadence.MONTHLY -> "this month"
        }
        return GoalState(
            GoalKind.COUNT, done, target, w, daysLeft, behind,
            needsAttention = remaining > 0 && daysLeft > 0 && (behind > 0 || remaining >= daysLeft),
            history = history, label = label,
        )
    }

    /**
     * Days to suggest [count] sessions on, spread evenly over [days] (in order), at most one a
     * day, starting with the first (today, when behind).
     */
    fun suggestionDays(count: Int, days: List<LocalDate>): List<LocalDate> {
        if (count <= 0 || days.isEmpty()) return emptyList()
        if (count >= days.size) return days
        return (0 until count).map { i -> days[i * days.size / count] }
    }

    /** "3 a week", "15 by 30 Nov", "checklist · by 12 Dec". */
    fun describe(spec: GoalSpec): String = when {
        spec.kind == GoalKind.CHECKLIST -> if (Pacing.isOngoing(spec.end)) "Checklist" else "Checklist · by " + spec.end.format(dMMM)
        spec.per == null || spec.per == Cadence.NONE -> "${spec.target} by " + spec.end.format(dMMM)
        else -> "${spec.target} " + when (spec.per) {
            Cadence.DAILY -> "a day"
            Cadence.THREE_DAY -> "every 3 days"
            Cadence.WEEKLY -> "a week"
            else -> "a month"
        } + if (Pacing.isOngoing(spec.end)) "" else " until " + spec.end.format(dMMM)
    }
}
