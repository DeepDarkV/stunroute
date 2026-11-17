#!/system/bin/sh
########################################
# stunroute 看门狗
#   每 30 秒检查一次 daemon，不在就拉起来。
#   由 service.sh 启动，自身单例。
########################################

MODDIR=$(dirname "$0")
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/stunroute
DATA=/data/adb/stunroute
DEX="$MODDIR/bin/stunroute.dex"
MAIN=com.eta.stunroute.Main
LOG="$DATA/logs/watchdog.log"

mkdir -p "$DATA/logs" "$DATA/runtime"

alive() { pgrep -f "app_process.*$MAIN" >/dev/null 2>&1; }

while :; do
  if ! alive; then
    echo "[$(date '+%F %T')] daemon 不在，拉起" >> "$LOG"
    setsid nohup /system/bin/app_process \
      -Djava.class.path="$DEX" \
      /system/bin $MAIN "$DATA" "$MODDIR" \
      >> "$DATA/logs/boot.log" 2>&1 &
    sleep 5
  fi
  # 日志超过 128KB 就截断
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 131072 ]; then
    tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
  fi
  sleep 30
done
