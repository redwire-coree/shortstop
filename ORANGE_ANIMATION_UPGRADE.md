# Orange animation refinement

This pass refines the existing native Android layered-mascot renderer rather than replacing the artwork or introducing a third-party animation dependency.

## What changed

- Added a separate rendered-pose layer for body translation, scale, rotation, deformation lag, and arm deformation. Animation states now produce targets while the renderer eases the visible pose toward them.
- Used faster easing for responsive gestures and softer easing for body shape, so expression changes and state transitions no longer snap between unrelated poses.
- Drove the leaf's follow-through spring from the rendered body motion instead of raw target motion. This keeps the leaf's secondary motion coherent with the character's actual on-screen movement.
- Kept reduced-motion users on a lower-amplitude, slower pose response.
- Kept the renderer at animation cadence while the body is still settling, then returned to its quiet cadence when settled.
- Explicitly clears an optional animation loop when a new non-idle action is requested, preventing an old loop from competing with a new event.
- Refined the home nudge copy and timing to use one gentle nod, a short invitation, then quiet idle and dismissal rather than repeated attention-seeking motion.

## Preserved

- Existing layered source artwork and alignment metadata.
- Native Canvas / Android View implementation and zero-third-party-dependency approach.
- Existing event-specific moves, focus/break states, blink and gaze behavior, speech bubble rendering, and reduced-motion preference.

## Validation

- ZIP archive integrity checked after packaging.
- Source-level inspection performed for the edited animation paths.
- A full Android Gradle build was not run in this environment: the project archive has no Gradle wrapper and no Gradle executable / Android SDK is available here. Please run the repository's Android build workflow before shipping.
