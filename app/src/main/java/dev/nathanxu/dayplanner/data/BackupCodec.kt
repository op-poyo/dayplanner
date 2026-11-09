package dev.nathanxu.dayplanner.data

import dev.nathanxu.dayplanner.domain.Cadence
import dev.nathanxu.dayplanner.domain.Json
import dev.nathanxu.dayplanner.domain.Period
import dev.nathanxu.dayplanner.domain.ProtectedBlock
import dev.nathanxu.dayplanner.domain.Tag
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Everything a backup holds. */
data class BackupContents(
    val tasks: List<Task>,
    val goals: List<Goal>,
    val routines: List<Routine>,
    val tags: List<Tag>,
    val settings: SettingsState,
    val exportedAt: LocalDateTime?,
) {
    fun summary(): String = "${tasks.count { it.genKey?.startsWith("l:") != true }} to-dos, " +
        "${goals.size} long-term tasks, ${routines.size} routines, ${tags.size} tags"
}

/**
 * Backup file format (JSON, human-readable). Dates are ISO strings, times "HH:mm", enums by name.
 * Unknown fields are ignored and missing optional ones take their defaults, so older backups
 * still restore. Calendar ids (device-specific) are kept but checked on restore.
 */
object BackupCodec {
    const val FORMAT = 1
    const val APP = "DayPlanner"

    class BadBackup(message: String) : IllegalArgumentException(message)

    fun encode(c: BackupContents, now: LocalDateTime): String = Json.write(
        linkedMapOf(
            "app" to APP,
            "format" to FORMAT,
            "exportedAt" to now.withNano(0).toString(),
            "tasks" to c.tasks.map(::task),
            "goals" to c.goals.map(::goal),
            "routines" to c.routines.map(::routine),
            "tags" to c.tags.map { linkedMapOf("key" to it.key, "name" to it.name, "color" to it.color) },
            "settings" to settings(c.settings),
        )
    )

    fun decode(text: String): BackupContents {
        val root = try {
            Json.parse(text) as? Map<*, *> ?: throw BadBackup("This isn't a DayPlanner backup.")
        } catch (e: Json.ParseException) {
            throw BadBackup("This file isn't a DayPlanner backup (not valid JSON).")
        }
        if (root["app"] != APP) throw BadBackup("This isn't a DayPlanner backup.")
        val format = (root["format"] as? Number)?.toInt() ?: throw BadBackup("Backup has no format number.")
        if (format > FORMAT) throw BadBackup("This backup is from a newer DayPlanner. Update the app first.")
        return BackupContents(
            tasks = list(root, "tasks").mapIndexed { i, m -> wrap("to-do ${i + 1}") { task(m) } },
            goals = list(root, "goals").mapIndexed { i, m -> wrap("long-term task ${i + 1}") { goal(m) } },
            routines = list(root, "routines").mapIndexed { i, m -> wrap("routine ${i + 1}") { routine(m) } },
            tags = list(root, "tags").mapIndexed { i, m ->
                wrap("tag ${i + 1}") { Tag(m.str("key"), m.str("name"), m.int("color")) }
            },
            settings = (root["settings"] as? Map<*, *>)?.let { wrap("settings") { settings(it) } } ?: SettingsState(),
            exportedAt = (root["exportedAt"] as? String)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        )
    }

    // ---------- entities ----------

    private fun task(t: Task) = linkedMapOf<String, Any?>(
        "id" to t.id, "title" to t.title, "date" to t.date?.toString(), "category" to t.category,
        "minutes" to t.minutes, "time" to t.time?.hm(), "goalId" to t.goalId, "routineId" to t.routineId,
        "units" to t.units, "remindBefore" to t.remindBefore, "priority" to t.priority,
        "deadline" to t.deadline?.toString(), "place" to t.place, "plannedEvents" to t.plannedEvents,
        "genKey" to t.genKey, "done" to t.done, "doneAt" to t.doneAt, "createdAt" to t.createdAt,
    )

