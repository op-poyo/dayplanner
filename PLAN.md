# DayPlanner — plan

Personal Android app (single user, Nathan). Native Kotlin, local-first, no server.

## 1. Goal of v1

One screen that answers "what am I doing today, and can I physically get there?", plus a home-screen widget to tick tasks off without opening the app.

## 2. Draft plan (v0, before review)

- Two-way Google Calendar sync via the Calendar REST API + OAuth
- Daily to-do list, weekly/monthly goals, home-screen widget
- Travel time between consecutive events
- Exercise tracker, fridge inventory, shopping list
- Auto-scheduler that places tasks into free calendar gaps
- Python backend for the scheduler

## 3. Self-review of v0 (blunt)

| Problem with v0 | Why it's a problem | Fix in revised plan |
|---|---|---|
| Calendar REST API + OAuth | Needs a Google Cloud project, consent screen, token refresh, test-user setup. Days of plumbing for zero user-visible value. | Read via Android `CalendarProvider` (your Google Calendar is already synced to the phone). Create events by handing off to the Calendar app with `ACTION_INSERT` — no write permission, no OAuth. |
| Exercise + fridge + shopping in v1 | Three separate data models and UIs. Each one is a mini-app. v1 would ship nothing finished. | Cut from v1. Data model leaves room (task categories `EXERCISE`, `COOKING`, `SHOPPING`). Modules land in v2/v3. |
| Auto-scheduler in v1 | The hard, interesting part — but it's worthless until there's reliable data (tasks with durations, real free slots). Building it first is building on sand. | v1 computes **free slots** (gaps after travel) and shows them. v2 fills them. |
| Python backend | Adds hosting, auth, deploy and cold starts to solve a problem that doesn't exist yet. | No server. Keep scheduling logic as pure Kotlin functions with unit tests so it can be ported later if needed. |
| "Daily tasks" was vague | Cooking, gym, review sessions repeat. Re-typing them daily kills the habit within a week. | **Routines**: a task template with weekdays that auto-creates today's instance. |
| "Easy to set up new goals/tasks" was vague | Forms with five fields are not easy. | **One quick-add box** with a tiny syntax (see §5). |
| Goals disconnected from tasks | A goal you can't log progress against is a wish. | Tasks can link to a goal; progress = done linked tasks / target. Recurring goals auto-renew each week/month. |
| Unfinished tasks vanish at midnight | Silent data loss of intent. | Undone tasks from earlier days roll over and are marked overdue. |
| Travel time with no cost control | Re-querying the Routes API on every screen open burns quota. | Cache by (origin, destination, mode) for 7 days in Room. |
| API key in the APK | Extractable from the APK. | Acceptable for a personal sideloaded build; **restrict the key** to Routes API + your app's package/SHA-1 in Google Cloud console. Never commit `local.properties`. |

Known limitations accepted for v1:
- Transit times are estimated without a departure time (good enough to flag "you can't make it"; not a journey planner).
- Widget refreshes on every change in the app and every ~30 min otherwise (Android limit). Ticking in the widget updates instantly.
- Deleting a routine-generated task for today recreates it on next refresh — mark it done instead, or edit the routine.

## 4. Revised v1 scope

1. **Today screen**: calendar timeline for today (events, travel legs between located events, "tight" warnings, free slots ≥ 30 min) + task list (today, overdue, undated).
2. **Quick add**: tasks and goals from one text box.
3. **Goals screen**: this week / this month, progress bars, "+1" logging, recurring goals.
4. **Routines screen**: repeating daily tasks on chosen weekdays.
5. **Widget**: today's tasks, next event, tap to tick, "+" opens quick add.
6. **Block time**: send any task to Google Calendar as an event (pre-filled in the Calendar app).

## 5. Quick-add syntax

| Token | Meaning | Example |
|---|---|---|
| `today` `tmr` `tomorrow` `mon`…`sun` | Date (default today; weekday = next occurrence, incl. today) | `Problem sheet 4 fri` |
| `someday` | No date (stays in list) | `Read Hull ch.3 someday` |
| `#tag` | Category: study, exercise/gym/run, cook, shop, project, quant | `5k tempo #run` |
| `45m` `1h` `1h30` | Duration | `Mechanics review 1h30` |
| `^word` | Link to the active goal whose title contains *word* | `LC two-sum ^leetcode` |
| `@week` / `@month` | Make it a **goal** for this week/month | `LeetCode mediums x5 @week` |
| `x5` | Goal target (default 1) | |
| `!r` | Recurring goal (renews every period) | `Run 3 times x3 @week !r` |

