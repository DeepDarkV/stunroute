package com.eta.stunroute;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简 HTTP 管理界面。只监听 127.0.0.1。 */
final class Web {

    static ServerSocket server;
    static String webRoot;
    static volatile int port = -1;
    static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    static synchronized void restart(int p, String root) {
        webRoot = root;
        stop();
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 16);
            port = p;
            final ServerSocket ss = server;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Socket s = ss.accept();
                        POOL.submit(() -> handle(s));
                    } catch (Throwable e) {
                        if (ss.isClosed()) return;
                    }
                }
            }, "stunroute-web");
            t.setDaemon(true);
            t.start();
            Main.log("Web 管理界面: http://127.0.0.1:" + p + "/");
        } catch (Throwable e) {
            port = -1;
            Main.log("Web 启动失败: " + e);
        }
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Throwable ignore) { }
        server = null;
        port = -1;
    }

    // ---------------- 请求处理 ----------------

    static void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            String head = readHead(in, 16384);
            if (head == null) return;
            String[] lines = head.split("\n");
            if (lines.length == 0) return;
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String target = req[1];
            String path = target;
            String query = "";
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                query = target.substring(qi + 1);
            }
            int clen = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { clen = Integer.parseInt(lines[i].substring(c + 1).trim()); } catch (Throwable ignore) { }
                }
            }
            String body = "";
            if (clen > 0) {
                byte[] buf = new byte[Math.min(clen, 1 << 20)];
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n < 0) break;
                    got += n;
                }
                body = new String(buf, 0, got, StandardCharsets.UTF_8);
            } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                // 没给 Content-Length 时退化为读到连接关闭
                ByteArrayOutputStream bb = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    bb.write(tmp, 0, n);
                    if (bb.size() > (1 << 20)) break;
                }
                body = bb.toString("UTF-8");
            }
            route(s, method, path, query, body);
        } catch (Throwable ignore) {
        }
    }

    /** 读到空行为止（HTTP 头结束），返回以 \n 分隔的头文本。 */
    static String readHead(InputStream in, int max) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (head.size() < max) {
            int c = in.read();
            if (c < 0) break;
            if (c == '\n') {
                if (line.size() == 0) break; // 空行：头结束
                byte[] l = line.toByteArray();
                head.write(l, 0, l.length);
                head.write('\n');
                line.reset();
            } else if (c != '\r') {
                line.write(c);
            }
        }
        if (head.size() == 0) return null;
        return head.toString("ISO-8859-1");
    }

    static void route(Socket s, String method, String path, String query, String body) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            write(s, "204 No Content", "text/plain", new byte[0]);
            return;
        }
        switch (path) {
            case "/":
            case "/index.html":
                serveIndex(s);
                return;
            case "/api/state":
                sendJson(s, "200 OK", stateJson());
                return;
            case "/api/txt":
                sendJson(s, "200 OK", txtJson(param(query, "name"), param(query, "key")));
                return;
            case "/api/logs":
                sendJson(s, "200 OK", logsJson(Json.i(param(query, "lines"), 120)));
                return;
            case "/api/config":
                if ("POST".equalsIgnoreCase(method)) {
                    try {
                        Main.applyFromWeb(Json.parse(body));
                        sendJson(s, "200 OK", "{\"ok\":true}");
                    } catch (Throwable t) {
                        sendJson(s, "200 OK", "{\"ok\":false,\"error\":" + Json.stringify(String.valueOf(t)) + "}");
                    }
                    return;
                }
                sendJson(s, "405 Method Not Allowed", "{\"ok\":false}");
                return;
            case "/api/action":
                try {
                    Object o = Json.parse(body);
                    String action = Json.s(Json.get(o, "action"));
                    if ("refresh".equals(action)) {
                        Main.kick = true;
                    } else if ("clean".equals(action)) {
                        Main.Iptables.cleanup();
                        Main.log("Web: 已清理 iptables 规则");
                    } else if ("dnsresync".equals(action)) {
                        Main.NetProxyDns.lastApplied = "";
                    }
                    sendJson(s, "200 OK", "{\"ok\":true}");
                } catch (Throwable t) {
                    sendJson(s, "200 OK", "{\"ok\":false}");
                }
                return;
            default:
                sendJson(s, "404 Not Found", "{\"ok\":false,\"error\":\"not found\"}");
        }
    }

    static String param(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            if (kv.substring(0, e).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Throwable t) {
                    return kv.substring(e + 1);
                }
            }
        }
        return "";
    }

    // ---------------- 接口实现 ----------------

    static String stateJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("running", true);
        root.put("globals", Main.globalsMap());
        List<Object> list = new ArrayList<>();
        synchronized (Main.services) {
            for (Main.Service s : Main.services.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name);
                m.put("domain", s.domain);
                m.put("vip", s.vip);
                m.put("port", s.port);
                m.put("proto", s.proto);
                m.put("txt", s.txt);
                m.put("key", s.key);
                m.put("target", s.curIp == null ? "" : (s.curIp.contains(":") ? "[" + s.curIp + "]" : s.curIp) + ":" + s.curPort);
                m.put("updated", s.updatedAt);
                list.add(m);
            }
        }
        root.put("services", list);
        return Json.stringify(root);
    }

    static String txtJson(String name, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (name == null || name.isEmpty()) {
            m.put("raw", "");
            m.put("error", "记录名为空");
            return Json.stringify(m);
        }
        try {
            List<String> records = Main.Dns.queryTxt(name, Main.dnsServer, Main.dnsPort, 4000);
            if (records.isEmpty()) {
                m.put("raw", "");
            } else {
                String raw = records.get(0);
                m.put("raw", raw);
                String[] ep = Main.parseEndpoint(raw, key);
                if (ep != null) {
                    m.put("ip", ep[0]);
                    m.put("port", Integer.parseInt(ep[1]));
                }
            }
        } catch (Throwable t) {
            m.put("raw", "");
            m.put("error", String.valueOf(t));
        }
        return Json.stringify(m);
    }

    static String logsJson(int lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> out = Main.tailLog(Math.max(1, Math.min(lines, 1000)));
        m.put("lines", out);
        return Json.stringify(m);
    }

    static void serveIndex(Socket s) throws IOException {
        File f = new File(webRoot == null ? "" : webRoot, "index.html");
        byte[] data;
        if (f.isFile()) {
            data = Files.readAllBytes(f.toPath());
        } else {
            data = "<h3>index.html 缺失</h3>".getBytes(StandardCharsets.UTF_8);
        }
        write(s, "200 OK", "text/html; charset=utf-8", data);
    }

    static void sendJson(Socket s, String status, String json) throws IOException {
        write(s, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void write(Socket s, String status, String type, byte[] data) throws IOException {
        OutputStream out = s.getOutputStream();
        String h = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }
}
