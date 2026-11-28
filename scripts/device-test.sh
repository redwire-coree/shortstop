#!/usr/bin/env bash
# Reusable emulator test suite (see .github/workflows/device-test.yml). Only adb, uiautomator and python3 are needed.
# Every scenario ends in exactly one RESULT line: PASS, FAIL, BLOCKED (could not be driven, reason given) or
# NOT_TESTED (cannot be checked from outside the app). Screenshots are printed into the job log as small JPEGs.
set -u
adb() { timeout 60 /usr/bin/env adb "$@" < /dev/null; }
PKG=com.shortstop.app
OUT=${OUT:-shots}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
PASSN=0; FAILN=0; BLOCKN=0; NOTN=0
result() { # status name detail
  case $1 in PASS) PASSN=$((PASSN+1));; FAIL) FAILN=$((FAILN+1));; BLOCKED) BLOCKN=$((BLOCKN+1));; NOT_TESTED) NOTN=$((NOTN+1));; esac
  echo "RESULT|$1|$2|${3:-}"
}
check() { # name condition-exit-status detail-on-fail
  if [ "$2" = 0 ]; then result PASS "$1"; else result FAIL "$1" "${3:-}"; fi
}
shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  if command -v convert >/dev/null && [ -s "$OUT/$1.png" ]; then
    echo "SHOT-BEGIN $1"; convert "$OUT/$1.png" -resize 420x -quality 55 jpg:- | base64 -w0; echo; echo "SHOT-END $1"
  fi
}
nonblank() { # screenshot name: true when the picture has real variation (not a white/black blank screen)
  local sd; sd=$(convert "$OUT/$1.png" -format "%[fx:standard_deviation]" info: 2>/dev/null || echo 0)
  awk "BEGIN{exit !($sd > 0.02)}"
}
alive() { [ -n "$(adb shell pidof $PKG | tr -d '\r')" ]; }
start() { adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep "${1:-2}"; }
size=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); W=${size%x*}; H=${size#*x}
tapf() { adb shell input tap "$(awk "BEGIN{print int($W*$1)}")" "$(awk "BEGIN{print int($H*$2)}")"; }
now_ms() { adb shell date +%s%3N | tr -d '\r'; }
seed() {
  adb shell am force-stop $PKG
  { echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"; echo "<map>"; cat "$1"; echo "</map>"; } > /tmp/prefs.xml
  adb push /tmp/prefs.xml /data/local/tmp/shortstop.xml >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/shortstop.xml shared_prefs/shortstop.xml'"
}
prefs() { adb shell "run-as $PKG cat shared_prefs/shortstop.xml" 2>/dev/null; }
pref() { # value of one saved setting ("" when missing)
  local line; line=$(prefs | grep "name=\"$1\"" | head -1)
  case "$line" in
    *'value="'*) echo "$line" | sed -E 's/.*value="([^"]*)".*/\1/';;
    *'>'*'</'*) echo "$line" | sed -E 's/^[^>]*>([^<]*)<.*/\1/';;
  esac
}
# UI detection from the accessibility tree (no guessed coordinates)
dump() { # fresh dump every time (a failed dump must never leave an old file to be read); a few retries for busy screens
  adb shell rm -f /sdcard/ui.xml
  local n; for n in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped" && break
    [ $n = 5 ] && echo "DUMPFAIL"
    sleep 1
  done
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
}
has() { dump; python3 "$HERE/ui.py" find "$OUT/ui.xml" "$1" >/dev/null; }
taptext() { dump; local xy; xy=$(python3 "$HERE/ui.py" find "$OUT/ui.xml" "$1") || return 1; adb shell input tap $xy; }
texts() { dump; python3 "$HERE/ui.py" texts "$OUT/ui.xml" | head -${1:-25}; }
diag() { adb logcat -d -s OrangeDiag:D 2>/dev/null; }
count_move() { diag | grep -c -- "-> $1 "; }
logclear() { adb logcat -c; }
base_prefs() { cat <<X
<boolean name="tour_done" value="true" />
X
}
running_prefs() { # $1 strict(true/false) $2 minutes $3 extra lines
  local NOW END; NOW=$(now_ms); END=$((NOW+$2*60000))
  cat <<X
<boolean name="tour_done" value="true" />
<boolean name="u_guard" value="true" />
<boolean name="g_lock" value="true" />
<long name="f_start" value="$NOW" />
<long name="f_end" value="$END" />
<int name="f_min" value="$2" />
<string name="f_tag">Study</string>
<boolean name="f_strict" value="$1" />
<int name="f_bleft" value="2" />
<int name="f_blen" value="5" />
X
  [ "$1" = true ] && echo "<long name=\"lock_until\" value=\"$END\" />"
  echo "${3:-}"
}
scrolldown() { adb shell input swipe $((W/2)) $((H*72/100)) $((W/2)) $((H*22/100)) 300; sleep 1; adb shell input swipe $((W/2)) $((H*72/100)) $((W/2)) $((H*22/100)) 300; sleep 1; }
tab() { case $1 in 1) tapf 0.13 0.955;; 2) tapf 0.32 0.955;; 3) tapf 0.50 0.955;; 4) tapf 0.66 0.955;; 5) tapf 0.86 0.955;; esac; sleep 1.5; }

