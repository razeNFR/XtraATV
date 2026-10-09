#!/bin/bash
# Drives the app on an Android TV emulator with D-pad key events, following .github/tv-test/steps.txt.
# Each "shot" saves a screenshot and the currently focused view to tv-out/.
OUT=tv-out
PKG=com.github.andreyasadchy.xtra.debug
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/*.apk > $OUT/install.log 2>&1
adb shell settings put global window_animation_scale 0.5
adb logcat -c
adb shell am start -n $PKG/com.github.andreyasadchy.xtra.ui.main.MainActivity
sleep 20
n=0
focus() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | sed 's/></>\n</g' | grep -o '<node[^>]*focused="true"[^>]*' | sed -E 's/.*resource-id="([^"]*)".*class="([^"]*)".*bounds="([^"]*)".*/\1 \2 \3/' | tail -1
}
while read -r cmd arg rest <&3; do
  case "$cmd" in
    ""|"#"*) ;;
    key) adb shell input keyevent "KEYCODE_$arg"; sleep "${rest:-1}" ;;
    keys) for k in $(echo "$arg" | tr ',' ' '); do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; sleep "${rest:-1}" ;;
    longkey) adb shell input keyevent --longpress "KEYCODE_$arg"; sleep "${rest:-1}" ;;
    sleep) sleep "$arg" ;;
    text) adb shell input text "$arg"; sleep 1 ;;
    shot)
      n=$((n+1))
      name=$(printf "%02d_%s" $n "$arg")
      adb exec-out screencap -p > "$OUT/$name.png"
      echo "$name: $(focus)" | tee -a $OUT/focus.log
      ;;
  esac
done 3< .github/tv-test/steps.txt
adb logcat -d -b crash > $OUT/crash.log 2>&1
echo "=== CRASHES ==="
grep -E "FATAL|Exception|at com.github" $OUT/crash.log | head -40
adb logcat -d | grep -E "AndroidRuntime|FATAL|$PKG" | tail -300 > $OUT/logcat.log 2>&1
exit 0
