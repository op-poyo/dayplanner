package dev.nathanxu.dayplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test fun roundTripsNestedValues() {
        val v = linkedMapOf(
            "s" to "quote \" backslash \\ newline \n tab \t unicode é 😀 ctrl \u0001",
            "n" to 42L, "neg" to -7L, "big" to 1_700_000_000_000L, "d" to 1.5, "t" to true, "f" to false, "z" to null,
            "list" to listOf(1L, "two", null, listOf<Any?>(), mapOf<String, Any?>()),
            "obj" to linkedMapOf("a" to linkedMapOf("b" to listOf(true))),
        )
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(v, Json.parse(Json.write(v, indent = false)))
    }

    @Test fun parsesStandardJson() {
        assertEquals(mapOf("a" to listOf(1L, 2.5, -3L, 1.0e3), "b" to "x/y"), Json.parse(""" { "a" : [ 1, 2.5, -3, 1e3 ], "b": "x\/y" } """))
        assertEquals("é", Json.parse("\"\\u00e9\""))
    }

    @Test fun rejectsBadInput() {
        listOf("", "{", "[1,]x", "{\"a\" 1}", "\"open", "tru", "{\"a\":1}}", "[1 2]").forEach { bad ->
            assertThrows(bad, Json.ParseException::class.java) { Json.parse(bad) }
        }
    }

    @Test fun intsWriteWithoutFraction() {
        assertEquals("[3,2.5]", Json.write(listOf(3, 2.5), indent = false))
    }
}
