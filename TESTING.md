# Test plan: v1.6.1 → v1.8

About 45 minutes. Reply with the step numbers and ✅ / ❌ (plus a screenshot or one line for each ❌).
Do the parts in order; part 6 deliberately wipes and restores the app, so it's last.

---

## 0. Get the build (5 min)

1. Unzip **DayPlanner-v1.8.zip** over your `dayplanner` folder (replace all), or `git am DayPlanner-v1.6.1-to-v1.8.patch` inside it.
2. Commit and push (GitHub Desktop: Commit → Push).
3. **GitHub → Actions:** wait for the green tick (~6 min).
   - **Red?** Open the failed run → *Build debug APK* → copy every line starting `e:` and send them to me. Most of the 1.6.2–1.8 screen code has never been through a real Android compile (there's no Android SDK where I build), so a first red build is quite possible. Each one takes minutes to fix.
4. **Releases → latest → DayPlanner.apk** → install over the top. Your data stays (database upgrades 3 → 5 automatically).

**0.1** App opens straight to Today: no setup screen, all your to-dos/routines/tasks still there.

---

## 1. Leftovers from v1.6.1 (5 min)

| # | Do | Expect |
|---|---|---|
| 1.1 | ✎ new to-do | Date chip says *Today* next to *Someday*; time chip *00:00* next to *No time* |
| 1.2 | Tap *Someday*, then the date chip | Date comes back; time cleared when Someday was picked |
| 1.3 | Duration: type 1 h 30 | Saved as 1 h 30 |
| 1.4 | Tasks → new task → End: *No end* → 3 per week | An ongoing task; a to-do appears on Today |
| 1.5 | Swipe the same to-do right 3 times in a row | Ticks, unticks, ticks: every time |
| 1.6 | +1 past the target, then −1 back | The ×n count doesn't creep up (no "3/28") |
| 1.7 | Look at tag dots | Centred next to the text |

---

## 2. Reminders, v1.6.2 (10 min, mostly waiting)

| # | Do | Expect |
|---|---|---|
| 2.1 | New to-do "Reminder test" with a time **3 min from now**; *Remind me* = At time | — |
| 2.2 | Settings ⚙ → Travel & alerts → *Scheduled* | "Reminder test" listed at that time |
| 2.3 | Wait | Notification "Reminder test · Now" with **Done** and **Snooze 10 min** |
| 2.4 | Tap **Snooze** | It disappears, comes back ~10 min later saying *Snoozed* |
| 2.5 | Tap **Done** on the snoozed one | To-do ticked in the app **and on the widget** |
| 2.6 | Another to-do 3 min ahead, then tick it before its time | No notification |
| 2.7 | A to-do 20 min ahead with *Remind me* = 15 min | Notification 5 min from now: "In 15 min · at hh:mm" |
| 2.8 | Routine with a time → edit → *Remind me at the start time* off | Its to-do no longer appears under *Scheduled* |
| 2.9 | Tick a to-do **on the widget** | Widget updates within a second or two |

---

## 3. Priority, deadlines, places, v1.7 (5 min)

| # | Do | Expect |
|---|---|---|
| 3.1 | Quick-add `Essay 3h by fri !high #study` | Snackbar: "Added “Essay” · someday · due Fri 13 Nov · high priority". Row shows a red **!** and "due Fri" |
| 3.2 | Quick-add `Revise tmr due 14/11` | Dated tomorrow, row says "due Sat" |
| 3.3 | Quick-add `Stand by me` | Title stays "Stand by me" (no deadline eaten) |
| 3.4 | Open the essay → *Priority, deadline, place* section is already open | High, deadline Fri |
| 3.5 | Settings → **Planning**: set hours, break 10 min; **Add protected time** for a tag (e.g. Study, weekdays 19:00–21:00); **Add place** "Gym" with an address | All saved and listed |
| 3.6 | Open a to-do → Place → tap the *Gym* chip | Saved; field shows "Gym" |

---

## 4. Plan my day, v1.7 (10 min) — the big one

Set up: 3–4 to-dos for today **without a time**, with durations (e.g. 30 min, 1 h, 45 min), one of them tagged with your protected tag, plus the 3 h essay from 3.1.

| # | Do | Expect |
|---|---|---|
| 4.1 | Today → To-do header → **Plan my day** | Sheet: today's free time filled; times avoid lectures/events/timed routines with ~10 min breaks; nothing before *now* or outside your hours |
| 4.2 | Look at the essay | Split into sittings, e.g. (1/2) 2 h + (2/2) 1 h, on different gaps or days |
| 4.3 | Protected tag to-do | Sits in the protected time ("protected time"); leftover protected time shows "kept free"; no other tag in there |
| 4.4 | Switch *3 days* / *Week* | Plan recalculates; *Didn't fit* lists anything left over with a reason |
| 4.5 | Untick one to-do, tap **Accept** | Snackbar "Planned n to-dos · added to <calendar>". To-dos now have times; unticked one unchanged |
| 4.6 | Google Calendar app | Blocks there, titled like the to-dos ("Essay (1/2)") |
| 4.7 | Tap **Undo** on the snackbar (be quick) | Times removed, blocks gone from Google Calendar |
| 4.8 | Plan + Accept again, then edit one planned to-do's time by hand | Its Google Calendar block disappears |
| 4.9 | Calendar tab → Schedule | Planned to-dos show once (no duplicate event line) |
| 4.10 | **Slipped:** let a planned block's end pass without ticking it, then reopen the app | Today shows "… slipped past its planned time · Re-plan"; Re-plan opens the sheet with it included |

---

## 5. Weekly review, v1.8 (5 min)

| # | Do | Expect |
|---|---|---|
| 5.1 | Settings → Travel & alerts → *Weekly review* → **Open this week's review** | Done count + bars per day (today highlighted), by tag, routines (e.g. Gym 1/2), long-term %, Slipped, Next week |
| 5.2 | On a slipped to-do tap *Today* / *Next Mon* / *Someday* / *Done* | It moves (disappears from Slipped) |
| 5.3 | **‹ Earlier** | Last week's numbers; *Later ›* comes back |
| 5.4 | **Plan the next 7 days** | Planner opens on *Week* |
| 5.5 | Set review day = **today**, time = 3 min from now | Notification "Weekly review · n done this week…"; tapping it opens the review. The Today screen also shows a *Weekly review* card today |
| 5.6 | Set it back to Sunday 18:00 | — |

---

## 6. Backup, restore and first-launch setup, v1.8 (10 min) — do last

This wipes the app on purpose and brings it back from the backup.

| # | Do | Expect |
|---|---|---|
| 6.1 | Settings → **Backup** → *Save a backup* → choose **Google Drive** (or Downloads) | "Backup saved" |
| 6.2 | Open the file from Drive | Readable JSON with your to-dos, routines, tags |
| 6.3 | Settings → *Restore from a file* → pick it → **Restore** | "Restored N to-dos, …" with Undo; nothing duplicated in Google Calendar |
| 6.4 | Android Settings → Apps → DayPlanner → Storage → **Clear storage** | — |
| 6.5 | Open DayPlanner | **First-launch setup**: dots at the top, *Skip setup* top right |
| 6.6 | First screen → **Restore from a backup instead** → pick the file → Restore | Lands in the app with everything back. Routines still appear once in Google Calendar (not twice) |
| 6.7 | (Optional) Clear storage again and walk the setup properly: calendar access → accounts tick-boxes (if you have 2) → notifications → hours + Home → type a quick-add on the last screen → *Start using DayPlanner* | Each step works; the quick-add line appears on Today. Then restore the backup again from Settings → Backup |

---

## Not testable yet

- Travel times, leave-now alerts, travel kept before to-dos with a place: need the **Maps API key** (README → Travel times).