    private fun task(m: Map<*, *>) = Task(
        id = m.long("id"), title = m.str("title"), date = m.date("date"), category = m.optStr("category") ?: "OTHER",
        minutes = m.optInt("minutes"), time = m.time("time"), goalId = m.optLong("goalId"), routineId = m.optLong("routineId"),
        units = m.optInt("units") ?: 1, remindBefore = m.optInt("remindBefore"), priority = m.optStr("priority"),
        deadline = m.date("deadline"), place = m.optStr("place"), plannedEvents = m.optStr("plannedEvents"),
        genKey = m.optStr("genKey"), done = m["done"] == true, doneAt = m.optLong("doneAt"),
        createdAt = m.optLong("createdAt") ?: 0L,
    )

    private fun goal(g: Goal) = linkedMapOf<String, Any?>(
        "id" to g.id, "title" to g.title, "period" to g.period.name, "periodStart" to g.periodStart.toString(),
        "target" to g.target, "recurring" to g.recurring, "archived" to g.archived, "category" to g.category,
        "split" to g.split, "endDate" to g.endDate?.toString(), "cadence" to g.cadence?.name, "priority" to g.priority,
    )

    private fun goal(m: Map<*, *>) = Goal(
        id = m.long("id"), title = m.str("title"),
        period = m.enumOf("period") { Period.valueOf(it) } ?: throw BadBackup("no period"),
        periodStart = m.date("periodStart") ?: throw BadBackup("no start date"),
        target = m.optInt("target") ?: 1, recurring = m["recurring"] == true, archived = m["archived"] == true,
        category = m.optStr("category") ?: "OTHER", split = m["split"] != false, endDate = m.date("endDate"),
        cadence = m.enumOf("cadence") { Cadence.valueOf(it) }, priority = m.optStr("priority"),
    )

    private fun routine(r: Routine) = linkedMapOf<String, Any?>(
        "id" to r.id, "title" to r.title, "category" to r.category, "minutes" to r.minutes, "daysMask" to r.daysMask,
        "active" to r.active, "time" to r.time?.hm(), "syncToCalendar" to r.syncToCalendar,
        "calendarEventId" to r.calendarEventId, "remind" to r.remind,
    )

    private fun routine(m: Map<*, *>) = Routine(
        id = m.long("id"), title = m.str("title"), category = m.optStr("category") ?: "OTHER", minutes = m.optInt("minutes"),
        daysMask = m.int("daysMask"), active = m["active"] != false, time = m.time("time"),
        syncToCalendar = m["syncToCalendar"] != false, calendarEventId = m.optLong("calendarEventId"),
        remind = m["remind"] as? Boolean,
    )

    // ---------- settings (no calendar ids: those belong to one phone) ----------

    private fun settings(s: SettingsState) = linkedMapOf<String, Any?>(
        "accounts" to s.accounts?.sorted(), "home" to s.home, "travelMode" to s.travelMode, "bufferMinutes" to s.bufferMinutes,
        "leaveAlerts" to s.leaveAlerts, "morningSummary" to s.morningSummary, "morningTime" to s.morningTime.hm(),
        "remindTodos" to s.remindTodos, "reminderLead" to s.reminderLead,
        "workStart" to s.workStart.hm(), "workEnd" to s.workEnd.hm(), "breakMinutes" to s.breakMinutes,
        "defaultMinutes" to s.defaultMinutes,
        "protectedBlocks" to s.protectedBlocks.map {
            linkedMapOf("days" to it.daysMask, "start" to it.start.hm(), "end" to it.end.hm(), "tag" to it.tag, "label" to it.label)
        },
        "places" to s.places.map { linkedMapOf("name" to it.name, "address" to it.address) },
        "weeklyReview" to s.weeklyReview, "reviewDay" to s.reviewDay.name, "reviewTime" to s.reviewTime.hm(),
    )

