# Shortstop visual upgrade

## What changed
- Replaced the stark black/white global palette with warm paper, espresso text, soft borders, and a restrained citrus-orange action color.
- Updated the dark palette to use deep espresso surfaces and warm cream text.
- Segmented controls and primary pills now use the Shortstop orange accent.
- App rows in Blocks now have a subtle warm selected treatment when an app is included in the current blocking mode.
- Refined the Schedule empty-state portrait duotone to use espresso and muted orange tones against the warm page matte, with slightly softer contrast.
- Matched the Schedule empty-state status bar to the warm page canvas.
- Updated the README theme description.

## Preserved
- Existing five-tab navigation and tab order.
- Shorts, Apps, and Websites categories.
- Always-on and Focus-only app modes.
- Search, Block All / Unblock all, per-app toggles, essential-app exclusions, and undo behavior.
- Schedule storage, schedule activation, templates, and editor behavior.
- Stats calculations, historical data, charts, rewards, and screen-time permission flow.
- Accessibility blocking service, persistence, and settings.

## Validation
- Source archive inspected and edited.
- Basic changed-source sanity checks passed.
- A Gradle/Android SDK build could not be run in this environment because the project archive does not include a Gradle wrapper and no Gradle installation or Android SDK was available. Install/build with Android Studio before publishing.
