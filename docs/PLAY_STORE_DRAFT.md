# Shortstop: Play Store readiness (draft, nothing published)

Checked against the code on branch `orange-rig`. Items marked OWNER need a decision or input from the app owner.

## Permissions and services (AndroidManifest.xml)
| Item | Used for | Notes |
|---|---|---|
| Accessibility service (`ShortsBlockerService`) | Detect Shorts/Reels, blocked apps and websites, home-screen cover, keeping Settings away while Uninstall protection is on | Needs Google's Accessibility API declaration and a prominent in-app disclosure before the user is sent to Accessibility settings. OWNER |
| `PACKAGE_USAGE_STATS` | Screen-time and usage in Stats | Special access, granted by the user in Settings. The manifest comment next to it describes app listing and should be corrected by the owner |
| `INTERNET` | Fetches website logos from `https://www.google.com/s2/favicons?domain=...` (Fx14.kt) | The blocked domain is sent to Google. Must be declared in Data safety (data shared) and in the privacy policy. OWNER |
| Notification listener (`NotifBlocker`) | Hides notifications from blocked apps (optional) | Declare in privacy policy |
| Quick Settings tile (`FocusTile`) | Start focus from the tile | None |

`allowBackup` is false. Services are protected by their system bind permissions.

## Release configuration (app/build.gradle.kts)
- applicationId `com.shortstop.app`, versionCode 68, versionName 10.22, minSdk 26, targetSdk 34.
- Only a debug signing config exists. There is no release signing config. Play needs a release key or Play App Signing. OWNER, not changed here.
- `isMinifyEnabled = false` for release and no ProGuard rules file. Enabling R8 should be tested on a device before release.
- Play requires target API 35 or higher for new apps and updates from 31 Aug 2025. Raising targetSdk changes behaviour and needs its own tested pass. OWNER decision.
- No test sources exist in the project and there are no third-party dependencies.

## Store listing checklist (to be written and approved by the owner)
- Short description (80 characters): suggested: "Block Shorts and Reels, and focus with Orange."
- Full description: describe only what exists: Focus and Ultra Focus sessions, breaks, schedules, blocks for apps and sites, Stats, cards, the Orange companion.
- Screenshots: real screens only (Focus, Schedule, Blocks, Stats, Settings, a card).
- Feature graphic, 512 px icon: use the existing Orange artwork. OWNER
- Category: Productivity. Content rating questionnaire. OWNER
- Privacy policy URL. OWNER
- Data safety form: Shortstop stores usage and focus data on the device. Network use is the favicon request above. Nothing else was found in the code. Confirm before submitting.
