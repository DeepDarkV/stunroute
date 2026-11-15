#!/system/bin/sh
########################################
# 恢复 NetProxy 的内网 DNS 规则
#
#   *.cdxmz.top  ->  192.168.9.254 (dns-lan)
#
# 背景：这条规则写在 NetProxy 的 singbox/dns 分区里。
# 一旦在 NetProxy 的 WebUI 里重新保存 DNS 设置，分区会被重新生成、
# 规则被冲掉，表现为 VPN 下 server.cdxmz.top 解析不出来。
# 跑一次本脚本即可恢复（幂等，已存在则直接退出）。
#
# 用法：sh restore-dns-lan.sh [--check]
#   --check  只检查不修改
########################################

NP=${NETPROXYCTL:-/data/adb/modules/netproxy/bin/netproxyctl}
D=/data/adb/stunroute
WORK=$D/runtime
LANDNS=192.168.9.254
SUFFIX=cdxmz.top

[ -x "$NP" ] || { echo "找不到 netproxyctl: $NP"; exit 1; }
mkdir -p "$WORK"

RAW=$WORK/dns.raw.json
$NP config read singbox/dns > "$RAW" 2>/dev/null || { echo "读取 dns 分区失败"; exit 1; }

if grep -q 'dns-lan' "$RAW"; then
  echo "✓ dns-lan 规则已存在，无需修改"
  exit 0
fi

if [ "$1" = "--check" ]; then
  echo "✗ 缺少 dns-lan 规则（$SUFFIX → $LANDNS）"
  exit 2
fi

REV=$(sed -n 's/.*"revision":"\([0-9a-f]*\)".*/\1/p' "$RAW")
[ -n "$REV" ] || { echo "拿不到 revision，放弃"; exit 1; }

# 取出分区内容（JSON 字符串转义还原）
sed -n 's/.*"content":"\(.*\)","revision":"[0-9a-f]*","target":"[^"]*"}}$/\1/p' "$RAW" > "$WORK/dns.esc"
sed -e 's/\\"/"/g' -e 's/\\n/\n/g' -e 's/\\\\/\\/g' "$WORK/dns.esc" > "$WORK/dns.json"

# 备份现有分区
cp -f "$WORK/dns.json" "$WORK/dns.before-dns-lan.$(date +%Y%m%d-%H%M%S).json"

# 插入 server 定义 + 域名规则
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
  echo "✗ 生成失败（未匹配到 servers/rules 结构），分区格式可能已变"
  exit 1
fi

OUT=$($NP config apply singbox/dns "$WORK/dns.new.json" --revision "$REV" 2>&1)
case "$OUT" in
  *'"ok":true'*) echo "✓ 已恢复：$SUFFIX → $LANDNS（revision ${REV%????????}…）" ;;
  *) echo "✗ 应用失败：$OUT"; exit 1 ;;
esac
