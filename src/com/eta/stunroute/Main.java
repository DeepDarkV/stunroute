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
    static final LinkedHashMap<String, Service> services = new LinkedHashMap<>();

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
            if (ip.equals(s.curIp) && port == s.curPort) return;
            log("[" + s.name + "] 端点更新: " + s.curIp + ":" + s.curPort + " -> " + ip + ":" + port);
            s.curIp = ip;
            s.curPort = port;
            s.updatedAt = System.currentTimeMillis();
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

    // ---------------- Web 配置接口 ----------------

    static Map<String, Object> globalsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dns_server", dnsPort == 53 ? dnsServer : dnsServer + ":" + dnsPort);
        m.put("interval", interval);
        m.put("dns_alias", dnsAlias);
        m.put("web_port", webPort);
        m.put("netproxyctl", npctl);
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
                if (s.name.isEmpty() || s.vip.isEmpty() || s.port <= 0) continue;
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
        String vip;      // 回环地址 127.0.0.x
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
            if (s.name.isEmpty() || s.vip.isEmpty()) return null;
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
