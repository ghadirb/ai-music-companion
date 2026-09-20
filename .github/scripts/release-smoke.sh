#!/bin/bash
# Installs the R8-minified, signed release APK on the emulator, starts it, and fails on any crash.
set -u
PKG=com.ghadirb.aimusic
APK=$(ls rel/apk/release/*.apk | head -1)
echo "Installing $APK"
adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE || true
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 20
adb logcat -d > logcat.txt
adb exec-out screencap -p > screenshot-home.png || true
# a few interactions: open the bottom-nav tabs by tapping along the bar
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
for x in 0.12 0.3 0.5 0.7 0.9; do
  adb shell input tap $(python3 -c "print(int($W*$x))") $(python3 -c "print(int($H*0.96))"); sleep 3
done
adb exec-out screencap -p > screenshot-nav.png || true
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "=== CRASH DETECTED ==="; grep -A40 "FATAL EXCEPTION" logcat.txt | head -120
  exit 1
fi
if grep -E "ClassNotFoundException|NoSuchMethodException|NoSuchFieldException" logcat.txt | grep -q "$PKG\|ghadirb"; then
  echo "=== R8 reflection problem ==="; grep -E "ClassNotFoundException|NoSuchMethodException|NoSuchFieldException" logcat.txt | head -20
  exit 1
fi
adb shell pidof $PKG > /dev/null || { echo "app process is not running"; exit 1; }
echo "release smoke test passed"
