package dev.nathanxu.dayplanner.data

import dev.nathanxu.dayplanner.domain.PlanRules
import dev.nathanxu.dayplanner.domain.ProtectedBlock
import java.time.DayOfWeek
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
    /** Notify for to-dos that have a time. */
    val remindTodos: Boolean = true,
    /** Default minutes before a to-do's time (0 = at the time). */
    val reminderLead: Int = 0,
    /** Planner: when your day starts and ends. */
    val workStart: LocalTime = LocalTime.of(8, 0),
    val workEnd: LocalTime = LocalTime.of(22, 0),
    /** Planner: gap kept around everything it places. */
    val breakMinutes: Int = 10,
    /** Planner: length for to-dos with no duration. */
    val defaultMinutes: Int = 30,
    /** Time kept for one tag (e.g. quant prep). */
    val protectedBlocks: List<ProtectedBlock> = emptyList(),
    /** Named addresses ("Gym", "Uni") you can pick for a to-do's place. */
    val places: List<SavedPlace> = emptyList(),
    /** Weekly review notification. */
    val weeklyReview: Boolean = true,
    val reviewDay: DayOfWeek = DayOfWeek.SUNDAY,
    val reviewTime: LocalTime = LocalTime.of(18, 0),
    /** Finished (or skipped) the first-launch setup. */
    val onboarded: Boolean = false,
) {
    val planRules: PlanRules
        get() = PlanRules(workStart = workStart, workEnd = workEnd, breakMinutes = breakMinutes, defaultMinutes = defaultMinutes)

    /** A to-do's place → address: a saved place's address, or the text itself. */
    fun addressOf(place: String?): String? {
        val p = place?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return places.firstOrNull { it.name.equals(p, ignoreCase = true) }?.address ?: p
    }
}

data class SavedPlace(val name: String, val address: String)
