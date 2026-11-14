#!/system/bin/sh
########################################
# stunroute 安装后处理
########################################

MODDIR=${MODPATH:-$(dirname "$0")}
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/stunroute
DATA=/data/adb/stunroute

mkdir -p "$DATA/logs" "$DATA/runtime"
[ -f "$DATA/services.conf" ] || cp "$MODDIR/default-services.conf" "$DATA/services.conf" 2>/dev/null

chmod 755 "$MODDIR/ctl.sh" "$MODDIR/service.sh" "$MODDIR/action.sh" "$MODDIR/customize.sh" "$MODDIR/uninstall.sh" 2>/dev/null
chmod 644 "$MODDIR/default-services.conf" "$MODDIR/bin/stunroute.dex" 2>/dev/null

ui_print "- stunroute 已安装"
ui_print "- 配置: $DATA/services.conf"
ui_print "- 状态: $MODDIR/ctl.sh status"
