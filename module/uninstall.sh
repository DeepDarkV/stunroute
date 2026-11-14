#!/system/bin/sh
########################################
# stunroute 卸载清理
########################################

pkill -f "com.eta.stunroute.Main" 2>/dev/null

iptables -t nat -D OUTPUT -j ETA_STUN 2>/dev/null
iptables -t nat -D POSTROUTING -j ETA_STUN_POST 2>/dev/null
iptables -t nat -F ETA_STUN 2>/dev/null
iptables -t nat -X ETA_STUN 2>/dev/null
iptables -t nat -F ETA_STUN_POST 2>/dev/null
iptables -t nat -X ETA_STUN_POST 2>/dev/null

# 配置目录保留（含用户服务列表），如需彻底清空请手动 rm -rf /data/adb/stunroute
