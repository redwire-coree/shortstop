# Orange scenario upgrade — 10.11

## Mascot placement and session start
- The running-session Orange is offset into the top-right mascot lane so its oversized speech-bubble canvas no longer sits over the “Focusing” status text.
- When a focus session becomes visible, Orange now plays its entrance animation and then settles into the calm focus pose. The initial pose is not immediately overwritten by the focus-state update.

## Scenario performance states
The layered OrangeView renderer now includes sustained/finite performance poses for:
- BEGGING — forward lean, reaching arms, wide eyes and trembling.
- DEAD — a defeated, rotated resting pose.
- PANIC — rapid but damped trembling and startled expression.
- CRYING — repeated soft sob motion and lowered expression.
- FINAL_BOSS — determined stance and controlled pulse.
- OVERLOAD — wobble, wide eyes and overloaded expression.
- VICTORY — repeated celebratory bounce with sparkle state.

## Event wiring
- Focus start: ENTER, then FOCUS.
- Early end: BEGGING; the third early end recorded for the same local day selects DEAD.
- Session completion: VICTORY.
- Very short ended session: CRYING.
- Long ended session: FINAL_BOSS.
- Last remaining break: OVERLOAD.

The seven states use the existing original layered mascot art and Android Canvas renderer. This avoids re-tracing or changing the mascot's original artwork. It is not a claim that the supplied seven-panel composite has been separated into independent SVG files.
