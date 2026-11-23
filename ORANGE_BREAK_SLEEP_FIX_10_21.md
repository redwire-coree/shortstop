# Orange break sleep fix — 10.21

- Break entry now switches Orange directly into the sustained SLEEP pose.
- Sleep uses continuous low-amplitude breathing, relaxed eyelids and a gently drifting, staggered Z animation.
- SLEEP is now an explicit ambient redraw state, so the breathing loop continues smoothly instead of freezing after a single frame.
- Ending a break triggers a short wake-up reaction, then returns to FOCUS if the session is still active and the break has not restarted.
- Break state reconciliation now handles an initially unset state as well as subsequent changes.
- Removed the old break-count-based OVERLOAD override that could replace the break transition immediately.
