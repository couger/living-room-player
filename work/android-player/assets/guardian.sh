#!/system/bin/sh
BASE=/data/adb/living-player
export CLASSPATH=$BASE/receiver.jar
# The kernel releases this lock on exit/reboot, including SIGKILL.
if [ "$1" != locked ]; then
 if [ -x /system/bin/flock ]; then
  exec 9>"$BASE/guardian.lock"
  /system/bin/flock -n 9 9>&9 || exit 0
 else
  exec /data/adb/magisk/busybox flock -n "$BASE/guardian.lock" sh "$BASE/living-player-guardian.sh" locked
 fi
fi
echo $$ > "$BASE/guardian.pid"
restore_charge() {
 if [ -f "$BASE/charge-owned" ]; then
  printf 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active
  printf 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val
  [ "$(cat /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active)" = 0 ] && rm -f "$BASE/charge-owned"
 fi
}
trap 'kill "$child" 2>/dev/null; restore_charge; exit 0' HUP INT TERM
while grep -q '"enabled":true' "$BASE/config.json"; do
 app_process /system/bin cn.couger.livingplayer.ReceiverServer >> "$BASE/server.log" 2>&1 &
 child=$!
 wait "$child"
 restore_charge
 # Bound persistent log size. It never contains pairing tokens or screen data.
 [ "$(wc -c < "$BASE/server.log")" -gt 65536 ] && tail -c 32768 "$BASE/server.log" > "$BASE/log.new" && mv "$BASE/log.new" "$BASE/server.log"
 sleep 3
done
rm -f "$BASE/guardian.pid"
