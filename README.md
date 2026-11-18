# stunroute — STUN 端点自动路由

把 DNS TXT 里发布的 STUN 映射端点（公网 ip:port）自动落到本机，
让任何 TCP/UDP 服务都能像固定地址一样访问，不再依赖 307 跳转。

## 它解决什么

lucky 之类的 STUN 打洞给出的公网端口是随机且会变的：

- HTTP 业务可以让服务端 307 到新端口，客户端无感；
- SSH / SMB / RDP / 游戏 / 数据库这类没有跳转机制，端口一变就得手改。

stunroute 的做法：

```
lucky 打洞成功
   └─(DDNS/API)→ _stun.example.com  TXT  "v=1 ssh=1.2.3.4:2222"
                        │
        stunroute 每 60s 查一次 TXT
                        │
        ① NetProxy(sing-box) DNS: ssh.example.com → 127.0.0.10
        ② iptables nat: 127.0.0.10:22 → 1.2.3.4:2222  (DNAT + MASQUERADE)
                        │
应用访问 ssh.example.com:22 → 直连最新公网端点（不经过任何中继，速度=直连）
```

- 客户端地址**完全不变**（域名 + 原端口），换端口时新连接立刻走新端点；
- 没有常驻转发进程，规则变化只是替换一条 iptables 规则，几乎零开销；
- 走的是本机 socket 直连，不经过中转服务器，也不占它的带宽。

## 安装

```
ksud module install stunroute-*.zip
```

首次安装需要重启一次（KernelSU 的 modules_update 机制），
或手动同步：`cp -rf /data/adb/modules_update/stunroute/. /data/adb/modules/stunroute/`。

## 配置

`/data/adb/stunroute/services.conf`（升级不覆盖）：

```
#dns_server=223.5.5.5
#interval=60
#dns_alias=1
#netproxyctl=/data/adb/modules/netproxy/bin/netproxyctl

# 名称|域名|回环地址|端口|协议|TXT记录名|键
nas-ssh|ssh.example.com|127.0.0.10|22|tcp|_stun.example.com|ssh
nas-smb|smb.example.com|127.0.0.11|445|tcp|_stun.example.com|smb
nas-rdp|rdp.example.com|127.0.0.12|3389|tcp|_stun.example.com|rdp
```

- 每个服务给一个不同的 `127.0.0.x`，端口就能保持原样、互不冲突；
- **拦截地址留空（或写 `auto`）= 跟随 TXT 里的 IP**：公网 IP 变化时，
  iptables 的匹配目标会自动换成新 IP（客户端域名解析跟着变，规则也必须跟着变）。
  用于"客户端先解析到公网 IP、再由本机拦截该 IP 的固定端口"这种场景；
- `域名` 留空则只做 IP 映射，客户端直接用 `回环地址:端口`；
- `dns_alias=1` 时自动把域名写进 NetProxy 的 DNS（sing-box 热加载，不断网）。

### TXT 内容支持的格式

```
v=1 ip=1.2.3.4 port=2222          # 单服务
v=1 ssh=1.2.3.4:2222 smb=1.2.3.4:445   # 多服务表，按键取
{"ssh":"1.2.3.4:2222"}            # JSON
```

IPv6 端点写 `[240e::1]:2222`。

## Web 管理界面

守护进程自带一个轻量 HTTP 服务，**只监听 127.0.0.1**：

```
http://127.0.0.1:8767/
```

两种打开方式：

1. **KernelSU 管理器**：模块卡片底部的「文档」图标（`webroot/index.html` 存在即出现），
   在管理器内置 WebView 里直接使用，无需浏览器；
2. 浏览器：`am start -a android.intent.action.VIEW -d http://127.0.0.1:8767/`

> WebUI 里页面来源是本地 file，直接 fetch 127.0.0.1 会被跨源拦截，
> 所以页面会通过 `ksu.exec` + busybox wget 调同一套接口（`window.ksu.exec` 返回的是字符串）。

界面功能：

- **全局设置**：DNS 服务器、轮询间隔、DNS 别名开关、管理端口；
- **服务映射**：卡片式增删改，实时显示「回环地址:端口 → 当前端点」和就绪状态；
  每条还有「NetProxy」按钮：**完整呈现这条映射涉及的所有改动**——
  实际生效的 `iptables` DNAT / MASQUERADE 规则、在 NetProxy `singbox/dns` 里对应的规则
  （JSON 可直接改，「应用」写入、「清空」删除），以及等价的手工命令；
