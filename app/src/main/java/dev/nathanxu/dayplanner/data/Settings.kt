package dev.nathanxu.dayplanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

data class SettingsState(
    /**
     * Google accounts whose calendars are shown. null = never chosen (shows everything, and a
     * single Google account is picked automatically). Empty = you turned them all off.
     */
    val accounts: Set<String>? = null,
    /** Calendars you've switched off inside the linked accounts. */
    val hiddenCalendars: Set<Long> = emptySet(),
    /** Calendar that routines are written to. null = the first linked account's main calendar. */
    val routineCalendarId: Long? = null,
    /** Start point for the first journey of the day. */
    val home: String? = null,
    /** TravelMode name: TRANSIT, WALK, BICYCLE or DRIVE. */
    val travelMode: String = "TRANSIT",
    /** Extra minutes on top of travel time before a "leave now" alert. */
    val bufferMinutes: Int = 10,
    val leaveAlerts: Boolean = true,
    val morningSummary: Boolean = true,
    val morningTime: LocalTime = LocalTime.of(7, 30),
)

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
        )
    }

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
    }
}