adb logcat -c
adb shell settings put global window_animation_scale 1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

# ---------------------------------------------------------------- 1. fresh install, first launch
echo "== 1 first launch"
start 4; shot 01_first_launch
alive; check "first launch: app running" $?
nonblank 01_first_launch; check "first launch: screen not blank" $?

# ---------------------------------------------------------------- 2. tabs, idle state
echo "== 2 tabs (idle)"
base_prefs > /tmp/p.xml; seed /tmp/p.xml; start 3; shot 02_focus_idle
nonblank 02_focus_idle; check "focus tab (idle): not blank" $?
prev=$(md5sum "$OUT/02_focus_idle.png" | cut -d' ' -f1)
for i in 2 3 4 5; do
  tab $i; shot "02_tab_$i"
  nonblank "02_tab_$i"; check "tab $i: not blank" $?
  cur=$(md5sum "$OUT/02_tab_$i.png" | cut -d' ' -f1)
  [ "$cur" != "$prev" ]; check "tab $i: screen changed after tap" $?
  prev=$cur
  echo "-- tab $i texts:"; texts 14
done
tab 4; sleep 3; shot 02_stats_a; sleep 2; shot 02_stats_b
[ "$(convert $OUT/02_stats_a.png -crop 100%x88%+0+140 -format '%#' info:)" = "$(convert $OUT/02_stats_b.png -crop 100%x88%+0+140 -format '%#' info:)" ]; check "stats: numbers and charts have settled after 3 s (no ongoing roll)" $?
tab 1; shot 02_tab_1_again
alive; check "tabs: app alive after cycling all tabs" $?

# ---------------------------------------------------------------- 3. lifecycle
echo "== 3 lifecycle"
for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_HOME; sleep 0.6; start 0.8; done
alive; check "background/return x8" $?; shot 03_after_bg
for r in 1 0 1 0; do adb shell settings put system user_rotation $r; sleep 1.5; done
adb shell settings put system user_rotation 0; sleep 2; shot 03_after_rotate
alive; check "rotation x4 (activity recreation)" $?
nonblank 03_after_rotate; check "after rotation: not blank" $?
adb shell input keyevent KEYCODE_POWER; sleep 1.5; adb shell input keyevent KEYCODE_WAKEUP; sleep 1; adb shell input keyevent 82; sleep 1.5
shot 03_after_lock
alive; check "screen lock/unlock" $?

# From here the accessibility service is switched on, like a phone that finished setup (the setup card is then gone).
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.ShortsBlockerService
adb shell settings put secure accessibility_enabled 1
sleep 3

