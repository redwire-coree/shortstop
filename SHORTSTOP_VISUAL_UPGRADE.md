# Shortstop — Schedule screen refinement

## Updated in this pass
- Removed the artificial 20% empty matte band beneath the Schedule portrait by fitting the portrait into the full DitherVeil canvas.
- Reduced the DitherVeil height cap from 360dp to 300dp so the portrait has a more balanced footprint on phone screens.
- Kept only a subtle bottom fade rather than fading a large lower section into blank paper.
- Tightened the Schedule empty-state title spacing and slightly reduced its size.
- Widened the schedule-template cards and reduced side insets so the first card is easier to read.

## Preserved
All schedule creation, templates, schedule storage, navigation, blocking logic, and existing app behavior.

## Build status
Source edits are included. This environment does not have the Android SDK/Gradle installation needed to produce and verify an APK. Build and test in Android Studio before release.
