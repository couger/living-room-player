#!/system/bin/sh
BASE=/data/adb/living-player
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
[ -f "$BASE/config.json" ] || exit 0
grep -q '"enabled":true' "$BASE/config.json" || exit 0
nohup /system/bin/sh "$BASE/living-player-guardian.sh" >/dev/null 2>&1 &