# ---------------------------------------------------------------- 4. Normal Focus running, persistence, recovery
echo "== 4 normal focus"
running_prefs false 25 > /tmp/p.xml; seed /tmp/p.xml
F_END=$(pref f_end); F_START=$(pref f_start)
start 3; scrolldown; shot 04_normal_running
has "End session"; check "normal focus: End session button detected" $?
nonblank 04_normal_running; check "normal focus: not blank" $?
sleep 20
[ -n "$F_END" ] && [ "$(pref f_end)" = "$F_END" ] && [ "$(pref f_start)" = "$F_START" ]; check "timer state: start/end unchanged after 20 s (no reset or drift)" $?
adb shell am force-stop $PKG; start 3; scrolldown; shot 04_normal_recovered
has "End session"; check "session recovery after process kill" $?
[ -n "$F_END" ] && [ "$(pref f_end)" = "$F_END" ]; check "session recovery: end time preserved" $?

# ---------------------------------------------------------------- 5. break
echo "== 5 break"
NOW=$(now_ms)
{ running_prefs false 25 | grep -v 'name="f_end"'; echo "<long name=\"f_end\" value=\"$((NOW+30*60000))\" /><long name=\"f_bu\" value=\"$((NOW+5*60000))\" /><long name=\"f_bs\" value=\"$NOW\" />"; } > /tmp/p.xml
seed /tmp/p.xml; start 4; scrolldown; shot 05_break
has "Break ends in"; check "break: break countdown text shown" $?
for i in 1 2 3; do adb shell input keyevent KEYCODE_HOME; sleep 0.6; start 0.8; done
scrolldown; shot 05_break_after_bg
has "Break ends in"; check "break survives background/return" $?
[ "$(pref g_lock)" = "true" ]; check "break: protection lock still held" $?

# ---------------------------------------------------------------- 6. Ultra Focus running: End session attempt -> Rage
echo "== 6 ultra focus running"
running_prefs true 25 > /tmp/p.xml; seed /tmp/p.xml; logclear; start 4; scrolldown; shot 06_ultra_running
has "Locked"; check "ultra running: End button shows Locked" $?
nonblank 06_ultra_running; check "ultra running: not blank" $?
dump; ENDXY=$(python3 "$HERE/ui.py" find "$OUT/ui.xml" "Locked"); echo "locked button at: $ENDXY"
logclear; adb shell input tap $ENDXY; sleep 1.2; shot 06_ultra_end_tap
[ "$(count_move FINAL_BOSS)" -ge 1 ]; check "ultra: End session attempt plays Rage" $? "diag: $(diag | tail -3)"
for i in 1 2 3 4 5; do adb shell input tap $ENDXY; sleep 0.2; done   # no dump in between: really rapid
sleep 1
[ "$(count_move FINAL_BOSS)" -eq 1 ]; check "ultra: rapid repeated taps play Rage once" $? "count=$(count_move FINAL_BOSS)"
sleep 5; shot 06_ultra_after_rage
[ "$(diag | grep -- '->' | tail -1 | grep -c 'FOCUS')" -ge 1 ]; check "ultra: returns to Focus pose after Rage" $? "last: $(diag | grep -- '->' | tail -1)"
[ -n "$(pref f_end)" ] && [ "$(pref f_end)" != "0" ]; check "ultra: session still running after End attempt" $?
[ "$(pref g_lock)" = "true" ] && [ "$(pref u_guard)" = "true" ]; check "ultra: protection still on and locked" $?

# ---------------------------------------------------------------- 7. Overload
echo "== 7 overload"
NOW=$(now_ms)
{ running_prefs true 25; } > /tmp/p.xml
S=$(grep 'name="f_start"' /tmp/p.xml | sed -E 's/.*value="([0-9]+)".*/\1/')
echo "<long name=\"ovl_at\" value=\"$NOW\" /><long name=\"ovl_done\" value=\"$S\" />" >> /tmp/p.xml
seed /tmp/p.xml; logclear; start 5; shot 07_overload
[ "$(count_move OVERLOAD)" -eq 1 ]; check "overload: plays once after third blocked launch" $? "count=$(count_move OVERLOAD)"
sleep 8
[ "$(count_move OVERLOAD)" -eq 1 ]; check "overload: not repeated" $?
[ "$(pref ovl_at)" = "0" ]; check "overload: pending event cleared" $?
# no overload without the event
running_prefs true 25 > /tmp/p.xml; seed /tmp/p.xml; logclear; start 6
[ "$(count_move OVERLOAD)" -eq 0 ]; check "overload: not played without blocked-launch event" $?

