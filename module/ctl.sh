#!/system/bin/sh
########################################
# stunroute 控制脚本
# 用法: ctl.sh status|start|stop|restart|log|clean|add
########################################

MODDIR=${0%/*}
[ -d "$MODDIR/bin" ] || MODDIR=/data/adb/modules/stunroute
DATA=/data/adb/stunroute
DEX="$MODDIR/bin/stunroute.dex"
CFG="$DATA/services.conf"
MAIN=com.eta.stunroute.Main

pidof_main() { pgrep -f "$MAIN" 2>/dev/null | head -1; }

start() {
  mkdir -p "$DATA/logs" "$DATA/runtime"
  pkill -f "$MAIN" 2>/dev/null
  sleep 1
  setsid nohup /system/bin/app_process -Djava.class.path="$DEX" /system/bin $MAIN "$DATA" "$MODDIR" \
    >> "$DATA/logs/boot.log" 2>&1 &
  sleep 3
  p=$(pidof_main)
  [ -n "$p" ] && echo "stunroute 已启动 (pid $p)" || echo "启动失败，查看 $DATA/logs/boot.log"
}

stop() {
  pkill -f "$MAIN" 2>/dev/null
  sleep 1
  echo "stunroute 已停止"
}

status() {
  p=$(pidof_main)
  echo "== 进程: ${p:-未运行}"
  echo "== 配置: $CFG"
  grep -v '^#' "$CFG" 2>/dev/null | grep -v '^$'
  echo "== 状态:"
  cat "$DATA/state.properties" 2>/dev/null
  echo "== iptables DNAT:"
  iptables -t nat -L ETA_STUN -n --line-numbers 2>/dev/null || echo "  (无映射链)"
  echo "== iptables MASQUERADE:"
  iptables -t nat -L ETA_STUN_POST -n 2>/dev/null | tail -n +3
  echo "== 最近日志:"
  tail -n 12 "$DATA/logs/stunroute.log" 2>/dev/null
}

clean() {
  iptables -t nat -D OUTPUT -j ETA_STUN 2>/dev/null
  iptables -t nat -D POSTROUTING -j ETA_STUN_POST 2>/dev/null
  iptables -t nat -F ETA_STUN 2>/dev/null
  iptables -t nat -X ETA_STUN 2>/dev/null
  iptables -t nat -F ETA_STUN_POST 2>/dev/null
  iptables -t nat -X ETA_STUN_POST 2>/dev/null
  echo "iptables 规则已清理"
}

add() {
  # add 名称 域名 回环地址 端口 协议 TXT记录 [键]
  [ $# -ge 6 ] || { echo "用法: ctl.sh add 名称 域名 回环地址 端口 协议 TXT记录 [键]"; exit 2; }
  echo "$1|$2|$3|$4|$5|$6|${7:-}" >> "$CFG"
  echo "已追加到 $CFG"
  start
}

case "$1" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  log)     tail -n "${2:-40}" "$DATA/logs/stunroute.log" ;;
  clean)   clean ;;
  add)     shift; add "$@" ;;
  *)       echo "用法: $0 status|start|stop|restart|log [行数]|clean|add ..." ;;
esac
