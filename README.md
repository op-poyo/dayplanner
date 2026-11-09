# DayPlanner v1.8

Personal Android planner: Google Calendar timeline with travel times, daily tasks, weekly/monthly goals, routines, and a home-screen widget. Local-first, no server. See `PLAN.md` for the design and its self-review.

## Run it (≈10 min)

1. Install **Android Studio** (Ladybug or newer). Open this folder. Let Gradle sync (it downloads Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21).
2. On your phone: Settings → About → tap *Build number* 7× → Developer options → enable **USB debugging**. Plug in.
3. Press ▶ Run. Grant calendar access (read + write) when you tap **Connect calendar**.
4. Add the widget: long-press home screen → Widgets → DayPlanner → *Today*.

### Travel times (optional, needed for leave-now alerts)

1. console.cloud.google.com → new project → enable **Routes API** → Credentials → *Create API key*.
2. Lock the key down (the APK is public, so the key can be extracted from it):
   - **API restrictions → Routes API** only.
   - **Application restrictions → Android apps**: package `dev.nathanxu.dayplanner`, SHA-1 `D7:D2:ED:DC:CF:92:97:B7:7C:E3:F9:52:6E:8F:06:B7:04:1F:90:F2`.
   - **Quotas → Routes API → Compute Routes requests per day**: cap it (e.g. 200). Personal use is a few dozen a day thanks to the 7-day cache.
3. GitHub → repo **Settings → Secrets and variables → Actions → New repository secret**: name `MAPS_API_KEY`, value = the key. CI adds it to every build.
   (Building locally instead: put `MAPS_API_KEY=...` in `local.properties`.)
4. Push anything (or re-run the workflow) and install the new APK.

Locations come from each event's location field in Google Calendar, so put real addresses or place names there ("Roberts Building, UCL").

## Using it

One box adds everything:

| Type | Gets you |
|---|---|
| `Problem sheet 4 fri #study 1h30` | Task on Friday, Study, 90 min |
| `LeetCode mediums x5 @week !r` | Weekly goal, target 5, renews every Monday |
| `Two-sum tmr 30m ^leetcode` | Task tomorrow that counts towards that goal when ticked |
| `Read Hull ch.3 someday` | Undated task that stays in the list |
| `COMP0205 report @month` | Steps goal: add steps on its card (`Draft intro tue`), % = steps ticked |
| `Gym 18:00 1h #gym` (Routines tab, Mon/Wed/Fri) | Daily to-do on those days **and** a weekly repeating Google Calendar event |

### Goals → daily to-dos and percentages

- **Count goals** (`x5`) split themselves into a paced daily to-do. A weekly ×5 gives one a day with two rest days; if you miss days, the next day's to-do catches you up (`LeetCode mediums ×2`). Ticking it adds its whole amount. `+1` logs extra.
- Each card shows **%**, "n of N", and pace: *on pace*, *2 behind*, *1 ahead*, plus today's share (`today 1/2`).
- **Steps goals** (no number) track the steps you add on the card or with `^word` from quick-add.
- Today screen shows every current goal's % at a glance, and each linked task shows its goal's % (`→ LeetCode 40%`). The widget shows the top three.
- `Split ✓` on a card turns the daily split off if you'd rather just log with `+1`.

### Routines → Google Calendar

- A routine with a time becomes a weekly recurring event in your Google account's main calendar (written through Android's calendar provider; Google's sync uploads it — no OAuth).
- Pause a routine, turn its 📅 off, or delete it, and the event is removed. If you delete the event in Google Calendar yourself, the app stops syncing that routine instead of re-creating it.
- The app is the source of truth: edits made to the event in Google Calendar aren't read back.

### v1.8: weekly review, backups, first-launch setup

- **Weekly review** (Sunday 18:00 notification, or the card on Today that day, or Settings → Travel & alerts): to-dos done this week by day and by tag, routine hit rate (e.g. Gym 2/3), long-term progress, what **slipped** with one-tap fixes (*Today*, *Next Mon*, *Someday*, *Done*), and next week's to-dos and deadlines. **Plan the next 7 days** opens the planner. ‹ Earlier shows past weeks. Day and time are in Settings.
- **Backup / restore** (Settings → Backup): one readable JSON file with every to-do, long-term task, routine, tag and setting. Save it anywhere the file picker offers, Google Drive included. Restore asks first, replaces everything, and offers **Undo**. Calendar choices on the phone are kept; routines get their calendar events back without duplicates. Android's own automatic backup to your Google account stays on as well.
- **First-launch setup**: welcome, calendar access (and which Google accounts), notifications, planning hours and Home, and a quick-add tutorial with a live box. Every step can be skipped; *Restore from a backup* is on the first screen. Existing installs skip it.
- Typing quick-add text and tapping ✎ now carries priority and deadline into the full form.

