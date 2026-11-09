package dev.nathanxu.dayplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A to-do as the review sees it. */
data class ReviewTask(
    val id: Long,
    val title: String,
    val date: LocalDate?,
    val done: Boolean,
    /** Day it was ticked (from doneAt), if done. */
    val doneOn: LocalDate?,
    val tag: String,
    val minutes: Int?,
    val routineId: Long? = null,
    val deadline: LocalDate? = null,
    /** A +1 log: counts for its long-term task, isn't a to-do. */
    val isLog: Boolean = false,
)

data class RoutineInput(
    val id: Long, val title: String, val daysMask: Int, val active: Boolean,
    /** Its repeat rule; null = weekly on [daysMask]. */
    val repeat: Repeat? = null,
)

data class TagTotal(val tag: String, val done: Int, val minutes: Int)
data class RoutineScore(val id: Long, val title: String, val done: Int, val expected: Int)

data class WeekSummary(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val done: Int,
    val doneMinutes: Int,
    /** Done per day, Monday first. */
    val perDay: List<Int>,
    val byTag: List<TagTotal>,
    val routines: List<RoutineScore>,
    /** Open to-dos from this week's past days, or past their deadline. */
    val slipped: List<ReviewTask>,
    val nextWeekCount: Int,
    /** Open to-dos due next week, soonest first. */
    val nextWeekDeadlines: List<ReviewTask>,
) {
    val busiestDay: DayOfWeek? get() = perDay.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.let { DayOfWeek.of(it.index + 1) }
}

object WeeklyReview {
    fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Summary of the Monday–Sunday week containing [today] (or the one starting [weekStart]),
     * counting only up to [today] for routines and slipped to-dos.
     */
    fun summarise(
        tasks: List<ReviewTask>,
        routines: List<RoutineInput>,
        today: LocalDate,
        weekStart: LocalDate = weekOf(today),
    ): WeekSummary {
        val weekEnd = weekStart.plusDays(6)
        val inWeek: (LocalDate?) -> Boolean = { it != null && !it.isBefore(weekStart) && !it.isAfter(weekEnd) }
        val upTo = if (today.isAfter(weekEnd)) weekEnd else today

        val doneThisWeek = tasks.filter { it.done && !it.isLog && inWeek(it.doneOn ?: it.date) }
        val perDay = (0..6).map { i -> doneThisWeek.count { (it.doneOn ?: it.date) == weekStart.plusDays(i.toLong()) } }
        val byTag = doneThisWeek.groupBy { it.tag }
            .map { (tag, list) -> TagTotal(tag, list.size, list.sumOf { it.minutes ?: 0 }) }
            .sortedWith(compareBy({ -it.done }, { -it.minutes }, { it.tag }))

        val routineScores = routines.filter { it.active }.mapNotNull { r ->
            val expected = (0..6).map { weekStart.plusDays(it.toLong()) }
                .count { !it.isAfter(upTo) && (r.repeat ?: Repeat.weekly(r.daysMask)).occursOn(it) }
            if (expected == 0) return@mapNotNull null
            val done = tasks.count { it.routineId == r.id && it.done && inWeek(it.date) && !it.date!!.isAfter(upTo) }
            RoutineScore(r.id, r.title, done.coerceAtMost(expected), expected)
        }

        val slipped = tasks.filter { t ->
            !t.done && !t.isLog && t.routineId == null &&
                ((t.date != null && !t.date.isBefore(weekStart) && t.date.isBefore(today)) ||
                    (t.deadline != null && t.deadline.isBefore(today)))
        }.sortedWith(compareBy({ it.deadline ?: it.date }, { it.id }))

        val nextStart = weekEnd.plusDays(1)
        val nextEnd = nextStart.plusDays(6)
        val inNext: (LocalDate?) -> Boolean = { it != null && !it.isBefore(nextStart) && !it.isAfter(nextEnd) }
        val open = tasks.filter { !it.done && !it.isLog }
        return WeekSummary(
            weekStart = weekStart,
            weekEnd = weekEnd,
            done = doneThisWeek.size,
            doneMinutes = doneThisWeek.sumOf { it.minutes ?: 0 },
            perDay = perDay,
            byTag = byTag,
            routines = routineScores,
            slipped = slipped,
            nextWeekCount = open.count { inNext(it.date) && it.routineId == null },
            nextWeekDeadlines = open.filter { inNext(it.deadline) }.sortedWith(compareBy({ it.deadline }, { it.id })),
        )
    }

    /** Next time the review is due at or after [now]'s day: [day] at [hour]:[minute]. */
    fun nextReviewDate(today: LocalDate, day: DayOfWeek): LocalDate = today.with(TemporalAdjusters.nextOrSame(day))
}
