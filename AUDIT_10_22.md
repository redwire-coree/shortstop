# Shortstop 10.22 — source audit and targeted fix

## Confirmed issue fixed
- Ending a break early previously cleared `f_bu` but left the full planned break extension in `f_end` and `f_bt`.
- This made an early-ended break add unused time to the focus session and over-subtract break time from early-completion statistics.
- `endBreak()` now subtracts only the remaining unused break duration from the focus end, break accounting, and strict-mode lock deadline.
- Added `f_bs` to record break start. Existing in-progress sessions without this key are handled using the stored deadline and configured break duration.
- New sessions and cleared sessions reset `f_bs`.

## Static checks performed
- Project archive extracted successfully.
- Android manifest and resource XML parsed successfully.
- SVG mascot master parsed successfully.
- PNG assets decoded successfully.
- Checked source for TODO/FIXME markers and high-risk lifecycle/timer patterns.

## Not verified
- Full Gradle build, Android lint, emulator execution, physical-device UI, accessibility service behaviour, and notification listener behaviour were not executable in this environment because Android SDK/Gradle are unavailable.
- This is a source-level audit, not a certification that the entire app is bug-free.
