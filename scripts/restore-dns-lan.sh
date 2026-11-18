#!/system/bin/sh
########################################
# 恢复 NetProxy 的内网 DNS 规则（应急用；平时由守护进程自动维护）
#
# 参数从 /data/adb/stunroute/services.conf 的 #lan_dns= 行读取：
#   #lan_dns=后缀1,后缀2|服务器|例外1,例外2
#
# 用法：sh restore-dns-lan.sh [--check]
#   --check  只检查不修改
########################################

NP=${NETPROXYCTL:-/data/adb/modules/netproxy/bin/netproxyctl}
DATA=/data/adb/stunroute
CONF=$DATA/services.conf
WORK=$DATA/runtime

[ -x "$NP" ] || { echo "找不到 netproxyctl: $NP"; exit 1; }
[ -f "$CONF" ] || { echo "找不到配置: $CONF"; exit 1; }
mkdir -p "$WORK"

LINE=$(grep '^#lan_dns=' "$CONF" 2>/dev/null | head -1 | sed 's/^#lan_dns=//')
if [ -z "$LINE" ]; then
  echo "配置里没有 #lan_dns= 行，跳过"
  exit 1
fi
SUFFIX=$(printf '%s' "$LINE" | cut -d'|' -f1 | cut -d',' -f1)
LANDNS=$(printf '%s' "$LINE" | cut -d'|' -f2)
echo "从配置读取：$SUFFIX → $LANDNS"

RAW=$WORK/dns.raw.json
$NP config read singbox/dns > "$RAW" 2>/dev/null || { echo "读取 dns 分区失败"; exit 1; }

if grep -q 'dns-lan' "$RAW"; then
  echo "✓ dns-lan 规则已存在，无需修改"
  exit 0
fi

if [ "$1" = "--check" ]; then
  echo "✗ 缺少 dns-lan 规则"
  exit 2
fi

REV=$(sed -n 's/.*"revision":"\([0-9a-f]*\)".*/\1/p' "$RAW")
[ -n "$REV" ] || { echo "拿不到 revision，放弃"; exit 1; }

sed -n 's/.*"content":"\(.*\)","revision":"[0-9a-f]*","target":"[^"]*"}}$/\1/p' "$RAW" > "$WORK/dns.esc"
sed -e 's/\\"/"/g' -e 's/\\n/\n/g' -e 's/\\\\/\\/g' "$WORK/dns.esc" > "$WORK/dns.json"
cp -f "$WORK/dns.json" "$WORK/dns.before-dns-lan.$(date +%Y%m%d-%H%M%S).json"

awk -v ip="$LANDNS" -v sfx="$SUFFIX" '
BEGIN { s = 0; r = 0 }
{
  if (!s && $0 ~ /"servers"[[:space:]]*:[[:space:]]*\[/) {
    print
    print "      {"
    print "        \"tag\": \"dns-lan\","
    print "        \"type\": \"udp\","
    print "        \"server\": \"" ip "\""
    print "      },"
    s = 1
    next
  }
  if (!r && $0 ~ /"rules"[[:space:]]*:[[:space:]]*\[/) {
    print
    print "      {\"domain_suffix\": [\"" sfx "\"], \"action\": \"route\", \"server\": \"dns-lan\"},"
    r = 1
    next
  }
  print
}
' "$WORK/dns.json" > "$WORK/dns.new.json"

if ! grep -q '"dns-lan"' "$WORK/dns.new.json"; then
  echo "✗ 生成失败（未匹配到 servers/rules 结构）"
  exit 1
fi

OUT=$($NP config apply singbox/dns "$WORK/dns.new.json" --revision "$REV" 2>&1)
case "$OUT" in
  *'"ok":true'*) echo "✓ 已恢复：$SUFFIX → $LANDNS" ;;
  *) echo "✗ 应用失败：$OUT"; exit 1 ;;
esac
