#!/system/bin/sh
########################################
# KernelSU 管理器「操作」按钮
########################################

MODDIR=${0%/*}
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/stunroute
DATA=/data/adb/stunroute
MAIN=com.eta.stunroute.Main

p=$(pgrep -f "$MAIN" 2>/dev/null | head -1)
echo "stunroute $(grep '^version=' "$MODDIR/module.prop" | cut -d= -f2)"
echo "进程: ${p:-未运行}"
echo "---- 映射 ----"
iptables -t nat -L ETA_STUN -n 2>/dev/null | tail -n +3 || echo "(无)"
echo "---- 端点 ----"
grep '\.target=' "$DATA/state.properties" 2>/dev/null | sed 's/\.target=/ -> /'
echo "---- 最近 ----"
tail -n 6 "$DATA/logs/stunroute.log" 2>/dev/null
