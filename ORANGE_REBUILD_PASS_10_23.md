# Orange companion rebuild pass — 10.23

This pass addresses two concrete source-level defects in the supplied 10.22 project.

## Focus/break companion integration
- The visible focus-card Orange is now interactive instead of explicitly disabling touch input.
- The first focus appearance now establishes the initial state and plays ENTER without immediately replacing it with the end-break SURPRISE transition.
- A session that first becomes visible while already on break starts directly in SLEEP.
- Subsequent break-state changes still use the existing sleep/wake transition handler.

## Dead performance
- Replaced the single fixed 88-degree defeated pose with a staged recoil, loss-of-balance, side flop and settling/breathing performance.
- Existing coffin, X-eye and spirit effects are retained.

## Verification limits
- Source edits and archive contents were checked.
- This environment does not contain the project's Gradle wrapper or an Android SDK, so an Android build and on-device rendering/touch test could not be performed here.
- This is not a complete skeletal-artwork reconstruction. The mascot still uses the project's aligned PNG layers and procedural Canvas limbs. The supplied expression sheet is a flattened reference, not rigged source artwork.
- The light-orange limb strokes remain a known visual issue to validate against a device screenshot; this pass does not claim that issue is fully resolved.
