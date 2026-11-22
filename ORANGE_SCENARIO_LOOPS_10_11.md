# Orange scenario-loop foundation (10.11)

This update adds a deterministic, lifecycle-aware VFX layer to the existing native `OrangeView`.
It preserves the existing mascot PNG layers and Canvas renderer and adds continuous loops for:
- `BEGGING`: animated stress marks and tears
- `DEAD`: a rising, fading ghost/soul effect
- `PANIC`: stress marks and cycling sweat droplets
- `CRYING`: animated tear streams and droplets
- `FINAL_BOSS`: independently pulsing flame tongues and rising embers
- `OVERLOAD`: drifting, expanding smoke puffs
- `VICTORY`: staggered falling confetti and pulsing sparkles

## API

```kotlin
orange.setScenarioLoop(OrangeScenario.FINAL_BOSS)
orange.clearScenarioLoop()
```

The scenario is mutually exclusive per `OrangeView`. Re-selecting the same scenario does not reset
its loop. Switching scenario starts its timeline at zero. `NONE` stops scenario rendering.

## Technical notes
- Effects use elapsed-time equations and stable phase offsets, not unbounded particle collections.
- No per-frame particle objects are allocated.
- The existing view visibility/attachment scheduler controls redraws; the effect loop stops when
  the view is detached or hidden.
- Reduced-motion mode reduces effect amplitude.
- The effects are drawn above the mascot and below its speech bubble.

## Important scope note
This is the animation/VFX engine layer, not a final set of seven bespoke character illustrations.
The supplied seven-pose concept sheet is a flattened reference image; it does not contain isolated
transparent character poses or separated coffin, soul, fire, water, smoke, crown and prop layers.
The current renderer can animate the original mascot and the added effects, but exact full-body
coffin/ghost, begging arm pose, spiral eyes, crown/podium and other scenario-specific character
silhouettes require production-ready transparent assets (or new independently drawn vector layers)
before they can match the reference exactly.

## Verification
Source archive integrity can be checked with `unzip -t`. A full Android build and device animation
test are still required; this environment does not include the Android SDK/Gradle toolchain.