# ---------------------------------------------------------------- 8. Normal end flow: Begging, Undo, Dead
echo "== 8 normal end session"
running_prefs false 25 > /tmp/p.xml; seed /tmp/p.xml; logclear; start 4; scrolldown
dump; ENDXY=$(python3 "$HERE/ui.py" find "$OUT/ui.xml" "End session"); echo "end button at: $ENDXY"
adb shell input tap $ENDXY; sleep 1.2; shot 08_end_armed
[ "$(count_move BEGGING)" -ge 1 ]; check "normal end: Begging plays while Undo is open" $? "diag: $(diag | tail -3)"
adb shell input tap $ENDXY; sleep 1.5; shot 08_after_undo      # same spot: the button now reads Undo
sleep 5
[ -n "$(pref f_end)" ] && [ "$(pref f_end)" != "0" ]; check "normal end: Undo keeps the session running" $?
[ "$(count_move DEAD)" -eq 0 ]; check "normal end: no Dead after Undo" $?
adb shell input tap $ENDXY; sleep 6.5; shot 08_after_confirm
[ "$(pref f_end)" = "0" ] || [ -z "$(pref f_end)" ]; check "normal end: confirmed end clears the session" $?
[ "$(count_move DEAD)" -eq 1 ]; check "normal end: Dead plays once after confirm" $? "count=$(count_move DEAD)"
[ "$(count_move VICTORY)" -eq 0 ]; check "normal end: no Victory for a manually ended session" $?
sleep 4; shot 08_after_dead
adb shell am force-stop $PKG; logclear; start 4
[ "$(count_move DEAD)" -eq 0 ]; check "normal end: Dead not replayed after reopening" $?

# ---------------------------------------------------------------- 9. Natural completion -> Victory -> summary
echo "== 9 completion"
NOW=$(now_ms)
{ running_prefs false 25 | grep -v 'name="f_end"'; echo "<long name=\"f_end\" value=\"$((NOW+9000))\" />"; } > /tmp/p.xml
seed /tmp/p.xml; logclear; start 3; shot 09_before_complete
sleep 12; shot 09_victory
[ "$(count_move VICTORY)" -eq 1 ]; check "completion: Victory plays once" $? "count=$(count_move VICTORY)"
sleep 6; shot 09_summary
has "Session complete"; check "completion: summary sheet shows after Victory" $?
[ "$(pref f_end)" = "0" ] || [ -z "$(pref f_end)" ]; check "completion: session cleared" $?
adb shell am force-stop $PKG; logclear; start 5
[ "$(count_move VICTORY)" -eq 0 ]; check "completion: no Victory replay after reopening" $?
has "Session complete"; [ $? -ne 0 ]; check "completion: summary not shown again after reopening" $?

# ---------------------------------------------------------------- 10. Protection switch (Settings > Ultra Focus)
echo "== 10 protection switch"
open_ultra_sheet() { # Settings > Ultra Focus; the page animates in, so try a few times
  start 3; tab 5
  local n; for n in 1 2 3; do sleep 1; taptext "^Ultra Focus$" && { sleep 1.5; return 0; }; done
  return 1
}
toggle_guard() { # taps the switch on the 'Uninstall protection' row; returns 1 when the row is not found
  dump; python3 "$HERE/ui.py" find "$OUT/ui.xml" "Uninstall protection" > /tmp/xy || return 1
  adb shell input tap $((W*88/100)) "$(cut -d' ' -f2 /tmp/xy)"; sleep 1; return 0
}
# a) no session: off -> on is saved and survives a restart
{ base_prefs; echo '<boolean name="u_guard" value="false" />'; } > /tmp/p.xml; seed /tmp/p.xml
if open_ultra_sheet; then
  shot 10_ultra_sheet; echo "-- sheet texts:"; texts 30
  if toggle_guard; then
    shot 10_guard_on
    [ "$(pref u_guard)" = "true" ]; check "protection: switching on is saved" $?
    adb shell am force-stop $PKG; start 3
    [ "$(pref u_guard)" = "true" ]; check "protection: stays on after restart" $?
  else result BLOCKED "protection: switch on" "row 'Uninstall protection' not found in the Ultra Focus sheet"; fi
