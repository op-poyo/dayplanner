package dev.nathanxu.dayplanner.domain

/**
 * Minimal JSON for backups: values are null, Boolean, Long, Double, String, List<Any?> and
 * Map<String, Any?>. Pure Kotlin so backups can be tested off-device (Android's org.json is a stub
 * in unit tests).
 */
object Json {
    fun write(value: Any?, indent: Boolean = true): String = StringBuilder().also { write(it, value, if (indent) 0 else -1) }.toString()

    private fun write(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) (if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()) else "null")
            is Float -> write(sb, v.toDouble(), level)
            is String -> str(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                v.entries.forEachIndexed { i, (k, x) ->
                    if (i > 0) sb.append(',')
                    if (level >= 0) nl(sb, level + 1)
                    str(sb, k.toString())
                    sb.append(if (level >= 0) ": " else ":")
                    write(sb, x, if (level >= 0) level + 1 else -1)
                }
                nl(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    if (level >= 0) nl(sb, level + 1)
                    write(sb, x, if (level >= 0) level + 1 else -1)
                }
                nl(sb, level)
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun nl(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    class ParseException(message: String) : IllegalArgumentException(message)

    /** Parses [text]; numbers without a fraction or exponent come back as Long. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseException("Unexpected text at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun fail(what: String): Nothing = throw ParseException("$what at $i")

        fun value(depth: Int): Any? {
            if (depth > 64) fail("Too deeply nested")
            if (i >= s.length) fail("Unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> number()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Bad literal")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected key")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') fail("Expected :")
                i++
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("Unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("Expected , or }")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out += value(depth + 1)
                ws()
                if (i >= s.length) fail("Unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("Expected , or ]")
                }
            }
        }

        fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad \\u escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("Bad \\u escape"))
                                i += 4
                            }
                            else -> fail("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") fail("Unexpected character")
            return if (t.any { it in ".eE" }) t.toDoubleOrNull() ?: fail("Bad number") else t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number")
        }
    }
}
