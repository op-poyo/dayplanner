package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class SittingsTest {
    private val a = Sitting(LocalDateTime.of(2026, 11, 10, 14, 0), 120)
    private val b = Sitting(LocalDateTime.of(2026, 11, 11, 9, 30), 60)

    @Test fun roundTrip() {
        val s = Sittings.encode(listOf(a, b))
        assertEquals("2026-11-10T14:00/120;2026-11-11T09:30/60", s)
        assertEquals(listOf(a, b), Sittings.decode(s))
    }

    @Test fun singleIsNotStoredAndJunkSkipped() {
        assertNull(Sittings.encode(listOf(a)))
        assertEquals(listOf(b), Sittings.decode("rubbish;2026-11-11T09:30/60;2026-13-01T00:00/5;2026-11-12T10:00/0"))
        assertEquals(emptyList<Sitting>(), Sittings.decode(null))
    }
}
