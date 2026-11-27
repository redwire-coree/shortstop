#!/usr/bin/env bash
# Runs on a CI emulator (see .github/workflows/device-test.yml). Zero app dependencies: only adb.
# It installs the debug APK, seeds app state, drives lifecycle scenarios, takes screenshots and checks logcat for crashes.
set -u
# every adb call: no stdin (adb shell would swallow it) and a hard timeout, so one stuck call cannot hang the whole job
adb() { timeout 60 /usr/bin/env adb "$@" < /dev/null; }
PKG=com.shortstop.app
OUT=${OUT:-shots}
mkdir -p "$OUT"
FAILS=0
note() { echo "== $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS+1)); }
shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  # the sandbox cannot download CI artifacts, so each screenshot is also printed into the job log as a small JPEG (base64)
  if command -v convert >/dev/null && [ -s "$OUT/$1.png" ]; then
    echo "SHOT-BEGIN $1"; convert "$OUT/$1.png" -resize 300x -quality 55 jpg:- | base64 -w0; echo; echo "SHOT-END $1"
  fi
}
alive() { [ -n "$(adb shell pidof $PKG | tr -d '\r')" ]; }
crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "$PKG" || adb logcat -d 2>/dev/null | grep -q "FATAL EXCEPTION"; }
start() { adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep "${1:-2}"; }
size=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); W=${size%x*}; H=${size#*x}
tapf() { adb shell input tap "$(awk "BEGIN{print int($W*$1)}")" "$(awk "BEGIN{print int($H*$2)}")"; }

# seed the app's SharedPreferences (debug build is debuggable, so run-as works). $1 = file with <boolean/long/string/int> lines.
seed() {
  adb shell am force-stop $PKG
  { echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"; echo "<map>"; cat "$1"; echo "</map>"; } > /tmp/prefs.xml
  adb push /tmp/prefs.xml /data/local/tmp/shortstop.xml >/dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/shortstop.xml shared_prefs/shortstop.xml'"
}
now_ms() { adb shell date +%s%3N | tr -d '\r'; }

adb logcat -c
adb shell settings put global window_animation_scale 1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0

note "1. first launch (fresh install): tour should show"
start 4; shot 01_first_launch
alive || fail "app not running after first launch"

note "2. seed: tour done, protection on"
cat > /tmp/base.xml <<X
<boolean name="tour_done" value="true" />
<boolean name="u_guard" value="true" />
X
seed /tmp/base.xml
start 3; shot 02_focus_idle
for i in 1 2 3 4; do
  case $i in 1) x=0.13;; 2) x=0.32;; 3) x=0.50;; 4) x=0.66;; esac
  tapf $x 0.955; sleep 1.5; shot "03_tab_$i"
done
tapf 0.86 0.955; sleep 1.5; shot 03_tab_5_settings

note "3. lifecycle: background and return x8"
for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_HOME; sleep 0.6; start 0.8; done
alive || fail "app died during background/return loop"
shot 04_after_bg_loop

note "4. lifecycle: rotate (activity recreation) x4"
for r in 1 0 1 0; do adb shell settings put system user_rotation $r; sleep 1.5; done
shot 05_after_rotate
alive || fail "app died during rotation"

note "5. running focus session (Normal), then break"
NOW=$(now_ms); END=$((NOW+25*60000))
cat > /tmp/run.xml <<X
<boolean name="tour_done" value="true" />
<boolean name="u_guard" value="true" />
<boolean name="g_lock" value="true" />
<long name="f_start" value="$NOW" />
<long name="f_end" value="$END" />
<int name="f_min" value="25" />
<string name="f_tag">Study</string>
<boolean name="f_strict" value="false" />
<int name="f_bleft" value="2" />
<int name="f_blen" value="5" />
X
seed /tmp/run.xml
start 3; shot 06_focus_running
BU=$((NOW+5*60000)); END2=$((END+5*60000))
sed -i "s#<long name=\"f_end\" value=\"$END\" />#<long name=\"f_end\" value=\"$END2\" /><long name=\"f_bu\" value=\"$BU\" /><long name=\"f_bs\" value=\"$NOW\" />#" /tmp/run.xml
seed /tmp/run.xml
start 3; shot 07_focus_break
for i in 1 2 3; do adb shell input keyevent KEYCODE_HOME; sleep 0.6; start 0.8; done
shot 08_break_after_bg
alive || fail "app died in break"

note "6. Ultra Focus running"
sed -i 's#name="f_strict" value="false"#name="f_strict" value="true"#' /tmp/run.xml
echo "<long name=\"lock_until\" value=\"$END2\" />" >> /tmp/run.xml
seed /tmp/run.xml
start 3; shot 09_ultra_running
tapf 0.72 0.80; sleep 1.5; shot 10_ultra_end_tap   # coordinates are a first guess for the End session button

note "7. lock screen / unlock"
adb shell input keyevent KEYCODE_POWER; sleep 1.5; adb shell input keyevent KEYCODE_WAKEUP; sleep 1; adb shell input keyevent 82; sleep 1.5
shot 11_after_lock
alive || fail "app died after lock/unlock"

note "8. crash scan"
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then fail "FATAL EXCEPTION in logcat"; grep -A12 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40; fi
if grep -qiE "ANR in|am_anr" "$OUT/logcat.txt"; then grep -iE "ANR in|am_anr" "$OUT/logcat.txt" | head -3; fail "ANR seen"; fi
echo "failures: $FAILS"
exit $FAILS
