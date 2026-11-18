package com.eta.stunroute;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * stunroute: 把 DNS TXT 里发布的 STUN 映射端点 (ip:port) 自动落到本机。
 *
 * 原理：
 *   1) 给每个服务分配一个回环地址 VIP（127.0.0.x），通过 NetProxy(sing-box) 的 DNS
 *      分区注入 A 记录，应用访问域名时解析到 VIP。
 *   2) iptables nat OUTPUT 里把 VIP:port DNAT 到 TXT 里发布的最新 ip:port，
 *      并在 POSTROUTING 做 MASQUERADE。
 *   3) 定时查询 TXT，端点变化时就地替换规则（不需要任何常驻中继进程）。
 */
public final class Main {

    static final String VERSION = "0.2.1";
    static Path base;
    static Path logFile;
    static Path stateFile;
    static final Object LOG_LOCK = new Object();

    static String dnsServer = "223.5.5.5";
    static int dnsPort = 53;
    static int interval = 60;
    static boolean dnsAlias = true;
    static String npctl = "/data/adb/modules/netproxy/bin/netproxyctl";
    static int webPort = 8767;
    static String modDir = "/data/adb/modules/stunroute";
    static volatile boolean kick = false;
    static volatile long lastWebRetry = 0;
    static final LinkedHashMap<String, Service> services = new LinkedHashMap<>();

    static String lanSuffix = "cdxmz.top";
    static String lanServer = "192.168.9.254";
    static String lanException = "vpn6.cdxmz.top";

    static long cfgStamp = -1;

    public static void main(String[] args) throws Exception {
        base = Paths.get(args.length > 0 ? args[0] : "/data/adb/stunroute");
        if (args.length > 1 && !args[1].isEmpty()) modDir = args[1];
        Path logDir = base.resolve("logs");
        Files.createDirectories(logDir);
        logFile = logDir.resolve("stunroute.log");
        stateFile = base.resolve("state.properties");

        log("==== stunroute " + VERSION + " start, base=" + base + ", mod=" + modDir);
        refreshConfig(true);
        Web.restart(webPort, modDir + "/webroot");

        long next = 0;
        while (true) {
            try {
                refreshConfig(false);
                long now = System.currentTimeMillis();
                if (kick || now >= next) {
                    kick = false;
                    doCycle();
                    next = System.currentTimeMillis() + Math.max(10, interval) * 1000L;
                }
            } catch (Throwable t) {
                log("循环异常: " + t);
            }
            Thread.sleep(500);
        }
    }

    static void doCycle() {
        // Web 服务自愈：在旧进程还没释放端口时启动会 BindException，这里定期重试
        if (Web.server == null && System.currentTimeMillis() - lastWebRetry > 20_000L) {
            lastWebRetry = System.currentTimeMillis();
            Web.restart(webPort, modDir + "/webroot");
        }
        // 守护 NetProxy 里的内网 DNS 规则（WebUI 重存配置会冲掉它）
        try {
            NetProxyDns.ensureLan(npctl);
        } catch (Throwable t) {
            log("dns-lan 检查失败: " + t);
        }
        if (dnsAlias) {
            try {
                NetProxyDns.ensure(npctl, services);
            } catch (Throwable t) {
                log("dns 别名同步失败: " + t);
            }
        }
        synchronized (services) {
            for (Service s : services.values()) refreshEndpoint(s);
        }
        Iptables.sync(services);
        saveState();
    }

    // ---------------- 配置 ----------------

