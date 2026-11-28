# Bug tracker (orange-rig)

Status key: FIXED (code) = changed in code and reviewed statically; NEEDS PHONE = a person must look at it on a device.

| # | Bug | Root cause | Fix | Status |
|---|-----|------------|-----|--------|
| 1 | Focus completion celebration not visible | Victory plays on the Focus-tab Orange, which sits at the top of the page; the page was still scrolled down from the running session, so the mascot was off screen. The summary card had no entrance of its own. | Page scrolls to the top when a session starts or ends; summary card settles in with a spring; a short confetti burst (`ConfettiBurst`, touch-transparent, plays once per `reacted_end`) runs over the sheet. Normal and Ultra Focus share the same completion path. | FIXED (code). NEEDS PHONE for look and feel |
| 2 | Locked button gives no feedback | The refused press ran Orange's Rage but the button itself did nothing and was only dimmed. | `FuseButton.locked` draws a padlock that closes/opens with the real `Store.frozen` state, the label cross-fades, and a refused tap shakes the button with a reject haptic. State is never changed by the animation. | FIXED (code). NEEDS PHONE |
| 3 | Orange normal eyes look compressed | The pupil art was a tall oval (79x103) and the eye whites carried a faint baked outline of it. | Pupils redrawn as true circles (88 px) with a round main highlight and a small second catch-light; baked outline removed from the whites. Layer positions and gaze maths unchanged, so Rage/Overload/Victory/Crying use the same rig. | FIXED (art). NEEDS PHONE for the other expressions |
| 4 | Setup card pushes the running timer below the fold | Setup card stays visible during a session | Hidden while a session runs, returns afterwards | FIXED (code) |
| 5 | Page stays scrolled after a session | No scroll reset | See 1 | FIXED (code) |
| 6 | Stats zero-day bars look like heavy black dashes | 4 dp bar in the ink colour for today | Zero days are a small neutral pip; today gets a faint dot | FIXED (code) |

Checklist to confirm on a phone: timer reaches zero (normal, Ultra); tap Locked (shake, padlock, Rage); idle eyes round; Rage, Overload, Victory, Crying unchanged in character.
