# Orange interaction refinement — 10.15

This pass adds a tactile press/release layer to the existing OrangeView renderer.

- Press-in squash is layered over the currently active animation; it does not replace focus, break, or scenario moves.
- Release and ACTION_CANCEL both restore the pose through a frame-rate-independent exponential ease.
- Touch dispatch now calls `performClick()` so the custom view exposes the standard accessibility click event.
- Existing layered artwork, scenario effect loops, and animation event wiring are preserved.

Validation: source-level changes and package structure inspected. An Android Gradle build and physical-device animation test have not been run in this environment. This is not a zero-bug certification.
