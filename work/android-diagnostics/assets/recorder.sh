#!/system/bin/sh
# Root-owned, bounded, local-only history. No screen/audio/account capture.
BASE=/data/adb/system-diagnostics/logs
mkdir -p "$BASE"
chmod 700 "$BASE"
if [ "$1" != locked ]; then
 exec 9>"$BASE/recorder.lock"
 /system/bin/flock -n 9 9>&9 || exit 0
fi
[ -f "$BASE/enabled" ] || exit 0
umask 077
echo $$ > "$BASE/recorder.pid"
boot=$(cat /proc/sys/kernel/random/boot_id)
if [ "$(cat "$BASE/current/boot-id" 2>/dev/null)" != "$boot" ]; then
 # These three directories belong exclusively to this recorder.
 rm -rf "$BASE/previous-2"
 [ ! -d "$BASE/previous-1" ] || mv "$BASE/previous-1" "$BASE/previous-2"
 [ ! -d "$BASE/current" ] || mv "$BASE/current" "$BASE/previous-1"
 mkdir -p "$BASE/current"
 echo "$boot" > "$BASE/current/boot-id"
fi
SESSION=$BASE/current
mkdir -p "$SESSION/tombstones"
{ date; cat /proc/uptime; getprop ro.build.fingerprint; getprop ro.boot.bootreason; getprop sys.boot.reason; getprop persist.sys.boot.reason.history; } > "$SESSION/start.txt"
copy_changed() {
 local source=$1 target=$2 cap=$3 mode=$4 stamp
 [ -f "$source" ] || return
 stamp=$(stat -c '%Y:%s' "$source")
 [ "$(cat "$target.stamp" 2>/dev/null)" != "$stamp" ] || return
 if [ "$mode" = head ]; then head -c "$cap" "$source" > "$target.new"; else tail -c "$cap" "$source" > "$target.new"; fi
 mv "$target.new" "$target"
 echo "$stamp" > "$target.stamp"
}
snapshot() {
 checksum=$(dmesg | grep -v 'avc: denied' | sha256sum)
 if [ "$(cat "$SESSION/kernel.stamp" 2>/dev/null)" != "$checksum" ]; then
  dmesg | grep -v 'avc: denied' | tail -c 524288 > "$SESSION/kernel.new" && mv "$SESSION/kernel.new" "$SESSION/kernel.txt"
  echo "$checksum" > "$SESSION/kernel.stamp"
 fi
 [ -f "$SESSION/kernel-at-start.txt" ] || cp "$SESSION/kernel.txt" "$SESSION/kernel-at-start.txt"
 copy_changed /data/system/uiderrors.txt "$SESSION/rescue.txt" 262144 tail
 for name in last_kmsg last_kmsg.1 last_log; do
  copy_changed "/cache/recovery/$name" "$SESSION/recovery-$name.txt" 1048576 tail
 done
 for source in /data/tombstones/tombstone_*; do
  case "$source" in *.pb) continue;; esac
  name=$(basename "$source")
  case "$name" in tombstone_[0-2][0-9]|tombstone_3[01]) ;; *) continue;; esac
  copy_changed "$source" "$SESSION/tombstones/$name.txt" 262144 head
 done
}
snapshot
logcat -b main,system,crash,events -v threadtime -v monotonic -f "$SESSION/android.log" -r 2048 -n 3 'AudioFlinger:W' 'AudioPolicyManager:W' 'HdmiControlService:W' 'RescueParty:I' 'Watchdog:I' 'am_crash:I' 'am_anr:I' '*:E' >/dev/null 2>&1 &
child=$!
waiter=""
trap 'kill "$child" 2>/dev/null; [ -z "$waiter" ] || kill "$waiter" 2>/dev/null; rm -f "$BASE/recorder.pid"; exit 0' HUP INT TERM EXIT
while [ -f "$BASE/enabled" ]; do
 { date; cat /proc/uptime; cat /sys/class/thermal/thermal_zone0/temp; pidof audioserver android.hardware.audio.service; cat /proc/loadavg; } >> "$SESSION/pulse.txt"
 if [ "$(wc -c < "$SESSION/pulse.txt")" -gt 131072 ]; then
  tail -c 65536 "$SESSION/pulse.txt" > "$SESSION/pulse.new" && mv "$SESSION/pulse.new" "$SESSION/pulse.txt"
 fi
 snapshot
 kill -0 "$child" 2>/dev/null || break
 sleep 60 &
 waiter=$!
 wait "$waiter"
done
