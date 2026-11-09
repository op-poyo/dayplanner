package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodoSortTest {
    private val d = LocalDate.of(2026, 11, 9)
    private val items = listOf(
        TodoSort.Fields(1, d, LocalTime.of(14, 0), Priority.NORMAL, null, "Study"),
        TodoSort.Fields(2, d, null, Priority.HIGH, d.plusDays(4), "gym"),
        TodoSort.Fields(3, null, null, Priority.HIGH, d.plusDays(1), null),
        TodoSort.Fields(4, d, LocalTime.of(9, 0), Priority.LOW, null, "Study"),
        TodoSort.Fields(5, d.plusDays(1), null, Priority.NORMAL, null, null),
    )

    @Test fun flatOrders() {
        assertEquals(listOf(4L, 1L, 2L, 5L, 3L), TodoSort.sorted(items, TodoSort.DATE) { it }.map { it.id })
        // High first, soonest deadline first within it.
        assertEquals(listOf(3L, 2L, 1L, 5L, 4L), TodoSort.sorted(items, TodoSort.PRIORITY) { it }.map { it.id })
        // Tags A–Z ignoring case, untagged last.
        assertEquals(listOf(2L, 4L, 1L, 5L, 3L), TodoSort.sorted(items, TodoSort.TAG) { it }.map { it.id })
    }

    @Test fun sections() {
        val byP = TodoSort.sections(items, TodoSort.PRIORITY, d) { it }
        assertEquals(listOf("High", "Normal", "Low"), byP.map { it.first })
        assertEquals(listOf(3L, 2L), byP[0].second.map { it.id })
        val byTag = TodoSort.sections(items, TodoSort.TAG, d) { it }
        assertEquals(listOf("gym", "Study", "No tag"), byTag.map { it.first })
        val byDate = TodoSort.sections(items, TodoSort.DATE, d) { it }
        assertEquals(listOf("Today", "Tomorrow", "Someday"), byDate.map { it.first })
        assertEquals(listOf(4L, 1L, 2L), byDate[0].second.map { it.id })
        assertEquals(emptyList<Any>(), TodoSort.sections(emptyList<TodoSort.Fields>(), TodoSort.PRIORITY, d) { it })
    }

    @Test fun parsesStoredName() {
        assertEquals(TodoSort.TAG, TodoSort.of("TAG"))
        assertEquals(TodoSort.DATE, TodoSort.of(null))
        assertEquals(TodoSort.DATE, TodoSort.of("nonsense"))
    }
}
