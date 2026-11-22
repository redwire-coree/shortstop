# Orange animation system — refined pass

This update works in the native Kotlin `OrangeView` renderer and preserves the existing layered artwork in `app/src/main/assets`.

## New character performances
- `GIGGLE`: two restrained laugh pulses, happy eyes and soft body recoil.
- `LISTEN`: attentive gaze and settled posture for calm empty states.
- `SLEEP`: slow breathing, lowered eyelids and a relaxed leaf angle.
- `PROUD`: confident, warm expression after a meaningful action.
- `ENCOURAGE`: reassuring nod with no particle burst or large jump.
- `CELEBRATE`: two-beat celebration with anticipation, lift, landing and a limited sparkle window.

## Animation quality changes
- Pose-to-pose blending now eases body deformation, rotation, arm poses, face targets and effects together instead of changing the whole character abruptly.
- Entrance and exit apply one coordinated fade, restrained scale and vertical travel to the mascot and speech bubble.
- Exit cancels any queued loop so a dismissed mascot cannot restart an animation.
- Persistent listening/sleeping states do not lock out normal taps.
- The home nudge and schedule empty state now use a single intentional performance instead of endlessly repeating a hop/wave loop.
- Successful completion events use the new two-beat celebration.

## Preserved
- Original separate PNG layers and their alignment metadata.
- Existing native Android `View`/`Canvas` rendering; no new dependencies.
- Reduced-motion amplitude handling and view lifecycle invalidation.

## Validation
- ZIP integrity checked after packaging.
- Kotlin parser/type pass was run without Android SDK dependencies; no syntax or local type-mismatch diagnostics remained.
- A full Android Gradle build could not be run in this environment because the project has no Gradle wrapper and Gradle/Android SDK are not installed. Build and device verification are still required before release.
