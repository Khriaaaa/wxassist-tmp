package com.khria.wxassist;

import org.json.JSONArray;
import org.json.JSONObject;

/** JSON 小工具 + 兼容层：java -source 8 无 String.join 时用 */
public final class Json {
    private Json() {}

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        b.append('"');
        return b.toString();
    }

    public static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(l.get(i));
        }
        return b.toString();
    }

    static final class List<T> extends java.util.ArrayList<T> {}
}
