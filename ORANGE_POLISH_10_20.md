# Orange companion polish — 10.20

## Long-press magic interaction
- A deliberate 4.5-second press on Orange triggers a restrained aurora flourish.
- Light theme uses a muted sage/green glow; dark theme uses a warm orange glow.
- The flourish combines a soft radial halo, a thin breathing ring, and deterministic drifting/twinkling sprinkles.
- Particles are calculated from elapsed time (not random values per frame), avoiding flicker and avoiding per-frame particle allocations.
- The effect fades out after 2.6 seconds and does not replace the active focus/break/scenario animation.
- A long press consumes the click, so it cannot also trigger the tap reaction.
- The existing short tap and drag/pet interactions remain available.
- The animation loop requests display-frame invalidation only while the magic effect is active.

## Copy polish
Companion lines were shortened and made calmer, more specific, and less emotionally pressuring. Examples include “Ready when you are.” and “Daily goal reached. Nicely done.”

## Verification boundary
Source and archive checks are possible here, but this environment has no Android SDK or Gradle executable, so a real Android build and device-level interaction test have not been performed. Do not treat this package as device-certified or bug-free until that QA is completed.
