# DayPlanner v1

Personal Android planner: Google Calendar timeline with travel times, daily tasks, weekly/monthly goals, routines, and a home-screen widget. Local-first, no server. See `PLAN.md` for the design and its self-review.

## Run it (≈10 min)

1. Install **Android Studio** (Ladybug or newer). Open this folder. Let Gradle sync (it downloads Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21).
2. On your phone: Settings → About → tap *Build number* 7× → Developer options → enable **USB debugging**. Plug in.
3. Press ▶ Run. Grant the calendar permission when you tap **Connect calendar**.
4. Add the widget: long-press home screen → Widgets → DayPlanner → *Today*.

### Travel times (optional)

1. console.cloud.google.com → new project → enable **Routes API** → Credentials → *Create API key*.
2. Restrict the key: **API restrictions → Routes API only**. Optionally *Application restrictions → Android apps* with package `dev.nathanxu.dayplanner` and your debug SHA-1 (`./gradlew signingReport`).
3. Add to `local.properties` (already git-ignored):
   ```
   MAPS_API_KEY=your_key_here
   ```
4. Rebuild. Travel legs appear between consecutive events that have different locations. Results cache for 7 days, so cost stays negligible.

Locations come from the event's location field in Google Calendar, so put real addresses or place names there ("Roberts Building, UCL").

## Using it

One box adds everything:

| Type | Gets you |
|---|---|
| `Problem sheet 4 fri #study 1h30` | Task on Friday, Study, 90 min |
| `LeetCode mediums x5 @week !r` | Weekly goal, target 5, renews every Monday |
| `Two-sum tmr 30m ^leetcode` | Task tomorrow that counts towards that goal when ticked |
| `Read Hull ch.3 someday` | Undated task that stays in the list |

- **Routines** tab: repeating tasks on chosen weekdays (cooking, gym, lecture review).
- 📅 on a task opens Google Calendar pre-filled at your first free slot long enough for it.
- Unfinished tasks roll over and show as overdue.

## Tests

```
./gradlew test
```
16 JVM tests cover the quick-add parser, timeline/free-slot builder, periods and weekday masks.

## Project layout

```
app/src/main/java/dev/nathanxu/dayplanner/
  domain/    pure Kotlin: QuickAdd parser, DayPlan timeline, Category/Period/Days  (unit-tested)
  data/      Room entities + DAOs, Repository (single source of truth)
  calendar/  CalendarProvider reader + Calendar app intents
  travel/    Routes API client with Room cache
  widget/    Glance home-screen widget
  ui/        Compose screens: Today, Goals, Routines
```
