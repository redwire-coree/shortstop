# Release tasks (tracked separately, not started)
1. Website favicon privacy: the blocked domain is sent to google.com/s2/favicons (Fx14.kt). Needs a Data safety entry and a privacy-policy line, or a different approach.
2. Accessibility declaration and prominent in-app disclosure before sending users to Accessibility settings.
3. Release signing configuration (none exists yet). No credentials were touched.
4. R8 / minification (currently off). Needs its own tested pass.
5. Target SDK 35 migration. Needs its own tested pass.
6. Incorrect comment next to PACKAGE_USAGE_STATS in AndroidManifest.xml (it describes app listing; the permission is used for Stats usage).