    private fun settings(m: Map<*, *>): SettingsState {
        val d = SettingsState()
        return d.copy(
            accounts = (m["accounts"] as? List<*>)?.filterIsInstance<String>()?.toSet(),
            home = m.optStr("home"),
            travelMode = m.optStr("travelMode") ?: d.travelMode,
            bufferMinutes = m.optInt("bufferMinutes") ?: d.bufferMinutes,
            leaveAlerts = m["leaveAlerts"] as? Boolean ?: d.leaveAlerts,
            morningSummary = m["morningSummary"] as? Boolean ?: d.morningSummary,
            morningTime = m.time("morningTime") ?: d.morningTime,
            remindTodos = m["remindTodos"] as? Boolean ?: d.remindTodos,
            reminderLead = m.optInt("reminderLead") ?: d.reminderLead,
            workStart = m.time("workStart") ?: d.workStart,
            workEnd = m.time("workEnd") ?: d.workEnd,
            breakMinutes = m.optInt("breakMinutes") ?: d.breakMinutes,
            defaultMinutes = m.optInt("defaultMinutes") ?: d.defaultMinutes,
            protectedBlocks = (m["protectedBlocks"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map {
                ProtectedBlock(it.int("days"), it.time("start") ?: throw BadBackup("no start"), it.time("end") ?: throw BadBackup("no end"), it.str("tag"), it.str("label"))
            } ?: d.protectedBlocks,
            places = (m["places"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { SavedPlace(it.str("name"), it.str("address")) } ?: d.places,
            weeklyReview = m["weeklyReview"] as? Boolean ?: d.weeklyReview,
            reviewDay = m.enumOf("reviewDay") { DayOfWeek.valueOf(it) } ?: d.reviewDay,
            reviewTime = m.time("reviewTime") ?: d.reviewTime,
        )
    }

    // ---------- helpers ----------

    private fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: BadBackup) {
        throw BadBackup("Backup is damaged: $what — ${e.message}")
    } catch (e: RuntimeException) {
        throw BadBackup("Backup is damaged: $what — ${e.message ?: e.javaClass.simpleName}")
    }

    private fun list(root: Map<*, *>, key: String): List<Map<*, *>> = when (val v = root[key]) {
        null -> emptyList()
        is List<*> -> v.map { it as? Map<*, *> ?: throw BadBackup("Backup is damaged: $key has a non-object entry") }
        else -> throw BadBackup("Backup is damaged: $key isn't a list")
    }

    // 24:00 is how an end-of-day LocalTime.MAX is stored.
    private fun LocalTime.hm(): String = if (this == LocalTime.MAX) "24:00" else "%02d:%02d".format(hour, minute)

    private fun Map<*, *>.str(k: String): String = this[k] as? String ?: throw BadBackup("no $k")
    private fun Map<*, *>.optStr(k: String): String? = this[k] as? String
    private fun Map<*, *>.long(k: String): Long = (this[k] as? Number)?.toLong() ?: throw BadBackup("no $k")
    private fun Map<*, *>.optLong(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<*, *>.int(k: String): Int = (this[k] as? Number)?.toInt() ?: throw BadBackup("no $k")
    private fun Map<*, *>.optInt(k: String): Int? = (this[k] as? Number)?.toInt()
    private fun Map<*, *>.date(k: String): LocalDate? = (this[k] as? String)?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw BadBackup("bad $k “$it”") }
    }
    private fun Map<*, *>.time(k: String): LocalTime? = (this[k] as? String)?.let {
        if (it == "24:00") return LocalTime.MAX
        runCatching { LocalTime.parse(it) }.getOrElse { throw BadBackup("bad $k “$it”") }
    }
    private fun <E> Map<*, *>.enumOf(k: String, of: (String) -> E): E? = (this[k] as? String)?.let {
        runCatching { of(it) }.getOrElse { throw BadBackup("unknown $k “$it”") }
    }
}
