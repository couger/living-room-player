#!/system/bin/sh
# Magisk fallback, independent of the UI app and playback services.
BASE=/data/adb/system-diagnostics
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
[ -f "$BASE/logs/enabled" ] || exit 0
nohup /system/bin/sh "$BASE/recorder.sh" >/dev/null 2>&1 </dev/null &
