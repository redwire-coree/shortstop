# Orange companion polish — 10.16

## Changes
- Companion dialogue is now presented in the measured subtitle column beside Orange instead of being drawn into the mascot canvas. This prevents the speech bubble from crossing the Orange title or progress copy.
- Dialogue uses a short alpha/vertical entrance and restores the current progress line after its timeout. A token prevents an older timeout from clearing a newer message.
- Increased the idle companion artwork size from 56dp to 64dp and widened the text gutter to 84dp; increased the companion row height to 104dp to give the layout more breathing room.
- Welcome-back, goal, early-quit, and session-result companion dialogue use the separate text lane.
- App version updated to 10.16 / versionCode 62.

## Verification limits
This package is based on the available 10.15 source archive. No Android SDK/Gradle installation was available in the working environment, so no APK build or physical-device test was performed. This patch fixes the visible dialogue/layout collision; it does not claim to be a complete professional character-rig rewrite or a zero-bug release.