### v1.7: priority, deadlines and Plan my day

- **Priority** (High / Normal / Low) and a **deadline** that's separate from the day you plan to do it. In the to-do form under *Priority, deadline, place*; in quick-add `!high` / `!low` (or `!!`, `!1`, `!3`) and `by fri` / `due 14/11`. `Essay 3h by fri` gets no day of its own, so the planner picks one before Friday. High-priority to-dos show a red **!**; deadlines show as *due Fri* (red when due today or past).
- **Plan my day** (Today → To-do header): places to-dos that have no time into the free gaps of today, 3 days or a week, around your calendar, timed routines and timed to-dos, with a break around everything. Earliest deadline first, then priority, then longest. To-dos over 2 h are split into sittings (30 min minimum). You review the plan first: untick anything to leave it out, see what didn't fit and why. **Accept** gives each to-do its time (so it gets a reminder) and, optionally, writes the blocks to your routines calendar. **Undo** puts everything back.
- **Slipped**: when a planned block passes and the to-do isn't ticked, Today offers **Re-plan**.
- **Settings → Planning**: planning hours, break length, default length for to-dos without one, **protected time** (e.g. weekdays 19:00–21:00 kept for #quant; only that tag goes there), and **saved places**. A to-do's place (saved or typed) gets travel time kept before it, when Home and travel times are set.
- Editing a planned to-do's date or time removes its old calendar blocks; deleting it removes the blocks still ahead.
- Long-term tasks have a priority too; their daily to-dos inherit it.
- DB v5 (nullable columns only, existing data kept).

### v1.6.2: reminders

- **To-dos with a time remind you**: at the time, or 5/15/30/60 min before (set per to-do under *Remind me*, or the default in Settings → Travel & alerts). The notification has **Done** (ticks it off) and **Snooze 10 min**.
- Routine to-dos remind too; switch it off per routine (*Remind me at the start time*).
- Ticking off, moving or deleting a to-do re-plans its reminder within a second, so you don't get nagged about finished things. A reminder that fires after you ticked the to-do is dropped.
- Alarms survive reboots and app updates, and are re-planned daily at 04:30.

### v1.6.1 (fixes)

- **Date and time pickers**: date is a chip that defaults to *Today* (tap to change) next to *Someday*; picking Someday keeps the date chip and clears the time. Time is a chip (00:00 until you choose) next to *No time*.
- **Duration** is hours + minutes. Quick-add understands `2h`, `2hr`, `2hrs`, `1.5h`, `1h30`, `1hr30min`, `45m`, `90mins`.
- **Ongoing tasks**: End → *No end* (or the *Ongoing* shortcut). "How many times" becomes per day / 3 days / week / month, and a to-do appears every period until you delete it.
- **Swipe** works every time (it was acting on an old copy of the row after the first swipe).
- **+1/−1 drift fixed**: today's ×n to-do is recalculated from actual progress after every change, so spamming can't inflate it (existing inflated ones correct themselves on next open). +1 stops at a finished task's target.
- Tag dots line up; the grey "Other" tag is no longer shown on to-dos.

### v1.6

**One way to add things, everywhere.** Every quick-add line (Today, Tasks, steps, Routines) has ✎ to open the full form, prefilled with whatever you typed. Each tab also has one big **New …** button. Forms open as bottom sheets.

**Tasks tab: Long-term | All to-dos.**
- All to-dos: every open to-do grouped Overdue / Today / Tomorrow / each day this week / by week / by month / Someday, plus *Done this week*.
- Long-term: progress rings, **Extend** (+1 week, +2 weeks, +1 month, pick a date), and a **Finished** section where ended tasks can be extended and brought back.

**Nicer to use** (from design research: Things 3, Todoist, delight principles):
- Swipe a to-do right to tick, left to delete.
- **Undo** on every delete instead of confirm dialogs.
- Haptic tick on completing; rows animate into place.
- Past events fade on Today; friendlier empty states.

**Fixes**
- Google Calendar ↔ app delay: the app now watches the phone's calendar and updates within a couple of seconds of Android syncing, and asks Android to sync with Google whenever you open or refresh it.
- Snackbar spam: messages replace each other instead of queueing, and +1/−1/ticks don't show one.

### v1.5

- **−1 / Undo** on long-term task cards takes back the latest progress (a +1, a tick or a ticked step).
- **New routine** form: name, days (with Weekdays / Every day / Weekends), start time, end time *or* duration, tag, calendar sync. Clashes are checked live, before you create it. The one-line box still works.

### v1.4: travel and alerts

Settings ⚙ → **Travel**:
- **Home address**: the first journey of the day starts here (shown on Today's schedule too).
- **Travel by**: public transport, walk, cycle or drive. **Buffer**: extra minutes before leaving.
- **Leave-now alerts**: a notification at event start − travel − buffer, for events with a location (needs travel times).
- **Morning summary** at a time you choose: event count, first event, to-dos.
- **Send test notification**, plus a list of what's scheduled today.
Alerts are re-planned whenever you open the app, daily at 04:30, and after a reboot or update.

### v1.3 at a glance

- **Tasks tab** (was Goals): *New task* sets a title, tag, start (default today) and end date, how many times, and a daily / every-3-days / weekly / monthly breakdown. A one-day task can have start and end times and becomes an ordinary to-do. Sections: Now and Coming up.
- **Multi-unit to-dos**: a breakdown to-do worth ×3 ticks off one at a time.
- **Tags**: coloured, not emoji. `#anything` in quick-add creates a tag; manage names and colours in Settings → Tags.
- **Clash warnings**: adding or editing a routine checks the next 4 weeks against your calendars and other routines.
- **Widget**: 4 wide — today's schedule on the left, to-dos on the right.
- **Calendar tab**: today is highlighted in every view.

### Settings ⚙ (top right)

Three tabs: **Calendars**, **Tags**, **Syntax**.
- Switch on any number of accounts (or none); untick individual calendars.
- A calendar marked *Not synced to this phone* (common for ones subscribed by link, like a UCL timetable) has a **Turn on sync** button. If Android refuses, do it in the Google Calendar app: Settings → that calendar → Sync.

- **Google account**: only this account's calendars are shown, and routines go into it. Pick it first if you have several accounts.
- **Calendar for routines**: switching moves existing routine events across.
- **Routine sync** status, and a *Sync routines now* button. If routines still don't show on the web, check Android Settings → Accounts → Google → Calendar sync is on.

### Calendar tab

- **Schedule**: scroll day by day through the next 4 weeks (tap *Show 4 more weeks* to keep going). Events and dated tasks per day.
- **3 day / Week**: hour grid; overlapping events sit side by side; red line = now. Tap a day header to jump to it.
- **Month**: grid with event titles and open-task counts; tap a day for its details, *Day view* opens it in the 3-day view.
- ‹ › move by week / 3 days / month; *Today* jumps back. Tap an event to open it in Google Calendar.

### Editing

Tap anything to edit it: tasks (title, date, time, duration, category, goal), goal cards (title, target, category, repeat, daily split), steps, and routines (title, days, time, duration, sync, active). Editing a routine updates its Google Calendar event.

### Rollover

- Tasks you typed roll over and show as overdue.
- Unticked routine to-dos and goal daily splits from earlier days are cleared (a split's missed amount moves into today's).
- 📅 on a task opens Google Calendar pre-filled at its time, or your first free slot long enough for it.

## Tests

```
./gradlew test
```
59 JVM tests cover the quick-add parser (incl. times), timeline/free-slot builder, goal pacing and percentages, calendar layout (multi-day events, overlap lanes, month grid), breakdown buckets, tags, routine clashes, travel legs from home, leave-alert timing, morning summary, periods and weekday masks.

## Project layout

```
app/src/main/java/dev/nathanxu/dayplanner/
  domain/    pure Kotlin: QuickAdd parser, DayPlan timeline, Pacing/GoalStatus, Category/Period/Days  (unit-tested)
  data/      Room entities + DAOs, Repository (single source of truth)
  calendar/  CalendarProvider reader + Calendar app intents
  travel/    Routes API client with Room cache
  widget/    Glance home-screen widget
  ui/        Compose screens: Today, Goals, Routines
```
