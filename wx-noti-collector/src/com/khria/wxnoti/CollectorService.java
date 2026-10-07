package com.khria.wxnoti;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0 采集器：只记不发。所有 extras 全量落 jsonl，供离线校准解析规则。
 */
public class CollectorService extends NotificationListenerService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String FILE = "noti_log.jsonl";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() { logEvent("connected", null); }

    @Override
    public void onListenerDisconnected() {
        logEvent("disconnected_rebinding", null);
        try {
            requestRebind(new android.content.ComponentName(this, CollectorService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) { logEvent("posted", sbn); }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { logEvent("removed", sbn); }

    @Override
    public void onDestroy() { io.shutdown(); super.onDestroy(); }

    private void logEvent(String ev, StatusBarNotification sbn) {
        final String line = buildLine(ev, sbn);
        io.execute(() -> appendLine(line));
    }

    // ---------- jsonl 组装 ----------

    /** 追加一个 "k":v 字段；[ 或 { 后面不加逗号 */
    private static void f(StringBuilder b, String k, String v) {
        char last = b.length() == 0 ? 0 : b.charAt(b.length() - 1);
        if (b.length() > 0 && last != '[' && last != '{') b.append(',');
        b.append(quote(k)).append(':').append(v == null ? "null" : v);
    }

    private static String num(long v) { return String.valueOf(v); }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    private static String stringify(Object v) {
        if (v == null) return null;
        if (v instanceof CharSequence) return v.toString();
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Object[]) return TextUtils.join(" | ", (Object[]) v);
        if (v instanceof Bundle) return "bundle{" + TextUtils.join(",", ((Bundle) v).keySet()) + "}";
        return String.valueOf(v);
    }

    private static String msgJson(Object o) {
        if (!(o instanceof Notification.MessagingStyle.Message)) return quote(stringify(o));
        Notification.MessagingStyle.Message m = (Notification.MessagingStyle.Message) o;
        StringBuilder b = new StringBuilder();
        b.append('{');
        if (Build.VERSION.SDK_INT >= 28) {
            Person p = m.getSenderPerson();
            f(b, "sender", quote(p == null ? null : String.valueOf(p.getName())));
        } else {
            f(b, "sender", quote(String.valueOf(m.getSender())));
        }
        f(b, "ts", num(m.getTimestamp()));
        f(b, "text", quote(m.getText() == null ? null : m.getText().toString()));
        Bundle extra = m.getExtras();
        if (extra != null && extra.getCharSequence("text") != null) {
            f(b, "rich", quote(String.valueOf(extra.getCharSequence("text"))));
        }
        b.append('}');
        return b.toString();
    }

    private static String buildLine(String ev, StatusBarNotification sbn) {
        StringBuilder b = new StringBuilder(768);
        b.append('{');
        f(b, "ts", num(System.currentTimeMillis()));
        f(b, "clock", quote(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())));
        f(b, "event", quote(ev));
        if (sbn != null) {
            f(b, "pkg", quote(sbn.getPackageName()));
            f(b, "key", quote(sbn.getKey()));
            f(b, "postTime", num(sbn.getPostTime()));
            f(b, "clearable", String.valueOf(sbn.isClearable()));
            Notification n = sbn.getNotification();
            f(b, "channel", quote(String.valueOf(n.getChannelId())));
            f(b, "category", quote(String.valueOf(n.category)));
            f(b, "priority", num(n.priority));
            Bundle ex = n.extras;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    if (("android.messages".equals(k) || "android.messages.historical".equals(k))
                            && v instanceof Parcelable[]) {
                        Parcelable[] arr = (Parcelable[]) v;
                        StringBuilder ab = new StringBuilder();
                        ab.append('[');
                        for (Parcelable p : arr) {
                            if (ab.length() > 1) ab.append(',');
                            ab.append(msgJson(p));
                        }
                        ab.append(']');
                        f(b, "android.messages".equals(k) ? "messages" : "historical_messages",
                                ab.toString());
                    } else {
                        f(b, "e:" + k, quote(stringify(v)));
                    }
                }
            }
            Notification.Action[] acts = n.actions;
            if (acts != null && acts.length > 0) {
                StringBuilder ab = new StringBuilder();
                ab.append('[');
                for (Notification.Action a : acts) {
                    if (ab.length() > 1) ab.append(',');
                    ab.append('{');
                    f(ab, "title", quote(String.valueOf(a.title)));
                    f(ab, "remoteInputs", num(a.getRemoteInputs() == null ? 0 : a.getRemoteInputs().length));
                    ab.append('}');
                }
                ab.append(']');
                f(b, "actions", ab.toString());
            }
        }
        b.append("}\n");
        return b.toString();
    }

    private void appendLine(String line) {
        try {
            File dir = getFilesDir();
            if (dir == null) return;
            File file = new File(dir, FILE);
            if (file.length() > MAX_BYTES) {
                File old = new File(dir, FILE + ".1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            FileOutputStream fo = new FileOutputStream(file, true);
            OutputStreamWriter w = new OutputStreamWriter(fo, StandardCharsets.UTF_8);
            w.write(line);
            w.close();
        } catch (Exception ignored) {}
    }
}
