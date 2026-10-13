#!/system/bin/sh
# One-time ROOT migration. Keep original history and any unrelated soak files.
set -e
[ "$(id -u)" = 0 ] || exit 1
OLD=/data/adb/living-player/diagnostics
NEW=/data/adb/system-diagnostics
[ -f "$NEW/legacy-imported" ] && exit 0
[ -f "$NEW/recorder.sh" ] || exit 2
[ -d "$OLD/current" ] || exit 3
# Never overwrite an independently started recorder or a partial import.
[ ! -e "$NEW/logs" ] && [ ! -e "$NEW/legacy-import.pending" ] || exit 4
enabled=0
[ ! -f "$OLD/enabled" ] || enabled=1
committed=0
rollback() {
 if [ "$committed" = 0 ] && [ "$enabled" = 1 ]; then
  nohup sh /data/adb/living-player/diagnostics.sh >/dev/null 2>&1 </dev/null &
 fi
}
trap rollback EXIT
p=$(cat "$OLD/recorder.pid" 2>/dev/null || true)
case "$p" in
 ''|*[!0-9]*) ;;
 *)
  if kill -0 "$p" 2>/dev/null; then
   cmd=$(tr '\000' ' ' < "/proc/$p/cmdline")
   case "$cmd" in *"/data/adb/living-player/diagnostics.sh"*) ;; *) exit 5;; esac
   kill -TERM "$p"
   # The old shell can defer its TERM trap while waiting for its 60s sleep.
   # Some Android kernels omit /proc/.../children; use the process table.
   for child in $(ps -A -o PID,PPID,NAME | awk -v parent="$p" '$2 == parent && $3 == "sleep" {print $1}'); do
    childcmd=$(tr '\000' ' ' < "/proc/$child/cmdline" 2>/dev/null || true)
    case "$childcmd" in "sleep 60 "|"sleep 60") kill -TERM "$child" 2>/dev/null || true;; esac
   done
   n=0
   while kill -0 "$p" 2>/dev/null && [ "$n" -lt 10 ]; do sleep 1; n=$((n+1)); done
   kill -0 "$p" 2>/dev/null && exit 6
  fi
 ;;
esac
umask 077
mkdir -p "$NEW/legacy-import.pending"
chmod 700 "$NEW" "$NEW/legacy-import.pending"
for session in current previous-1 previous-2; do
 [ ! -d "$OLD/$session" ] || cp -a "$OLD/$session" "$NEW/legacy-import.pending/$session"
done
[ "$enabled" != 1 ] || touch "$NEW/legacy-import.pending/enabled"
mv "$NEW/legacy-import.pending" "$NEW/logs"
[ ! -f "$OLD/enabled" ] || mv "$OLD/enabled" "$OLD/enabled.migrated"
printf 'Original history preserved at %s\n' "$OLD" > "$NEW/legacy-imported"
committed=1
if [ "$enabled" = 1 ]; then
 nohup sh "$NEW/recorder.sh" >/dev/null 2>&1 </dev/null &
fi
echo migration-complete
exit 0
