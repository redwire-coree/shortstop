# Shortstop for Android

Blocks YouTube Shorts, Instagram Reels, Facebook Reels, TikTok, Snapchat Spotlight, X video, LinkedIn video, Pinterest and Reddit video using an Android Accessibility Service.
When a Short or Reel player appears, Shortstop presses Back for you (Home if Back keeps landing in the feed;
TikTok is sent straight Home). No dependencies, no internet permission, nothing leaves the phone.

## Build the APK
**Android Studio (easiest)**
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. The file lands in `app/build/outputs/apk/debug/app-debug.apk`.

**Command line** (needs Android SDK + JDK 17)
```
gradle wrapper
./gradlew assembleDebug
```

## Set up on the phone
1. Install the APK (allow "install unknown apps").
2. Open Shortstop, tap **Enable service**, pick Shortstop, switch it on.
3. Android 13+ greys the toggle for sideloaded apps: Settings > Apps > Shortstop > ⋮ > **Allow restricted settings**, then retry.

## Keeping it working
Apps rename their internal view IDs now and then. Detection keywords are in `ShortsBlockerService.kt`
(`ID_KEYWORDS`, `SELECTED_LABELS`). If a Reel slips through, add the new ID keyword.
Facebook's IDs are the most volatile; the selected-"Reels"-tab check is the fallback.

## Features
- Per-app toggles, and a daily time allowance per app (for example 10 min of Reels, then blocked).
- Schedules: block only inside windows (work, study, midnight to 6 AM), with day-of-week chips. Windows can cross midnight.
- 7-day chart and weekly summary.
- Light, dark or system theme. Arc-style grain, scalloped dividers and sticker.
- Adaptive icon with a themed (monochrome) version for Android 13+.

## Detection notes
YouTube, Instagram, Facebook and TikTok rules are the most tested. Snapchat, X, LinkedIn, Pinterest and Reddit
detect a *selected* tab labelled Spotlight / Videos / Watch, which may need tuning. Edit the tables at the bottom
of `ShortsBlockerService.kt`.
