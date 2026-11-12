# Shortstop for Android

Blocks YouTube Shorts, Instagram Reels, Facebook Reels and TikTok using an Android Accessibility Service.
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