    static void refreshConfig(boolean force) {
        Path f = base.resolve("services.conf");
        if (!Files.exists(f)) {
            log("缺少 " + f + "，使用空配置");
            return;
        }
        try {
            long stamp = Files.getLastModifiedTime(f).toMillis();
            if (!force && stamp == cfgStamp) return;
            cfgStamp = stamp;

            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            LinkedHashMap<String, Service> next = new LinkedHashMap<>();
            String dns = dnsServer;
            int dnsP = dnsPort;
            int itv = interval;
            boolean alias = dnsAlias;
            String ctl = npctl;
            int wp = webPort;
            String lanS = lanSuffix;
            String lanV = lanServer;
            String lanE = lanException;

            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String c = line.substring(1).trim();
                    if (c.startsWith("dns_server=")) {
                        String v = c.substring(11).trim();
                        int c1 = v.indexOf(':');
                        if (c1 > 0 && v.indexOf(':', c1 + 1) < 0) {
                            dns = v.substring(0, c1);
                            dnsP = parseIntSafe(v.substring(c1 + 1), 53);
                        } else {
                            dns = v;
                            dnsP = 53;
                        }
                    }
                    else if (c.startsWith("interval=")) itv = parseIntSafe(c.substring(9).trim(), itv);
                    else if (c.startsWith("dns_alias=")) alias = "1".equals(c.substring(10).trim()) || "true".equalsIgnoreCase(c.substring(10).trim());
                    else if (c.startsWith("netproxyctl=")) ctl = c.substring(12).trim();
                    else if (c.startsWith("web_port=")) wp = parseIntSafe(c.substring(9).trim(), wp);
                    else if (c.startsWith("lan_dns_suffix=")) lanS = c.substring(15).trim();
                    else if (c.startsWith("lan_dns_server=")) lanV = c.substring(15).trim();
                    else if (c.startsWith("lan_dns_exception=")) lanE = c.substring(18).trim();
                    continue;
                }
                Service s = Service.parse(line);
                if (s != null) next.put(s.name, s);
            }
            dnsServer = dns;
            dnsPort = dnsP;
            interval = itv;
            dnsAlias = alias;
            npctl = ctl;
            webPort = wp;
            if (!lanS.isEmpty()) lanSuffix = lanS;
            if (!lanV.isEmpty()) lanServer = lanV;
            if (!lanE.isEmpty()) lanException = lanE;
            synchronized (services) {
                LinkedHashMap<String, Service> merged = new LinkedHashMap<>();
                for (Service s : next.values()) {
                    Service old = services.get(s.name);
                    if (old != null && old.vip.equals(s.vip) && old.port == s.port) {
                        s.curIp = old.curIp;
                        s.curPort = old.curPort;
                        s.updatedAt = old.updatedAt;
                    }
                    merged.put(s.name, s);
                }
                services.clear();
                services.putAll(merged);
            }
            log("配置已载入: " + next.size() + " 个服务, dns=" + dnsServer + ":" + dnsPort + ", interval=" + interval + "s, alias=" + dnsAlias + ", web=" + webPort);
        } catch (Throwable t) {
            log("读取配置失败: " + t);
        }
    }

    // ---------------- 端点刷新 ----------------

    static void refreshEndpoint(Service s) {
        if (s.txt == null || s.txt.isEmpty()) return;
        try {
            List<String> records = Dns.queryTxt(s.txt, dnsServer, dnsPort, 4000);
            if (records.isEmpty()) {
                log("[" + s.name + "] TXT 无记录: " + s.txt);
                return;
            }
            String payload = records.get(0);
            String[] ep = parseEndpoint(payload, s.key);
            if (ep == null) {
                log("[" + s.name + "] 无法解析 TXT: " + payload);
                return;
            }
            String ip = ep[0];
            int port = Integer.parseInt(ep[1]);
            boolean changed = !ip.equals(s.curIp) || port != s.curPort;
            s.curIp = ip;
            s.curPort = port;
            if (changed) {
                log("[" + s.name + "] 端点更新: " + ip + ":" + port);
                s.updatedAt = System.currentTimeMillis();
            }
            // vip 留空/auto 时跟随端点 IP：公网 IP 变了，拦截规则也要跟着换
            if (s.vipAuto && !ip.equals(s.vip)) {
                String old = s.vip;
                s.vip = ip;
                log("[" + s.name + "] 拦截地址跟随端点: " + (old == null || old.isEmpty() ? "(空)" : old) + " → " + ip);
            }
        } catch (Throwable t) {
            log("[" + s.name + "] 查询 TXT 失败: " + t);
        }
    }

    /**
     * 支持:
     *   ip=1.2.3.4 port=1567
     *   ssh=1.2.3.4:2222        (按 key 取)
     *   {"ssh":"1.2.3.4:2222"}  (按 key 取)
     *   1.2.3.4:1567
     *   [240e::1]:1567
     */
    static String[] parseEndpoint(String raw, String key) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.isEmpty()) return null;

        // JSON 形态
        if (p.startsWith("{")) {
            String v = jsonPick(p, key);
            if (v == null) return null;
            return parseHostPort(v);
        }
        // key=host:port 形态
        if (key != null && !key.isEmpty()) {
            for (String tok : p.split("[\\s;,]+")) {
                int eq = tok.indexOf('=');
                if (eq > 0 && tok.substring(0, eq).equalsIgnoreCase(key)) {
                    String[] r = parseHostPort(tok.substring(eq + 1));
                    if (r != null) return r;
                }
            }
        }
        // ip=... port=...
        String ip = field(p, "ip");
        String port = field(p, "port");
        if (ip != null && port != null) return new String[]{ip, port};
        // 裸 host:port
        return parseHostPort(p);
    }

    static String jsonPick(String json, String key) {
        if (key == null || key.isEmpty()) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int c = json.indexOf(':', i + needle.length());
        if (c < 0) return null;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    static String field(String s, String name) {
        String[] toks = s.split("[\\s;,]+");
        for (String t : toks) {
            int eq = t.indexOf('=');
            if (eq > 0 && t.substring(0, eq).equalsIgnoreCase(name)) return t.substring(eq + 1);
        }
        return null;
    }

    static String[] parseHostPort(String v) {
        String x = v.trim();
        if (x.startsWith("[")) {
            int rb = x.indexOf(']');
            if (rb < 0) return null;
            String host = x.substring(1, rb);
            int c = x.indexOf(':', rb);
            if (c < 0) return new String[]{host, "0"};
            return new String[]{host, x.substring(c + 1).trim()};
        }
        // 纯 IPv6 无端口
        if (x.indexOf(':') != x.lastIndexOf(':')) return new String[]{x, "0"};
        int c = x.lastIndexOf(':');
        if (c < 0) return null;
        String host = x.substring(0, c).trim();
        String port = x.substring(c + 1).trim();
        if (host.isEmpty() || !port.matches("\\d{1,5}")) return null;
        return new String[]{host, port};
    }

    // ---------------- 配置导出 / 导入 ----------------

    static String exportJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "stunroute");
        m.put("version", VERSION);
        m.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()));
        m.put("globals", globalsMap());
        List<Object> list = new ArrayList<>();
        synchronized (services) {
            for (Service s : services.values()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", s.name);
                o.put("domain", nz(s.domain));
                o.put("vip", s.vipAuto ? "" : nz(s.vip));
                o.put("port", s.port);
                o.put("proto", nz(s.proto));
                o.put("txt", nz(s.txt));
                o.put("key", nz(s.key));
                list.add(o);
            }
        }
        m.put("services", list);
        return Json.stringify(m);
    }

    static String importJson(String body) {
        try {
            Object o = Json.parse(body);
            if (!(o instanceof Map)) return "{\"ok\":false,\"error\":\"配置不是 JSON 对象\"}";
            Map<?, ?> m = (Map<?, ?>) o;
            String app = Json.s(m.get("app"));
            if (app != null && !"stunroute".equals(app)) {
                return "{\"ok\":false,\"error\":\"不是 stunroute 的配置\"}";
            }
            applyFromWeb(m);
            int n;
            synchronized (services) { n = services.size(); }
            return "{\"ok\":true,\"services\":" + n + "}";
        } catch (Throwable t) {
            log("导入配置失败: " + t);
            return "{\"ok\":false,\"error\":" + Json.stringify(String.valueOf(t)) + "}";
        }
    }

    // ---------------- 每条映射在 NetProxy 侧的配置 ----------------

    /** 该映射在 NetProxy dns 分区里对应的规则（没有域名则返回空串）。 */
    static String describeNpRule(Service s) {
        if (s == null || s.domain == null || s.domain.isEmpty()) return "";
        String vip = (s.vip == null || s.vip.isEmpty()) ? "127.0.0.1" : s.vip;
        return "{\"domain\": [\"" + s.domain + "\"], \"action\": \"predefined\", \"answer\": [\""
                + s.domain + ". IN A " + vip + "\"]}";
    }

    static String buildNpScript(Service s, String rule) {
        if (rule == null || rule.isEmpty()) {
            return "# 该映射未配置域名，不需要修改 NetProxy\n"
                    + "# 只做 iptables DNAT（拦截 " + nz(s.vip) + ":" + s.port + " → TXT 端点）";
        }
        return "# 把下面这条规则加入 singbox/dns 的 rules（放在最前面）\n"
                + rule + "\n\n"
                + "NP=" + npctl + "\n"
                + "$NP config read singbox/dns > /tmp/dns.raw.json\n"
                + "# 提取 data.content 并插入上面的规则后：\n"
                + "$NP config apply singbox/dns /tmp/dns.json --revision <上一步的 revision>\n\n"
                + "# 也可以用本页的「应用」按钮，由守护进程直接写入";
    }

    static String findDnatRule(Service s) {
        if (s.vip == null || s.vip.isEmpty()) return "";
        String out = Iptables.sh("iptables -t nat -S ETA_STUN 2>/dev/null");
        for (String line : out.split("\n")) {
            if (line.contains("-d " + s.vip + "/32") && line.contains("--dport " + s.port + " ")) {
                return line.trim();
            }
        }
        return "";
    }

    static String findMasqRule(Service s) {
        if (s.curIp == null || s.curPort <= 0) return "";
        String out = Iptables.sh("iptables -t nat -S ETA_STUN_POST 2>/dev/null");
        for (String line : out.split("\n")) {
            if (line.contains("-d " + s.curIp + "/32") && line.contains("--dport " + s.curPort + " ")) {
                return line.trim();
            }
        }
        return "";
    }

    static String npInfoJson(String name) {
        Service s;
        synchronized (services) {
            s = services.get(name);
        }
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name);
        m.put("domain", nz(s.domain));
        m.put("vip", nz(s.vip));
        m.put("vipAuto", s.vipAuto);
        m.put("port", s.port);
        m.put("target", s.curIp == null ? "" : s.curIp + ":" + s.curPort);

        // iptables 侧：这条映射实际落地的两条规则
        Map<String, Object> ip = new LinkedHashMap<>();
        ip.put("dnat", findDnatRule(s));
        ip.put("masq", findMasqRule(s));
        m.put("iptables", ip);

        // NetProxy 侧：DNS 别名规则
        String rule = describeNpRule(s);
        m.put("rule", rule);
        m.put("script", buildNpScript(s, rule));
        boolean present = false;
        if (s.domain != null && !s.domain.isEmpty()) {
            String raw = NetProxyDns.run(npctl + " config read singbox/dns");
            present = raw != null && raw.contains(s.domain);
        }
        m.put("present", present);
        return Json.stringify(m);
    }

    /** 模块对系统/NetProxy 的全局改动汇总。 */
    static String globalInfoJson() {
        Map<String, Object> m = new LinkedHashMap<>();

        // NetProxy dns 分区的守护规则
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("lanRule",
                "{\"domain_suffix\": [\"" + lanSuffix + "\"], \"action\": \"route\", \"server\": \"dns-lan\"}");
        dns.put("exception",
                "{\"domain\": [\"" + lanException + "\"], \"action\": \"route\", \"server\": \"dns-direct\"}");
        dns.put("server",
                "{\"tag\": \"dns-lan\", \"type\": \"udp\", \"server\": \"" + lanServer + "\"}");
        String raw = NetProxyDns.run(npctl + " config read singbox/dns");
        boolean lanOk = raw != null && raw.contains("dns-lan");
        boolean exOk = raw != null && raw.contains(lanException);
        dns.put("lanPresent", lanOk);
        dns.put("exceptionPresent", exOk);
        m.put("dns", dns);

        // iptables 链
        m.put("dnatChain", Iptables.sh("iptables -t nat -S ETA_STUN 2>/dev/null").trim());
        m.put("postChain", Iptables.sh("iptables -t nat -S ETA_STUN_POST 2>/dev/null").trim());
        m.put("hookOutput", Iptables.shOk("iptables -t nat -C OUTPUT -j ETA_STUN"));
        m.put("hookPost", Iptables.shOk("iptables -t nat -C POSTROUTING -j ETA_STUN_POST"));

        // 其他
        m.put("serviceCount", services.size());
        m.put("routeLan", "sysctl net.ipv4.conf.all.route_localnet=1");
        m.put("watchdog", new File("/data/adb/modules/stunroute/watchdog.sh").exists());
        return Json.stringify(m);
    }


    /** 把编辑后的规则写入 NetProxy 的 dns 分区（同一域名的旧规则会被替换）。 */
    @SuppressWarnings("unchecked")
    static synchronized String applyNpRule(String name, String ruleText) {
        Service s;
        synchronized (services) {
            s = services.get(name);
        }
        if (s == null) return "{\"ok\":false,\"error\":\"未知服务\"}";
        if (s.domain == null || s.domain.isEmpty()) return "{\"ok\":false,\"error\":\"该映射没有域名\"}";

        String raw = NetProxyDns.run(npctl + " config read singbox/dns");
        if (raw == null) return "{\"ok\":false,\"error\":\"读取 dns 分区失败\"}";
        String content = NetProxyDns.extractContent(raw);
        String revision = NetProxyDns.extractField(raw, "revision");
        if (content == null || revision == null) return "{\"ok\":false,\"error\":\"解析 dns 分区失败\"}";

        try {
            Object parsed = Json.parse(content);
            Map<String, Object> root = (Map<String, Object>) parsed;
            Map<String, Object> dns = (Map<String, Object>) root.get("dns");
            if (dns == null) return "{\"ok\":false,\"error\":\"分区没有 dns 节点\"}";
            List<Object> rules = (List<Object>) dns.get("rules");
            if (rules == null) {
                rules = new ArrayList<>();
                dns.put("rules", rules);
            }
            final String domain = s.domain;
            rules.removeIf(r -> {
                String t = Json.stringify(r);
                return t.contains("\"" + domain + "\"");
            });
            if (ruleText != null && !ruleText.trim().isEmpty()) {
                Object nr = Json.parse(ruleText);
                if (!(nr instanceof Map)) return "{\"ok\":false,\"error\":\"规则不是合法 JSON 对象\"}";
                rules.add(0, nr);
            }
            Path tmp = base.resolve("runtime").resolve("dns.np.json");
            Files.createDirectories(tmp.getParent());
            Files.write(tmp, Json.stringify(root).getBytes(StandardCharsets.UTF_8));
            String out = NetProxyDns.run(npctl + " config apply singbox/dns " + tmp + " --revision " + revision);
            boolean ok = out != null && out.contains("\"ok\":true");
            log("NetProxy 规则已更新: " + name + " (" + domain + ") ok=" + ok);
            return "{\"ok\":" + ok + "}";
        } catch (Throwable t) {
            log("应用 NetProxy 规则失败: " + t);
            return "{\"ok\":false,\"error\":" + Json.stringify(String.valueOf(t)) + "}";
        }
    }

    // ---------------- Web 配置接口 ----------------

    static Map<String, Object> globalsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dns_server", dnsPort == 53 ? dnsServer : dnsServer + ":" + dnsPort);
        m.put("interval", interval);
        m.put("dns_alias", dnsAlias);
        m.put("web_port", webPort);
        m.put("netproxyctl", npctl);
        m.put("lan_dns_suffix", lanSuffix);
        m.put("lan_dns_server", lanServer);
        m.put("lan_dns_exception", lanException);
        return m;
    }

    static synchronized void applyFromWeb(Object body) throws IOException {
        Map<?, ?> m = body instanceof Map ? (Map<?, ?>) body : Collections.emptyMap();
        Object g = m.get("globals");
        if (g instanceof Map) {
            Map<?, ?> gm = (Map<?, ?>) g;
            String d = Json.s(gm.get("dns_server"));
            if (d != null && !d.trim().isEmpty()) {
                String v = d.trim();
                int c1 = v.indexOf(':');
                if (c1 > 0 && v.indexOf(':', c1 + 1) < 0) {
                    dnsServer = v.substring(0, c1);
                    dnsPort = parseIntSafe(v.substring(c1 + 1), 53);
                } else {
                    dnsServer = v;
                    dnsPort = 53;
                }
            }
            interval = Math.max(10, Json.i(gm.get("interval"), interval));
            dnsAlias = Json.b(gm.get("dns_alias"), dnsAlias);
            String ls = Json.s(gm.get("lan_dns_suffix"));
            if (ls != null && !ls.trim().isEmpty()) lanSuffix = ls.trim();
            String lv = Json.s(gm.get("lan_dns_server"));
            if (lv != null && !lv.trim().isEmpty()) lanServer = lv.trim();
            String le = Json.s(gm.get("lan_dns_exception"));
            if (le != null && !le.trim().isEmpty()) lanException = le.trim();
            int wp = Json.i(gm.get("web_port"), webPort);
            if (wp >= 1024 && wp <= 65535) webPort = wp;
        }

        List<Service> svcs = new ArrayList<>();
        Object ls = m.get("services");
        if (ls instanceof List) {
            for (Object o : (List<?>) ls) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> sm = (Map<?, ?>) o;
                Service s = new Service();
                s.name = nz(Json.s(sm.get("name")));
                s.domain = nz(Json.s(sm.get("domain")));
                s.vip = nz(Json.s(sm.get("vip")));
                s.port = Json.i(sm.get("port"), 0);
                s.proto = nz(Json.s(sm.get("proto")));
                if (s.proto.isEmpty()) s.proto = "tcp";
                s.txt = nz(Json.s(sm.get("txt")));
                s.key = nz(Json.s(sm.get("key")));
                s.vipAuto = s.vip.isEmpty() || s.vip.equalsIgnoreCase("auto") || s.vip.equals("@");
                if (s.name.isEmpty() || s.port <= 0) continue;
                if (!s.vipAuto && s.vip.isEmpty()) continue;
                Service old = services.get(s.name);
                if (old != null) {
                    s.curIp = old.curIp;
                    s.curPort = old.curPort;
                    s.updatedAt = old.updatedAt;
                }
                svcs.add(s);
            }
        }

        writeConfigFile(svcs);
        LinkedHashMap<String, Service> next = new LinkedHashMap<>();
        for (Service s : svcs) next.put(s.name, s);
        synchronized (services) {
            services.clear();
            services.putAll(next);
        }
        try {
            cfgStamp = Files.getLastModifiedTime(base.resolve("services.conf")).toMillis();
        } catch (Throwable ignore) {
        }
        if (Web.port != webPort) {
            Web.restart(webPort, modDir + "/webroot");
        }
        kick = true;
        log("Web: 保存配置，共 " + svcs.size() + " 个服务, dns=" + dnsServer + ":" + dnsPort + ", interval=" + interval + "s");
    }

    static void writeConfigFile(List<Service> svcs) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# ============ stunroute 配置（Web 界面生成，可直接编辑） ============\n");
        b.append("#dns_server=").append(dnsPort == 53 ? dnsServer : dnsServer + ":" + dnsPort).append('\n');
        b.append("#interval=").append(interval).append('\n');
        b.append("#dns_alias=").append(dnsAlias ? 1 : 0).append('\n');
        b.append("#netproxyctl=").append(npctl).append('\n');
        b.append("#web_port=").append(webPort).append('\n');
        b.append("#lan_dns_suffix=").append(lanSuffix).append('\n');
        b.append("#lan_dns_server=").append(lanServer).append('\n');
        b.append("#lan_dns_exception=").append(lanException).append('\n');
        b.append("#\n# 格式: 名称|域名|回环地址|端口|协议|TXT记录名|键\n");
        for (Service s : svcs) {
            b.append(nz(s.name)).append('|')
                    .append(nz(s.domain)).append('|')
                    .append(nz(s.vip)).append('|')
                    .append(s.port).append('|')
                    .append(nz(s.proto)).append('|')
                    .append(nz(s.txt)).append('|')
                    .append(nz(s.key)).append('\n');
        }
        Path f = base.resolve("services.conf");
        Files.write(f, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static List<String> tailLog(int n) {
        List<String> out = new ArrayList<>();
        try {
            if (!Files.exists(logFile)) return out;
            List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int start = Math.max(0, all.size() - n);
            out.addAll(all.subList(start, all.size()));
        } catch (Throwable ignore) {
        }
        return out;
    }

    // ---------------- 状态与日志 ----------------

    static void saveState() {
        StringBuilder sb = new StringBuilder();
        sb.append("# updated=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append('\n');
        synchronized (services) {
            for (Service s : services.values()) {
                sb.append(s.name).append(".target=").append(s.curIp == null ? "" : s.curIp + ":" + s.curPort).append('\n');
                sb.append(s.name).append(".domain=").append(s.domain == null ? "" : s.domain).append('\n');
                sb.append(s.name).append(".vip=").append(s.vip == null ? "" : s.vip + ":" + s.port).append('\n');
            }
        }
        try {
            Files.write(stateFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
        }
    }

    static void log(String msg) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + msg;
        synchronized (LOG_LOCK) {
            try {
                if (Files.exists(logFile) && Files.size(logFile) > 512 * 1024) {
                    List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                    int keep = Math.min(all.size(), 2000);
                    Files.write(logFile, all.subList(all.size() - keep, all.size()), StandardCharsets.UTF_8);
                }
                try (BufferedWriter w = Files.newBufferedWriter(logFile,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                    w.newLine();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    static int parseIntSafe(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return d; }
    }

    // ---------------- 服务模型 ----------------

    static final class Service {
        String name;
        String domain;   // 可选：应用访问用的域名
        String vip;      // 拦截目标：固定 IP，或留空/auto 表示跟随 TXT 里的 IP
        boolean vipAuto; // vip 是否跟随端点 IP
        int port;        // 应用访问的端口（保持原端口，便于客户端无感）
        String proto = "tcp";
        String txt;      // TXT 记录名
        String key;      // TXT 整表中的键

        String curIp;
        int curPort;
        long updatedAt;

        /** name|domain|vip|port|proto|txt|key */
        static Service parse(String line) {
            String[] f = line.split("\\|", -1);
            if (f.length < 4) return null;
            Service s = new Service();
            s.name = f[0].trim();
            s.domain = f[1].trim();
            s.vip = f[2].trim();
            try { s.port = Integer.parseInt(f[3].trim()); } catch (Throwable t) { return null; }
            if (f.length > 4 && !f[4].trim().isEmpty()) s.proto = f[4].trim();
            if (f.length > 5) s.txt = f[5].trim();
            if (f.length > 6) s.key = f[6].trim();
            s.vipAuto = s.vip.isEmpty() || s.vip.equalsIgnoreCase("auto") || s.vip.equals("@");
            if (s.name.isEmpty()) return null;
            if (!s.vipAuto && s.vip.isEmpty()) return null;
            return s;
        }
    }

    // ---------------- DNS TXT 查询 ----------------

    static final class Dns {
        static List<String> queryTxt(String name, String server, int port, int timeoutMs) throws IOException {
            byte[] q = buildQuery(name);
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(timeoutMs);
                ds.send(new DatagramPacket(q, q.length, InetAddress.getByName(server), port));
                byte[] buf = new byte[4096];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                return parse(resp.getData(), resp.getLength());
            }
        }

        static byte[] buildQuery(String name) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int id = (int) (Math.random() * 65535);
            write16(b, id);
            write16(b, 0x0100); // RD
            write16(b, 1);
            write16(b, 0);
            write16(b, 0);
            write16(b, 0);
            for (String part : name.split("\\.")) {
                byte[] pb = part.getBytes(StandardCharsets.US_ASCII);
                b.write(pb.length);
                b.write(pb);
            }
            b.write(0);
            write16(b, 16); // TXT
            write16(b, 1);  // IN
            return b.toByteArray();
        }

        static void write16(ByteArrayOutputStream b, int v) {
            b.write((v >> 8) & 0xFF);
            b.write(v & 0xFF);
        }

        static int u16(byte[] d, int o) {
            return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
        }

        static long u32(byte[] d, int o) {
            return ((long) (d[o] & 0xFF) << 24) | ((long) (d[o + 1] & 0xFF) << 16)
                    | ((long) (d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
        }

        static int skipName(byte[] d, int off) {
            while (off < d.length) {
                int len = d[off] & 0xFF;
                if (len == 0) return off + 1;
                if ((len & 0xC0) == 0xC0) return off + 2;
                off += 1 + len;
            }
            return off;
        }

        static List<String> parse(byte[] d, int len) {
            List<String> out = new ArrayList<>();
            try {
                int qd = u16(d, 4);
                int an = u16(d, 6);
                int off = 12;
                for (int i = 0; i < qd; i++) {
                    off = skipName(d, off) + 4;
                }
                for (int i = 0; i < an && off < len; i++) {
                    off = skipName(d, off);
                    int type = u16(d, off);
                    int rdlen = u16(d, off + 8);
                    off += 10;
                    int end = off + rdlen;
                    if (type == 16) {
                        StringBuilder sb = new StringBuilder();
                        int p = off;
                        while (p < end) {
                            int sl = d[p++] & 0xFF;
                            int take = Math.min(sl, end - p);
                            if (take <= 0) break;
                            sb.append(new String(d, p, take, StandardCharsets.UTF_8));
                            p += take;
                        }
                        out.add(sb.toString());
                    }
                    off = end;
                }
            } catch (Throwable ignore) {
            }
            return out;
        }
    }

    // ---------------- iptables 规则 ----------------

    static final class Iptables {
        static final String DNAT_CHAIN = "ETA_STUN";
        static final String POST_CHAIN = "ETA_STUN_POST";
        static String lastSig = "";

        static void sync(Map<String, Service> svcs) {
            List<Service> ready = new ArrayList<>();
            synchronized (svcs) {
                for (Service s : svcs.values()) {
                    if (s.curIp != null && s.curPort > 0) ready.add(s);
                }
            }
            StringBuilder sig = new StringBuilder();
            for (Service s : ready) {
                sig.append(s.name).append('=').append(s.vip).append(':').append(s.port)
                        .append('>').append(s.curIp).append(':').append(s.curPort).append(';');
            }
            if (sig.toString().equals(lastSig)) {
                // 规则可能被系统或其他模块刷掉，做一次轻量校验
                if (healthy(ready.size())) return;
                log("检测到 iptables 规则缺失，重新下发");
            }
            try {
                ensureChains();
                sh("iptables -t nat -F " + DNAT_CHAIN);
                sh("iptables -t nat -F " + POST_CHAIN);
                Set<String> seenSnat = new HashSet<>();
                for (Service s : ready) {
                    String p = s.proto.isEmpty() ? "tcp" : s.proto;
                    sh("iptables -t nat -A " + DNAT_CHAIN + " -p " + p
                            + " -d " + s.vip + "/32 --dport " + s.port
                            + " -j DNAT --to-destination " + dest(s.curIp, s.curPort));
                    String snat = "-p " + p + " -d " + s.curIp + " --dport " + s.curPort;
                    if (seenSnat.add(snat)) {
                        sh("iptables -t nat -A " + POST_CHAIN + " " + snat + " -j MASQUERADE");
                    }
                }
                sh("sysctl -w net.ipv4.conf.all.route_localnet=1");
                lastSig = sig.toString();
                log("iptables 规则已更新 (" + ready.size() + " 条映射)");
            } catch (Throwable t) {
                log("iptables 更新失败: " + t);
            }
        }

        static String dest(String ip, int port) {
            return ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
        }

        static boolean healthy(int expected) {
            try {
                if (!shOk("iptables -t nat -C OUTPUT -j " + DNAT_CHAIN)) return false;
                if (!shOk("iptables -t nat -C POSTROUTING -j " + POST_CHAIN)) return false;
                String dump = sh("iptables -t nat -S " + DNAT_CHAIN);
                int n = 0;
                for (String line : dump.split("\n")) {
                    if (line.startsWith("-A " + DNAT_CHAIN + " ")) n++;
                }
                return n == expected;
            } catch (Throwable t) {
                return false;
            }
        }

        static void ensureChains() {
            sh("iptables -t nat -N " + DNAT_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -N " + POST_CHAIN + " 2>/dev/null");
            if (!shOk("iptables -t nat -C OUTPUT -j " + DNAT_CHAIN)) {
                sh("iptables -t nat -I OUTPUT 1 -j " + DNAT_CHAIN);
            }
            if (!shOk("iptables -t nat -C POSTROUTING -j " + POST_CHAIN)) {
                sh("iptables -t nat -A POSTROUTING -j " + POST_CHAIN);
            }
        }

        static void cleanup() {
            sh("iptables -t nat -D OUTPUT -j " + DNAT_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -D POSTROUTING -j " + POST_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -F " + DNAT_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -X " + DNAT_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -F " + POST_CHAIN + " 2>/dev/null");
            sh("iptables -t nat -X " + POST_CHAIN + " 2>/dev/null");
            lastSig = "";
        }

        static String sh(String cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out = readAll(p.getInputStream());
                p.waitFor();
                return out;
            } catch (Throwable t) {
                return "";
            }
        }

        static boolean shOk(String cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().close();
                return p.waitFor() == 0;
            } catch (Throwable t) {
                return false;
            }
        }

        static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // ---------------- NetProxy DNS 别名同步 ----------------

    static final class NetProxyDns {
        static String lastApplied = "";
        static long lastLanCheck = 0;
        // 参数来自全局配置 lanSuffix / lanServer / lanException

        /**
         * 守护 *.cdxmz.top → 192.168.9.254 这条内网 DNS 规则。
         * NetProxy 的 WebUI 重新保存 DNS 设置时会把分区重新生成、这条规则被冲掉，
         * 这里每分钟检查一次，缺失就增量补回去（不影响分区里的其他内容）。
         */
        static synchronized void ensureLan(String ctl) {
            long now = System.currentTimeMillis();
            if (now - lastLanCheck < 60_000L) return;
            lastLanCheck = now;
            if (!new File(ctl).canExecute()) return;

            String raw = run(ctl + " config read singbox/dns");
            if (raw == null || !raw.contains("\"content\"")) return;

            String content = extractContent(raw);
            String revision = extractField(raw, "revision");
            if (content == null || revision == null) return;
            if (lanPresent(content)) return;

            String patched = insertLanRules(content);
            if (patched == null) {
                log("dns-lan 守护：分区结构未匹配，跳过");
                return;
            }
            try {
                Path tmp = base.resolve("runtime").resolve("dns.guard.json");
                Files.createDirectories(tmp.getParent());
                Files.write(tmp, patched.getBytes(StandardCharsets.UTF_8));
                String out = run(ctl + " config apply singbox/dns " + tmp + " --revision " + revision);
                if (out != null && out.contains("\"ok\":true")) {
                    log("已恢复内网 DNS 规则: *." + lanSuffix + " → " + lanServer);
                } else {
                    log("恢复 dns-lan 失败: " + out);
                }
            } catch (Throwable t) {
                log("恢复 dns-lan 异常: " + t);
            }
        }

        /** server 定义、后缀规则、例外规则三者齐全才算就位。 */
        @SuppressWarnings("unchecked")
        static boolean lanPresent(String content) {
            try {
                Object parsed = Json.parse(content);
                if (!(parsed instanceof Map)) return false;
                Map<String, Object> root = (Map<String, Object>) parsed;
                Map<String, Object> dns = (Map<String, Object>) root.get("dns");
                if (dns == null) return false;
                boolean hasServer = false;
                List<Object> servers = (List<Object>) dns.get("servers");
                if (servers != null) {
                    for (Object o : servers) {
                        if (o instanceof Map && "dns-lan".equals(((Map<?, ?>) o).get("tag"))) {
                            // 地址也要对得上，参数改了要能重建
                            hasServer = lanServer.equals(((Map<?, ?>) o).get("server"));
                            break;
                        }
                    }
                }
                boolean hasRule = false, hasException = false;
                List<Object> rules = (List<Object>) dns.get("rules");
                if (rules != null) {
                    for (Object o : rules) {
                        String t = Json.stringify(o);
                        if (t.contains("dns-lan")) hasRule = true;
                        if (t.contains(lanException)) hasException = true;
                    }
                }
                return hasServer && hasRule && hasException;
            } catch (Throwable t) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        static String insertLanRules(String content) {
            // vpn6.cdxmz.top 是公网 VPN 入口，VPN 没连时内网 DNS 不可达，
            // 必须在后缀规则之前放一条例外，否则"连 VPN 得先解析 VPN 地址"死循环。
            try {
                Object parsed = Json.parse(content);
                if (!(parsed instanceof Map)) return null;
                Map<String, Object> root = (Map<String, Object>) parsed;
                Map<String, Object> dns = (Map<String, Object>) root.get("dns");
                if (dns == null) return null;

                // 1) 保证 dns-lan server 存在（可能被 WebUI 重存时冲掉）
                List<Object> servers = (List<Object>) dns.get("servers");
                if (servers == null) {
                    servers = new ArrayList<>();
                    dns.put("servers", servers);
                }
                final String tag = "dns-lan";
                servers.removeIf(o -> o instanceof Map && tag.equals(((Map<?, ?>) o).get("tag")));
                Map<String, Object> srv = new LinkedHashMap<>();
                srv.put("tag", tag);
                srv.put("type", "udp");
                srv.put("server", lanServer);
                servers.add(srv);

                // 2) 规则
                List<Object> rules = (List<Object>) dns.get("rules");
                if (rules == null) {
                    rules = new ArrayList<>();
                    dns.put("rules", rules);
                }
                rules.removeIf(r -> {
                    String t = Json.stringify(r);
                    return t.contains("cdxmz.top");
                });
                Map<String, Object> lan = new LinkedHashMap<>();
                lan.put("domain_suffix", Collections.singletonList(lanSuffix));
                lan.put("action", "route");
                lan.put("server", "dns-lan");
                rules.add(0, lan);

                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("domain", Collections.singletonList(lanException));
                ex.put("action", "route");
                ex.put("server", "dns-direct");
                rules.add(0, ex);

                return Json.stringify(root);
            } catch (Throwable t) {
                return null;
            }
        }

        static String insertLanRulesLegacy(String content) {
            String serverBlock = "      {\n"
                    + "        \"tag\": \"dns-lan\",\n"
                    + "        \"type\": \"udp\",\n"
                    + "        \"server\": \"" + lanServer + "\"\n"
                    + "      },\n";
            int s = content.indexOf("\"servers\": [");
            if (s < 0) return null;
            int lb = content.indexOf('[', s);
            if (lb < 0) return null;
            String withServer = content.substring(0, lb + 1) + "\n" + serverBlock + content.substring(lb + 1);

            String ruleLine = "      {\"domain_suffix\": [\"" + lanSuffix + "\"], \"action\": \"route\", \"server\": \"dns-lan\"},\n";
            int r = withServer.indexOf("\"rules\": [");
            if (r < 0) return null;
            int rb = withServer.indexOf('[', r);
            if (rb < 0) return null;
            return withServer.substring(0, rb + 1) + "\n" + ruleLine + withServer.substring(rb + 1);
        }

        static void ensure(String ctl, Map<String, Service> svcs) {
            List<Service> aliased = new ArrayList<>();
            synchronized (svcs) {
                for (Service s : svcs.values()) {
                    if (s.domain != null && !s.domain.isEmpty() && s.vip != null) aliased.add(s);
                }
            }
            if (aliased.isEmpty()) return;
            if (!new File(ctl).canExecute()) {
                log("netproxyctl 不可执行: " + ctl);
                return;
            }
            StringBuilder sig = new StringBuilder();
            for (Service s : aliased) sig.append(s.domain).append('>').append(s.vip).append(';');
            if (sig.toString().equals(lastApplied)) return;

            String raw = run(ctl + " config read singbox/dns");
            if (raw == null || !raw.contains("\"content\"")) {
                log("读取 dns 分区失败");
                return;
            }
            String content = extractContent(raw);
            String revision = extractField(raw, "revision");
            if (content == null || revision == null) {
                log("解析 dns 分区失败");
                return;
            }
            String patched = content;
            int added = 0;
            for (Service s : aliased) {
                if (patched.contains("\"" + s.vip + "\"")) continue;
                String rule = "{\"domain\":[\"" + s.domain + "\"],\"action\":\"predefined\","
                        + "\"answer\":[\"" + s.domain + ". IN A " + s.vip + "\"]},";
                int idx = patched.indexOf("\"rules\":");
                if (idx < 0) {
                    log("dns 分区没有 rules，跳过");
                    break;
                }
                int lb = patched.indexOf('[', idx);
                if (lb < 0) {
                    log("dns 分区 rules 格式异常");
                    break;
                }
                patched = patched.substring(0, lb + 1) + "\n" + rule + patched.substring(lb + 1);
                added++;
            }
            if (added == 0) {
                lastApplied = sig.toString();
                return;
            }
            try {
                Path tmp = base.resolve("runtime").resolve("dns.patched.json");
                Files.createDirectories(tmp.getParent());
                Files.write(tmp, patched.getBytes(StandardCharsets.UTF_8));
                String out = run(ctl + " config apply singbox/dns " + tmp + " --revision " + revision);
                if (out == null || !out.contains("\"ok\":true")) {
                    log("应用 dns 分区失败: " + out);
                    return;
                }
                lastApplied = sig.toString();
                log("已注入 DNS 别名: " + added + " 条 (revision=" + revision.substring(0, 8) + ")");
            } catch (Throwable t) {
                log("写入 dns 分区异常: " + t);
            }
        }

        static String extractContent(String raw) {
            int k = raw.indexOf("\"content\":\"");
            if (k < 0) return null;
            int start = k + 11;
            int end = raw.indexOf("\",\"revision\"", start);
            if (end < 0) return null;
            String esc = raw.substring(start, end);
            StringBuilder sb = new StringBuilder(esc.length());
            for (int i = 0; i < esc.length(); i++) {
                char c = esc.charAt(i);
                if (c == '\\' && i + 1 < esc.length()) {
                    char n = esc.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            if (i + 4 < esc.length()) {
                                sb.append((char) Integer.parseInt(esc.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        static String extractField(String raw, String name) {
            String needle = "\"" + name + "\":\"";
            int k = raw.indexOf(needle);
            if (k < 0) return null;
            int start = k + needle.length();
            int end = raw.indexOf('"', start);
            if (end < 0) return null;
            return raw.substring(start, end);
        }

        static String run(String cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                pb.redirectErrorStream(false);
                Process p = pb.start();
                String out = Iptables.readAll(p.getInputStream());
                p.getErrorStream().close();
                p.waitFor();
                return out.trim();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
