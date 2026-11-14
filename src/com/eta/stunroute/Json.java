package com.eta.stunroute;

import java.util.*;

/** 极简 JSON 解析/序列化，只覆盖 Web 接口用到的类型。 */
final class Json {

    static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.value();
        return v;
    }

    static Object get(Object o, String key) {
        if (o instanceof Map) return ((Map<?, ?>) o).get(key);
        return null;
    }

    static String s(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static int i(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt(((String) o).trim()); } catch (Throwable t) { return def; }
        }
        return def;
    }

    static boolean b(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return "1".equals(o) || "true".equalsIgnoreCase((String) o);
        return def;
    }

    static String stringify(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append('"').append(esc((String) o)).append('"');
        } else if (o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) b.append((long) d);
            else b.append(d);
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Collection) {
            b.append('[');
            boolean first = true;
            for (Object v : (Collection<?>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, v);
            }
            b.append(']');
        } else {
            b.append('"').append(esc(o.toString())).append('"');
        }
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s == null ? "" : s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = str();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        String str() {
            if (i >= s.length() || s.charAt(i) != '"') return null;
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default: b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        Object num() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            try { return Double.parseDouble(s.substring(start, i)); }
            catch (Throwable t) { return null; }
        }
    }
}
