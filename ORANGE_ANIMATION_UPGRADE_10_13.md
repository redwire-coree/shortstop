# Orange animation upgrade — 10.13

This pass improves interaction and continuous effects on top of the existing layered Orange renderer.

## Interactive behavior
- Tapping Orange during a focus or break session now gives a short, eased pet/reassurance reaction without replacing the active FOCUS/BREAK state.
- The reaction includes a subtle squash, body lift, glance toward the tapped side, smile, and leaf follow-through.
- A long press during focus/break gives a distinct haptic acknowledgement and the same non-disruptive pet response.
- Three taps during focus produce a brief encouragement message rather than interrupting the session.
- Existing idle three-tap giggle and ordinary tap reaction remain available outside focus/break.

## Continuous visual effects
- Crying tears now fall on staggered looping phases.
- Final Boss has rising embers with deterministic offsets and fade-out.
- Victory has drifting confetti with staggered looping phases.
- Effects are calculated from elapsed time rather than frame counts and reuse Paint/Path state where possible.

## Verification boundary
Source-level edits only. This environment does not include a configured Android SDK/Gradle build or a physical device, so no APK build or on-device performance test is claimed. Run a Gradle build and test touch behavior, rotation, background/foreground, and reduced-motion mode on devices before release.
