#!/system/bin/sh
########################################
# stunroute 开机启动
########################################

MODDIR=${0%/*}
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/stunroute
DATA=/data/adb/stunroute
[ -f "$MODDIR/disable" ] && exit 0

mkdir -p "$DATA/logs" "$DATA/runtime"
[ -f "$DATA/services.conf" ] || cp "$MODDIR/default-services.conf" "$DATA/services.conf" 2>/dev/null

LOG="$DATA/logs/boot.log"

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 60 ]; do
  sleep 2
  i=$((i + 1))
done

# 让 NetProxy / 网络先起来
sleep 12

pkill -f "com.eta.stunroute.Main" 2>/dev/null
sleep 1

echo "[$(date '+%F %T')] starting stunroute" >> "$LOG"
setsid nohup /system/bin/app_process \
  -Djava.class.path="$MODDIR/bin/stunroute.dex" \
  /system/bin com.eta.stunroute.Main "$DATA" "$MODDIR" \
  >> "$LOG" 2>&1 &
