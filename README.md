# DayPlanner v1.5

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
54 JVM tests cover the quick-add parser (incl. times), timeline/free-slot builder, goal pacing and percentages, calendar layout (multi-day events, overlap lanes, month grid), breakdown buckets, tags, routine clashes, travel legs from home, leave-alert timing, morning summary, periods and weekday masks.

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
