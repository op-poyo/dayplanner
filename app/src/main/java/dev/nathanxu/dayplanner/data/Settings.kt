package dev.nathanxu.dayplanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    /** Google account whose calendars are shown and written to. null = not chosen yet. */
    val accountName: String? = null,
    /** Calendar that routines are written to. null = that account's primary calendar. */
    val routineCalendarId: Long? = null,
)

/** Tiny SharedPreferences-backed store; observable so the UI updates on change. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    val current: SettingsState get() = _state.value

    private fun read() = SettingsState(
        accountName = sp.getString(KEY_ACCOUNT, null),
        routineCalendarId = sp.getLong(KEY_CAL, -1L).takeIf { it >= 0 },
    )

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        sp.edit()
            .putString(KEY_ACCOUNT, next.accountName)
            .putLong(KEY_CAL, next.routineCalendarId ?: -1L)
            .apply()
        _state.value = next
    }

    private companion object {
        const val KEY_ACCOUNT = "account_name"
        const val KEY_CAL = "routine_calendar_id"
    }
}
