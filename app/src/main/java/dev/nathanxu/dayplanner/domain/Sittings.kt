package dev.nathanxu.dayplanner.domain

import java.time.LocalDateTime

/** One planned block of a split to-do. */
data class Sitting(val start: LocalDateTime, val minutes: Int)

/** Stored as text on the to-do: "2026-11-10T14:00/60;2026-11-11T09:30/45". Bad parts are skipped. */
object Sittings {
    fun encode(list: List<Sitting>): String? =
        list.takeIf { it.size > 1 }?.joinToString(";") { "${it.start.withSecond(0).withNano(0)}/${it.minutes}" }

    fun decode(s: String?): List<Sitting> = s.orEmpty().split(';').mapNotNull { part ->
        val (at, min) = part.split('/').takeIf { it.size == 2 } ?: return@mapNotNull null
        val start = runCatching { LocalDateTime.parse(at.trim()) }.getOrNull() ?: return@mapNotNull null
        val m = min.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        Sitting(start, m)
    }.sortedBy { it.start }
}