- **导出 / 导入配置**：全局设置卡片下方两个按钮。
  导出为一份 JSON（`app` / `version` / `globals` / `services`），可复制或另存；
  导入支持粘贴或选择文件，写入后立即重建全部映射（iptables、DNS 别名）。
  重装模块后导入一次即可恢复所有映射，不用重新配——NetProxy 本身需先装好。
  **内网 DNS 支持多组**（`#lan_dns=` 每行一组，格式 `后缀1,后缀2|服务器|例外1,例外2`）：

  ```
  #lan_dns=corp.example.com|192.0.2.53|vpn.example.com
  #lan_dns=corp.lan,lan|10.0.0.53|
  ```

  每组会生成独立的 `dns-lan` / `dns-lan-1` … server 与后缀规则，例外统一走 `dns-direct`；
  删掉某一组后，对应的 server 和规则会自动清理。这些参数都在 `globals.lan_groups` 里，
  随导出/导入一起走。
- **全局改动**：模块对系统做的、不属于某条映射的修改都列在这里——
  NetProxy `singbox/dns` 的守护规则（`dns-lan` server、`vpn.example.com` 公网例外、`corp.example.com` 后缀路由）、
  两条 iptables 链的完整内容、OUTPUT/POSTROUTING 挂载状态、服务数与看门狗状态；
- **TXT 记录**：一键查询各记录的真实内容，直接看到解析结果；
- **运行日志**：最近 120 行，可自动刷新。

保存即生效（守护进程立刻重跑一轮），改端口会就地重启 Web 服务。

接口（可脚本化）：

```
GET  /api/state                 运行状态、全局设置、服务列表与当前端点
POST /api/config                保存整体配置（JSON）
GET  /api/txt?name=_x&key=k     查询 TXT 并解析
GET  /api/logs?lines=120        日志尾部
POST /api/action                 {"action":"refresh"|"clean"|"dnsresync"}
```

## 自愈与守护

模块里所有"配置过的规则"都由守护进程持续盯着，不会因为 NetProxy 更新 / WebUI 保存 / 进程被杀而丢失：

| 守护对象 | 机制 | 实测恢复时间 |
|---|---|---|
| stunroute 进程 | `watchdog.sh` 每 30s 检查，挂了用 `setsid` 拉起 | ≤30s |
| Web 管理界面 | 端口被旧进程占用导致 bind 失败时，每 20s 重试 | ≤20s |
| iptables 映射 | 每轮检查 `ETA_STUN` 链是否还在，缺失即重建 | ≤60s |
| NetProxy 内网 DNS（`*.corp.example.com → 192.0.2.53`） | 每分钟检查 `singbox/dns` 分区，被冲掉就增量补回 | ≤40s |

开机时 `service.sh` 会先拉起 daemon，再启动 watchdog（单例检查，不会重复启动）。

## 命令

```
/data/adb/modules/stunroute/ctl.sh status    # 状态、规则、日志
/data/adb/modules/stunroute/ctl.sh log 100   # 看日志
/data/adb/modules/stunroute/ctl.sh restart
/data/adb/modules/stunroute/ctl.sh clean     # 清掉 iptables 规则
/data/adb/modules/stunroute/ctl.sh add 名称 域名 回环地址 端口 协议 TXT记录 [键]
```

## 发布 TXT（关键前提）

模块只负责"读"，TXT 得有人写。lucky 的 DDNS / Webhook 或任意脚本都可以，
例如 Cloudflare：

```sh
curl -s -X PATCH "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$REC_ID" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data "{\"type\":\"TXT\",\"name\":\"_stun.example.com\",\"content\":\"v=1 ip=$IP port=$PORT\",\"ttl\":60}"
```

要点：

- TTL 用 30–60s，避免公共 DNS 缓存导致延迟；
- 每次 STUN 端口变化都要重写；
- 多服务可以合并到一条 TXT，减少请求。

## 已知限制

- TXT 有传播延迟，端口切换后最多约 `interval + TTL` 秒内新连接才走新端点；
- 端点变化时**已建立**的连接不会迁移（conntrack 保持旧映射），会自行超时；
- 依赖 Root（iptables + 修改 NetProxy 配置），非 Root 环境不可用；
- 若在 NetProxy 的 WebUI 里重存 DNS 配置，注入的别名会被覆盖，
  守护进程会在下一轮自动补回（最多 60 秒）。
