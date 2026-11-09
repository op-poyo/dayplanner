package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** How a long-term task is broken into to-dos. */
enum class Cadence(val label: String, val short: String) {
    DAILY("Daily", "today"),
    THREE_DAY("Every 3 days", "these 3 days"),
    WEEKLY("Weekly", "this week"),
    MONTHLY("Monthly", "this month"),
    NONE("No breakdown", ""),
}

/**
 * Even pacing over an inclusive [start, end] range: by the end of day k of n you should have
 * done ceil(target·k/n). A breakdown bucket (today / these 3 days / this week / this month) asks
 * for whatever gets you on pace by the bucket's last day, so missed work rolls into the next ask.
 */
object Pacing {
    fun totalDays(start: LocalDate, end: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(1)

    /** Days elapsed from [start] up to and including [date], clamped to the range. */
    fun elapsedDays(start: LocalDate, end: LocalDate, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, date) + 1).toInt().coerceIn(0, totalDays(start, end))

    fun expectedBy(target: Int, start: LocalDate, end: LocalDate, date: LocalDate): Int {
        val n = totalDays(start, end)
        val k = elapsedDays(start, end, date)
        return (target * k + n - 1) / n // integer ceil
    }

    /** Last day of the breakdown bucket containing [today], clamped to [end]. */
    fun bucketEnd(cadence: Cadence, start: LocalDate, end: LocalDate, today: LocalDate): LocalDate {
        val raw = when (cadence) {
            Cadence.DAILY -> today
            Cadence.THREE_DAY -> {
                val since = ChronoUnit.DAYS.between(start, today).coerceAtLeast(0)
                start.plusDays((since / 3 + 1) * 3 - 1)
            }
            Cadence.WEEKLY -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            Cadence.MONTHLY -> today.withDayOfMonth(today.lengthOfMonth())
            Cadence.NONE -> end
        }
        return if (raw.isAfter(end)) end else raw
    }

    /** End date that marks an ongoing task (no end). */
    val ONGOING_END: LocalDate = LocalDate.of(9999, 12, 31)

    fun isOngoing(end: LocalDate): Boolean = end == ONGOING_END

    /** First day of the breakdown bucket containing [today]. */
    fun bucketStart(cadence: Cadence, start: LocalDate, today: LocalDate): LocalDate = when (cadence) {
        Cadence.DAILY -> today
        Cadence.THREE_DAY -> start.plusDays(ChronoUnit.DAYS.between(start, today).coerceAtLeast(0) / 3 * 3)
        Cadence.WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Cadence.MONTHLY -> today.withDayOfMonth(1)
        Cadence.NONE -> start
    }

    /**
     * What today's to-do should still ask for, given everything already done today.
     * Finite task: the pace quota minus today's progress. Ongoing task: [target] per bucket minus
     * what's been done in the bucket so far (including today).
     */
    fun remainingToday(
        target: Int, start: LocalDate, end: LocalDate, cadence: Cadence, today: LocalDate,
        doneBeforeToday: Int, doneToday: Int, doneInBucket: Int,
    ): Int = if (isOngoing(end)) {
        if (today.isBefore(start)) 0 else (target - doneInBucket).coerceAtLeast(0)
    } else {
        (quotaToday(target, start, end, cadence, today, doneBeforeToday) - doneToday).coerceAtLeast(0)
    }

    /** Units to ask for in today's to-do. 0 outside the range or when already on pace. */
    fun quotaToday(
        target: Int, start: LocalDate, end: LocalDate, cadence: Cadence,
        today: LocalDate, doneBeforeToday: Int,
    ): Int {
        if (today.isBefore(start) || today.isAfter(end)) return 0
        val remaining = (target - doneBeforeToday).coerceAtLeast(0)
        val due = expectedBy(target, start, end, bucketEnd(cadence, start, end, today))
        return (due - doneBeforeToday).coerceIn(0, remaining)
    }
}

enum class GoalMode { COUNT, STEPS, SINGLE }

/** Everything the UI needs to show a plan's percentage, pace and today's share. */
data class GoalStatus(
    val mode: GoalMode,
    val done: Int,
    val total: Int,
    val expectedNow: Int?,
    val todayPlanned: Int,
    val todayDone: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).roundToInt()
    val complete: Boolean get() = total > 0 && done >= total

    /** Positive = ahead of pace, negative = behind. Null when pacing doesn't apply. */
    val paceDelta: Int? get() = expectedNow?.let { done - it }

    companion object {
        /** Ongoing task: progress is this bucket's count against the per-bucket target. */
        fun ongoing(target: Int, doneInBucket: Int, todayUnits: Int, todayDoneUnits: Int): GoalStatus =
            GoalStatus(GoalMode.COUNT, doneInBucket, target.coerceAtLeast(1), null, todayUnits, todayDoneUnits)

        /**
         * COUNT: target > 1, progress in units. STEPS: target 1 with linked step tasks, progress =
         * ticked steps / steps. SINGLE: target 1 with no steps, done once anything is logged.
         */
        fun of(
            target: Int, start: LocalDate, end: LocalDate, today: LocalDate,
            doneUnits: Int, stepTotal: Int, stepDone: Int, todayUnits: Int, todayDoneUnits: Int,
        ): GoalStatus = when {
            target > 1 -> GoalStatus(
                GoalMode.COUNT, doneUnits, target,
                Pacing.expectedBy(target, start, end, today), todayUnits, todayDoneUnits,
            )
            stepTotal > 0 -> GoalStatus(GoalMode.STEPS, stepDone, stepTotal, null, todayUnits, todayDoneUnits)
            else -> GoalStatus(GoalMode.SINGLE, doneUnits.coerceAtMost(1), 1, null, todayUnits, todayDoneUnits)
        }
    }
}
