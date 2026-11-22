# Orange animation system — 10.12

## Runtime approach
- The existing original layered mascot remains the runtime character. Its body, leaf, eyes, pupils, highlights, mouth and spark layers are already aligned and animated independently by the Android Canvas renderer.
- `app/src/main/assets/orange_mascot_master.svg` is included as the editable vector master/source. The running Android renderer continues to use the optimized aligned PNG layers; Android does not natively render arbitrary SVG files without an SVG parser dependency.
- Seven scenario-specific performances now include their own continuous pose logic and hand-drawn Canvas effects. Effects are deterministic/time-based rather than unbounded particle objects, so they do not accumulate memory over time.

## Event mapping
| Event | Animation |
|---|---|
| Focus session becomes visible | ENTER (spring entrance + small wave), a short PANIC reaction, then sustained FOCUS |
| User ends a session early | BEGGING; after 3 early exits on the same local calendar day, DEAD |
| Session ends under 5 minutes | CRYING |
| Session ends from 5 to 44 minutes | BEGGING |
| Session ends after 45+ minutes | FINAL_BOSS |
| Session completes | VICTORY |
| Break starts with one or fewer breaks remaining | OVERLOAD, then FOCUS when the break condition clears |

## Screen placement
The in-session mascot is offset below the top status pill so it no longer sits over the “Focusing” label. It remains in the upper-right of the session card.

## Lifecycle and safety
- The animation clock uses elapsed uptime and clamps delta time, so motion does not depend on device frame rate.
- Animation drawing is paused while detached/hidden.
- Scenario FX use a small fixed number of draw operations; there is no growing particle list.
- Session timer logic remains separate from animation time.

## Verification status
Source-level edits and static checks were performed. A complete Android Gradle build and physical-device visual test were not possible in the current environment because no Android SDK/Gradle installation is available. Therefore this package is not represented as device-verified or guaranteed bug-free. The final validation step is to build and test on Android Studio/device, especially the exact Focus card spacing on the target screen sizes.
