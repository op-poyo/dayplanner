package dev.nathanxu.dayplanner.domain

import java.time.LocalDate
import java.time.LocalTime

/** How to-do lists are ordered: by date (default), priority or tag. */
enum class TodoSort(val label: String) {
    DATE("Date"), PRIORITY("Priority"), TAG("Tag");

    /** What sorting needs from a to-do. */
    data class Fields(
        val id: Long,
        val date: LocalDate?,
        val time: LocalTime?,
        val priority: Priority,
        val deadline: LocalDate?,
        /** The tag's display name; null = no tag. */
        val tag: String?,
    )

    companion object {
        fun of(name: String?): TodoSort = entries.firstOrNull { it.name == name } ?: DATE

        // Undated last, untimed after timed, then id for a stable order.
        private val byWhen = compareBy<Fields>(
            { it.date == null }, { it.date }, { it.time == null }, { it.time }, { it.id },
        )
        private val byUrgency = compareBy<Fields>({ it.deadline == null }, { it.deadline }).then(byWhen)

        /** A flat order (Today's list): priority or tag first, then when. */
        fun <T> sorted(items: List<T>, mode: TodoSort, f: (T) -> Fields): List<T> = when (mode) {
            DATE -> items.sortedWith(compareBy<T, Fields>(byWhen) { f(it) })
            PRIORITY -> items.sortedWith(compareBy<T> { f(it).priority.rank }.thenBy(byUrgency) { f(it) })
            TAG -> items.sortedWith(compareBy<T>({ f(it).tag == null }, { f(it).tag?.lowercase() }).thenBy(byWhen) { f(it) })
        }

        /**
         * Sections (All to-dos): by date uses [TodoGroups]; by priority High → Normal → Low, soonest
         * deadline first; by tag A–Z with untagged last. Empty sections are left out.
         */
        fun <T> sections(items: List<T>, mode: TodoSort, today: LocalDate, f: (T) -> Fields): List<Pair<String, List<T>>> = when (mode) {
            DATE -> TodoGroups.group(sorted(items, DATE, f), today) { f(it).date }
            PRIORITY -> Priority.entries.sortedBy { it.rank }.mapNotNull { p ->
                items.filter { f(it).priority == p }.takeIf { it.isNotEmpty() }?.let { list ->
                    p.label to list.sortedWith(compareBy<T, Fields>(byUrgency) { f(it) })
                }
            }
            TAG -> items.groupBy { f(it).tag }
                .entries.sortedWith(compareBy({ it.key == null }, { it.key?.lowercase() }))
                .map { (tag, list) -> (tag ?: "No tag") to list.sortedWith(compareBy<T, Fields>(byWhen) { f(it) }) }
        }
    }
}
