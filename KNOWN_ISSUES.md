# Known Issues and Limitations

This is an honest list of current limitations in Mudita Timer (v1.6.0). It is a
small, single-purpose app built and tested specifically for the Mudita Kompakt.
Nothing here prevents normal use; these are documented so expectations are clear.

## Scope and distribution

- **Sideload only.** There is no Play Store listing. The app is installed by
  sideloading the APK via Mudita Center. It is signed with a personal key.
- **Targets an older Android API on purpose.** The app targets SDK 31 (Android 12)
  to match the Kompakt's platform. The Google Play "target a recent API level"
  lint check is intentionally disabled. This is the right choice for a sideloaded
  Kompakt app but means the app is not intended for general Play distribution.
- **Tested on the Mudita Kompakt.** Behavior on other Android devices or launchers
  is not guaranteed. The launcher icon in particular is shipped as a raster image
  sized for the Kompakt's launcher rather than as an adaptive vector icon.

## Alarm and timing

- **Exact alarm timing depends on the device granting it.** The end-of-timer alarm
  is scheduled with Android's AlarmManager. Where the system allows exact alarms,
  the alarm fires at the precise end time, even with the screen locked. Where a
  device or OS build does not grant exact-alarm permission, the app falls back to
  an inexact alarm so it still fires, but the system may delay it slightly rather
  than firing at the exact second. On the Kompakt this has tested as on-time.
- **On Android 13+ setups, an "Alarms & reminders" permission may matter.** If a
  device requires the user to grant exact-alarm permission and it is not granted,
  timing uses the inexact fallback described above.
- **The alarm rings until you stop it.** When a timer ends, a loud repeating tone
  plays on the alarm audio stream until you tap **Stop alarm**, either in the app
  (shown over the lock screen, with the display turned on) or on the notification.
  As a safety cap, an alarm nobody stops goes quiet after 10 minutes and leaves a
  notification saying so.
- **The alarm plays at full alarm volume.** While it rings, the phone's alarm
  volume is raised to maximum; your own setting is put back when the alarm stops.
  The tone and volume are not configurable, by design, to keep the app simple.
- **Screen wake depends on the system.** Turning the display on and showing the
  Stop button over the lock screen uses Android's full-screen notification
  mechanism. The sound does not depend on it.

## State and persistence

- **A running countdown is recovered; a paused one is not, across a full restart.**
  If the app is closed or killed while a countdown is running, on reopen it reads
  the saved end time and correctly shows the timer as finished if that time has
  passed. State across screen rotation and brief recreation is preserved. However,
  a paused timer or a running stopwatch is held in memory and is not restored if
  the operating system fully kills the process.
- **No history.** The app keeps no log of past timers or stopwatch runs. Each
  session starts fresh. This is intentional.

## Interface

- **The ongoing notification uses a generic system icon.** While a timer or
  stopwatch is running, Android requires a persistent notification (this is what
  keeps the timer reliable in the background). Its small icon is currently a stock
  system icon rather than the app's hourglass. Cosmetic only.
- **The persistent notification cannot be dismissed while running.** This is
  required by Android for a foreground service and is expected behavior, not a bug.
- **Custom durations are bounded.** Custom times are entered on an on-screen
  numpad (digits fill from the right, like a stock phone timer) and accept 0 to
  99 minutes and 0 to 59 seconds. An out-of-range entry (for example a stray
  `00:90`) is simply ignored when Start is tapped rather than showing an error.

## Not yet implemented

These are reasonable requests that are not in the app yet, noted here so the scope
is clear:

- **Presets are fixed.** The one-tap presets are 5, 10, and 25 minutes and cannot
  currently be changed or added to. Custom times of any length can still be entered
  on the numpad.
- **A running timer cannot be extended.** There is no "add a minute" control once a
  countdown is running; to change the time, reset and start again.

## Maintenance notes (for anyone building from source)

- **No automated tests.** The app has been verified by manual testing on device.
- **Forward compatibility.** A couple of patterns are valid at the current target
  (SDK 31) but would need changes before raising the target SDK: the dynamic
  broadcast receiver registration would need an explicit exported/not-exported
  flag on API 33+, and the foreground service type would need review on API 34+.
  The app's internal broadcasts are already package-scoped.

## Reporting

If something does not work as described, especially the alarm not firing on your
device, please open an issue noting your device, the Android/OS version, and
whether the timer was running with the screen on or locked.
