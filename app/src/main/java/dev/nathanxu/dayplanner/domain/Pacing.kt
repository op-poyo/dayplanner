package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Even pacing for count goals ("x5 @week"): by the end of day k of an n-day period you should
 * have done ceil(target * k / n). Missed days are absorbed into later quotas automatically.
 */
object Pacing {
    fun totalDays(period: Period, start: LocalDate): Int =
        ChronoUnit.DAYS.between(start, period.endAfter(start)).toInt()

    /** Days elapsed including [today], clamped to the period. */
    fun elapsedDays(period: Period, start: LocalDate, today: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(0, totalDays(period, start))

    fun expectedBy(target: Int, period: Period, start: LocalDate, today: LocalDate): Int {
        val n = totalDays(period, start)
        val k = elapsedDays(period, start, today)
        return (target * k + n - 1) / n // integer ceil
    }

    /** Units to schedule for [today], given what was done before today. */
    fun quotaToday(target: Int, period: Period, start: LocalDate, today: LocalDate, doneBeforeToday: Int): Int {
        val remaining = (target - doneBeforeToday).coerceAtLeast(0)
        return (expectedBy(target, period, start, today) - doneBeforeToday).coerceIn(0, remaining)
    }
}

enum class GoalMode { COUNT, STEPS, SINGLE }

/** Everything the UI needs to show a goal's percentage, pace and today's share. */
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
        /**
         * COUNT: target > 1, progress in units. STEPS: target 1 with linked step tasks, progress =
         * ticked steps / steps. SINGLE: target 1 with no steps, done once anything is logged.
         */
        fun of(
            target: Int, period: Period, start: LocalDate, today: LocalDate,
            doneUnits: Int, stepTotal: Int, stepDone: Int, todayUnits: Int, todayDoneUnits: Int,
        ): GoalStatus = when {
            target > 1 -> GoalStatus(
                GoalMode.COUNT, doneUnits, target,
                Pacing.expectedBy(target, period, start, today), todayUnits, todayDoneUnits,
            )
            stepTotal > 0 -> GoalStatus(GoalMode.STEPS, stepDone, stepTotal, null, todayUnits, todayDoneUnits)
            else -> GoalStatus(GoalMode.SINGLE, doneUnits.coerceAtMost(1), 1, null, todayUnits, todayDoneUnits)
        }
    }
}
