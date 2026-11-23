# Orange animation upgrade — 10.18

## Changes in this pass

- Added an articulated foot/leg pass to `OrangeView`. Each leg has its own hip, knee and ankle path, plus foot roll. The gait is deterministic from the active move clock and varies for idle/focus, hop/cheer/victory, panic/overload and dead/sleep poses.
- Added the leg pass after the body mesh and before the arm pass so the feet remain visually attached to the original mascot artwork.
- Made petting a cross-state additive reaction. Dragging and tapping can now produce a short body squash, lean, gaze, smile and leaf follow-through without replacing the active move (except exit).
- Kept the focus timer independent of the mascot's animation clock.
- Incremented app version to 10.18 / versionCode 64.

## Verification limits

- Source delimiter-balance check passed.
- ZIP integrity is checked after packaging.
- A full Android Gradle build and physical-device animation test were not possible in this environment because Gradle/Android SDK tooling is unavailable. Do not treat this as a verified release build.
