package dev.nathanxu.dayplanner.data

import android.content.Context
import dev.nathanxu.dayplanner.domain.ProtectedBlock
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.LocalTime

/** Tiny SharedPreferences-backed store; observable so the UI updates on change. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    val current: SettingsState get() = _state.value

    private fun read(): SettingsState {
        val accounts: Set<String>? = when {
            sp.contains(KEY_ACCOUNTS) -> sp.getStringSet(KEY_ACCOUNTS, emptySet())!!.toSet()
            // v1.2 stored one account.
            sp.getString(KEY_ACCOUNT_V12, null) != null -> setOf(sp.getString(KEY_ACCOUNT_V12, null)!!)
            else -> null
        }
        return SettingsState(
            accounts = accounts,
            hiddenCalendars = sp.getStringSet(KEY_HIDDEN, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet(),
            routineCalendarId = sp.getLong(KEY_CAL, -1L).takeIf { it >= 0 },
            home = sp.getString(KEY_HOME, null)?.takeIf { it.isNotBlank() },
            travelMode = sp.getString(KEY_MODE, "TRANSIT") ?: "TRANSIT",
            bufferMinutes = sp.getInt(KEY_BUFFER, 10),
            leaveAlerts = sp.getBoolean(KEY_LEAVE, true),
            morningSummary = sp.getBoolean(KEY_MORNING, true),
            morningTime = sp.getInt(KEY_MORNING_AT, 7 * 60 + 30).let { LocalTime.of(it / 60, it % 60) },
            remindTodos = sp.getBoolean(KEY_REMIND, true),
            reminderLead = sp.getInt(KEY_DEFAULT_REMINDER, -1).takeIf { it >= 0 },
            workStart = time(sp.getInt(KEY_WORK_START, 8 * 60)),
            workEnd = time(sp.getInt(KEY_WORK_END, 22 * 60)),
            breakMinutes = sp.getInt(KEY_BREAK, 10),
            defaultMinutes = sp.getInt(KEY_DEFAULT_MIN, 30),
            protectedBlocks = runCatching { readProtected(sp.getString(KEY_PROTECTED, "[]")!!) }.getOrDefault(emptyList()),
            places = runCatching { readPlaces(sp.getString(KEY_PLACES, "[]")!!) }.getOrDefault(emptyList()),
            weeklyReview = sp.getBoolean(KEY_REVIEW, true),
            reviewDay = runCatching { DayOfWeek.valueOf(sp.getString(KEY_REVIEW_DAY, "SUNDAY")!!) }.getOrDefault(DayOfWeek.SUNDAY),
            reviewTime = time(sp.getInt(KEY_REVIEW_AT, 18 * 60)),
            onboarded = sp.getBoolean(KEY_ONBOARDED, false),
            todoSort = sp.getString(KEY_SORT, "DATE") ?: "DATE",
            swipeTipSeen = sp.getBoolean(KEY_SWIPE_TIP, false),
        )
    }

    private fun time(min: Int): LocalTime = if (min >= 24 * 60) LocalTime.MAX else LocalTime.of(min / 60, min % 60)
    private fun min(t: LocalTime): Int = if (t == LocalTime.MAX) 24 * 60 else t.hour * 60 + t.minute

    private fun readProtected(json: String): List<ProtectedBlock> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ProtectedBlock(o.getInt("days"), time(o.getInt("start")), time(o.getInt("end")), o.getString("tag"), o.getString("label"))
        }
    }

    private fun writeProtected(list: List<ProtectedBlock>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("days", it.daysMask).put("start", min(it.start)).put("end", min(it.end)).put("tag", it.tag).put("label", it.label))
        }
    }.toString()

    private fun readPlaces(json: String): List<SavedPlace> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { SavedPlace(it.getString("name"), it.getString("address")) } }
    }

    private fun writePlaces(list: List<SavedPlace>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("address", it.address)) }
    }.toString()

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        sp.edit().apply {
            if (next.accounts == null) remove(KEY_ACCOUNTS) else putStringSet(KEY_ACCOUNTS, next.accounts)
            remove(KEY_ACCOUNT_V12)
            putStringSet(KEY_HIDDEN, next.hiddenCalendars.map { it.toString() }.toSet())
            putLong(KEY_CAL, next.routineCalendarId ?: -1L)
            putString(KEY_HOME, next.home)
            putString(KEY_MODE, next.travelMode)
            putInt(KEY_BUFFER, next.bufferMinutes)
            putBoolean(KEY_LEAVE, next.leaveAlerts)
            putBoolean(KEY_MORNING, next.morningSummary)
            putInt(KEY_MORNING_AT, next.morningTime.hour * 60 + next.morningTime.minute)
            putBoolean(KEY_REMIND, next.remindTodos)
            putInt(KEY_DEFAULT_REMINDER, next.reminderLead ?: -1)
            putInt(KEY_WORK_START, min(next.workStart))
            putInt(KEY_WORK_END, min(next.workEnd))
            putInt(KEY_BREAK, next.breakMinutes)
            putInt(KEY_DEFAULT_MIN, next.defaultMinutes)
            putString(KEY_PROTECTED, writeProtected(next.protectedBlocks))
            putString(KEY_PLACES, writePlaces(next.places))
            putBoolean(KEY_REVIEW, next.weeklyReview)
            putString(KEY_REVIEW_DAY, next.reviewDay.name)
            putInt(KEY_REVIEW_AT, min(next.reviewTime))
            putBoolean(KEY_ONBOARDED, next.onboarded)
            putString(KEY_SORT, next.todoSort)
            putBoolean(KEY_SWIPE_TIP, next.swipeTipSeen)
        }.apply()
        _state.value = next
    }

    private companion object {
        const val KEY_ACCOUNT_V12 = "account_name"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_HIDDEN = "hidden_calendars"
        const val KEY_CAL = "routine_calendar_id"
        const val KEY_HOME = "home"
        const val KEY_MODE = "travel_mode"
        const val KEY_BUFFER = "buffer_minutes"
        const val KEY_LEAVE = "leave_alerts"
        const val KEY_MORNING = "morning_summary"
        const val KEY_MORNING_AT = "morning_at"
        const val KEY_REMIND = "remind_todos"
        // v1.8.1: new key, so the old "at the time" default becomes off.
        const val KEY_DEFAULT_REMINDER = "default_reminder"
        const val KEY_WORK_START = "work_start"
        const val KEY_WORK_END = "work_end"
        const val KEY_BREAK = "break_minutes"
        const val KEY_DEFAULT_MIN = "default_minutes"
        const val KEY_PROTECTED = "protected_blocks"
        const val KEY_PLACES = "places"
        const val KEY_REVIEW = "weekly_review"
        const val KEY_REVIEW_DAY = "review_day"
        const val KEY_REVIEW_AT = "review_at"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_SORT = "todo_sort"
        const val KEY_SWIPE_TIP = "swipe_tip_seen"
    }
}