## 6. Architecture

```
ui (Compose)  ──>  MainViewModel  ──>  Repository  ──>  Room (tasks, goals, routines, travel_cache)
                         │                  │
                         │                  ├──> CalendarSource (CalendarProvider, read-only)
                         │                  └──> TravelService (Routes API + cache)
                         └──> domain (pure Kotlin: QuickAdd parser, DayPlan timeline builder)
widget (Glance) ──> Repository
```

- Kotlin 2.0, Jetpack Compose (Material 3, dynamic colour), Room, Glance, java.time. minSdk 26.
- Manual DI via an `AppContainer` on the `Application` — no Hilt for an app this size.
- Pure domain code has no Android imports → unit-testable on the JVM.

## 7. Data model

- `Task(id, title, date?, category, minutes?, goalId?, routineId?, done, doneAt?)` — unique (routineId, date) so routines never double-create.
- `Goal(id, title, period WEEK|MONTH, periodStart, target, recurring, archived)`
- `Routine(id, title, category, minutes?, daysMask, goalId?, active)` — bit 0 = Monday.
- `TravelCache(key, seconds, fetchedAt)`

## 8. Post-build review (v1 as shipped)

- **Verified**: domain logic (parser, timeline, periods, day masks) compiled with Kotlin 2.0.21 and passes 16 JUnit tests.
- **Not verified**: the Android layer (Room, Compose, Glance, CalendarProvider) was written without an Android SDK available, so it has never been compiled or run. Expect a first-sync fix or two; dependency versions were picked as a known-compatible set (AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Room 2.6.1 / Compose BOM 2024.12.01 / Glance 1.1.1).
- **Weak spots to fix next**: no settings screen (travel mode is hard-coded to transit); no home location, so the first leg of the day has no travel time; deleting a routine task gets it recreated; no task editing (delete and re-add); widget only refreshes the date every ~30 min after midnight.
- **Design debt worth paying early**: Room `exportSchema = false` and no migrations — fine until you have data you care about. Turn on schema export and write migrations before v2.

## 9. v1.1 changes

- **Routines → Google Calendar.** Timed routines are written as weekly recurring events (RRULE) via CalendarProvider with WRITE_CALENDAR; Google's sync adapter uploads them. Reconciled on every app open and routine edit. Deleting the event in Google Calendar switches sync off for that routine rather than fighting you. One-way: the app owns the routine.
- **Goal breakdowns.** Count goals get a paced daily to-do: by day k of n you should have done ceil(target·k/n); today's to-do is the shortfall, so missed days are absorbed instead of piling up as overdue. Goals without a number become steps goals (% = steps ticked).
- **Percentages everywhere.** Goal cards (% + pace + today's share), a Today-screen goal strip, goal % on linked tasks, top three in the widget.
- **Times.** `18:30` / `6pm` in quick-add; tasks sort by time; block-time uses it.
- Schema v2 with destructive migration (fine pre-release; write real migrations before relying on data).

Self-review of v1.1:
- Pacing spreads evenly across all days of the period, including weekends. If you only want weekdays, that needs a per-goal day mask (cheap to add).
- Changing a routine's days/time means delete + re-add; there's no edit screen yet.
- Calendar choice is automatic (Google primary). If you have several Google accounts it may pick the wrong one; a picker belongs in the settings screen.
- Still uncompiled on Android (no SDK here). Domain logic: 24/24 tests pass.

## 10. Roadmap

- **v1** (this build): above.
- **v1.1**: settings screen (travel mode, home address so first/last legs work), notifications for "leave now".
- **v2 — scheduler**: greedy/ILP placement of duration-tagged tasks into free slots with priorities and deadlines; one-tap "accept plan" writes blocks to calendar.
- **v2 — exercise**: pull from Health Connect; tie to running goals (5k/10k targets).
- **v3 — kitchen**: inventory with expiry, meal suggestions from what's in, auto shopping list.
- **Later**: Firebase/Supabase sync if you want a laptop view.
