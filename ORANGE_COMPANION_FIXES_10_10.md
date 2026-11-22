# Orange companion fixes — 10.10

This source update makes three focused corrections to the existing mascot implementation:

1. **Three-tap interaction:** the old tap handler rejected taps while a TAP animation was busy. Since TAP lasts longer than the old tap interval, the three-tap GIGGLE sequence was difficult or impossible to reach. Taps can now be counted during the short TAP reaction, while FOCUS and BREAK poses remain protected.
2. **Focus-row layout:** the mascot renderer reserves a wide canvas for speech bubbles, but the Focus row placed that full view at a negative horizontal margin. That could clip the character. The view now stays within the row, uses a slightly smaller display size, and the text lane begins after the character.
3. **Background lifecycle:** the delayed sleep callback is cancelled when the Activity pauses and rescheduled on resume, preventing the delayed state change from firing while the app is backgrounded.

Original Orange image assets and the existing renderer artwork are unchanged.

## Verification status

- Source-level checks: completed for the edited sections.
- Android build: not run in this environment (no Gradle executable or Gradle wrapper is included).
- Device/emulator visual testing: not performed.

Use the repository's GitHub Actions workflow to build the APK, then test on a physical phone before release.