else result BLOCKED "protection: switch on" "Ultra Focus link not found in Settings"; fi
# b) session started with protection on: cannot be switched off
running_prefs false 25 > /tmp/p.xml; seed /tmp/p.xml
if open_ultra_sheet && toggle_guard; then
  shot 10_guard_locked
  [ "$(pref u_guard)" = "true" ]; check "protection: ON at session start cannot be switched off" $?
  has "stays on"; check "protection: friendly explanation shown when blocked" $?
else result BLOCKED "protection: lock at start" "could not reach the switch"; fi
# c) switched on during a running session: locks immediately
{ running_prefs false 25 | grep -v 'name="u_guard"\|name="g_lock"'; echo '<boolean name="u_guard" value="false" />'; } > /tmp/p.xml; seed /tmp/p.xml
if open_ultra_sheet && toggle_guard; then
  [ "$(pref u_guard)" = "true" ] && [ "$(pref g_lock)" = "true" ]; check "protection: switched on mid-session is locked at once" $?
  toggle_guard; sleep 0.5
  [ "$(pref u_guard)" = "true" ]; check "protection: then cannot be switched off during that session" $?
else result BLOCKED "protection: lock mid-session" "could not reach the switch"; fi
# d) protection OFF at session start stays off
{ running_prefs false 25 | grep -v 'name="u_guard"\|name="g_lock"'; echo '<boolean name="u_guard" value="false" />'; } > /tmp/p.xml; seed /tmp/p.xml; start 5; sleep 5
[ "$(pref u_guard)" = "false" ] && [ "$(pref g_lock)" != "true" ]; check "protection: OFF at start is never switched on automatically" $?

# ---------------------------------------------------------------- 11. empty states and stats
echo "== 11 empty states"
base_prefs > /tmp/p.xml; seed /tmp/p.xml; start 3
for i in 2 3 4; do tab $i; shot "11_empty_tab_$i"; nonblank "11_empty_tab_$i"; check "empty state tab $i: not blank" $?; echo "-- texts:"; texts 14; done

# ---------------------------------------------------------------- 12. stress
echo "== 12 stress"
start 2
for i in $(seq 1 6); do for t in 1 2 3 4 5; do tab $t; done; done
alive; check "rapid tab switching (30 switches)" $?
adb shell monkey -p $PKG --throttle 60 -s 11 400 >/dev/null 2>&1; sleep 2
alive || start 2
shot 12_after_monkey
adb logcat -d | grep -q "FATAL EXCEPTION.*" ; [ $? -ne 0 ]; check "monkey: 400 random events without a crash" $?
# speech box then Home straight away (the stop-activity crash scenario)
base_prefs > /tmp/p.xml; seed /tmp/p.xml; start 3
for i in 1 2 3 4 5; do
  if taptext "Orange"; then sleep 0.4; fi
  adb shell input keyevent KEYCODE_HOME; sleep 0.5; start 0.8
done
alive; check "speech box then Home x5 (stop-activity crash scenario)" $?

# ---------------------------------------------------------------- not driveable from outside
result NOT_TESTED "timer digits on screen" "drawn on a canvas, so the value cannot be read from the UI tree; state accuracy is checked through saved times instead"
result NOT_TESTED "animation smoothness / FPS" "an emulator without a GPU cannot give meaningful frame timings"
result NOT_TESTED "Orange performance details (Rage/Overload/Victory look)" "only visible in screenshots; the trigger and return are checked through the move log"

# ---------------------------------------------------------------- crash / ANR scan
adb logcat -d > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; [ $? -ne 0 ]; check "logcat: no FATAL EXCEPTION" $?
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then grep -A12 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40; fi
grep -qE "ANR in $PKG" "$OUT/logcat.txt"; [ $? -ne 0 ]; check "logcat: no ANR" $?
echo "SUMMARY|pass=$PASSN|fail=$FAILN|blocked=$BLOCKN|not_tested=$NOTN"
exit $FAILN
