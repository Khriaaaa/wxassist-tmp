package com.khria.wxassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * 采集服务：只监听微信；解析 MessagingStyle EXTRA_MESSAGES（公开 API 路线），
 * 全量原始 extras 落 jsonl 供校准；命中名单才触发生成。
 */
public class AssistListenerService extends NotificationListenerService {
    static final String TAG = "wxassist";
    private static final String CHANNEL_SERVICE = "assist_service";
    private static final String CHANNEL_SUGGEST = "assist_suggest";
    private static final long MAX_LOG = 4L * 1024 * 1024;

    private ExecutorService io;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newSingleThreadExecutor();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_SERVICE, "后台采集",
                NotificationManager.IMPORTANCE_MIN));
        // 高优 channel + 锁屏可见，heads-up 还需用户开悬浮
        NotificationChannel c = new NotificationChannel(CHANNEL_SUGGEST, "回复建议",
                NotificationManager.IMPORTANCE_HIGH);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        // 常驻低优通知兜底，杀服务概率略降
        Notification n = new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentText("监听中，若消失请重新授权")
                .setOngoing(true).build();
        startForeground(1, n);
        return START_STICKY;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!accept(sbn)) return;
        try {
            final String line = buildLine("posted", sbn);
            io.execute(() -> appendLine(line));
            final ParsedMsg pm = parse(sbn);
            if (pm != null && TargetStore.hit(this, pm.sender)) {
                SuggestFlow.onIncoming(this, pm);
            }
        } catch (Throwable t) {
            final String err = Json.quote(String.valueOf(t));
            io.execute(() -> appendLine("{\"event\":\"build_error\",\"err\":" + err + "}"));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!accept(sbn)) return;
        // removed 只记 key + postTime，extras 不重复落
        String key = sbn.getKey() == null ? "" : Json.quote(sbn.getKey());
        io.execute(() -> appendLine("{\"event\":\"removed\",\"key\":" + key
                + ",\"pt\":" + sbn.getPostTime() + "}"));
    }

    @Override
    public void onListenerDisconnected() {
        // 系统断绑后 requestRebind 自愈
        requestRebind(new ComponentName(this, AssistListenerService.class));
    }

    private boolean accept(StatusBarNotification sbn) {
        if (sbn == null) return false;
        String pkg = sbn.getPackageName();
        return "com.tencent.mm".equals(pkg) || getPackageName().equals(pkg);
    }

    /** 解析结果：发件人 + 拼接文本；只取 incoming 消息 */
    static class ParsedMsg {
        String sender;
        String text;
        long ts;
        String raw;
    }

    private ParsedMsg parse(StatusBarNotification sbn) {
        Bundle ex = sbn.getNotification().extras;
        if (ex == null) return null;
        Parcelable[] arr = ex.getParcelableArray("android.messages");
        if (arr == null || arr.length == 0) return null;
        // 取最后一条 incoming
        for (int i = arr.length - 1; i >= 0; i--) {
            if (!(arr[i] instanceof Bundle)) continue;
            Bundle b = (Bundle) arr[i];
            CharSequence sender = null;
            Object po = b.getParcelable("sender_person");
            if (po instanceof Person) sender = ((Person) po).getName();
            if (sender == null) sender = b.getCharSequence("sender");
            CharSequence text = b.getCharSequence("text");
            if (text == null || sender == null) continue;
            ParsedMsg pm = new ParsedMsg();
            pm.sender = sender.toString();
            pm.text = text.toString();
            pm.ts = b.getLong("time", System.currentTimeMillis());
            pm.raw = ex.toString();
            return pm;
        }
        return null;
    }

    private String buildLine(String ev, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        JSONObject o = new JSONObject();
        try {
            o.put("event", ev);
            o.put("ts", fmt.format(new Date()));
            o.put("pkg", sbn.getPackageName());
            o.put("key", String.valueOf(sbn.getKey()));
            o.put("pt", sbn.getPostTime());
            o.put("channel", n.getChannelId() == null ? JSONObject.NULL : n.getChannelId());
            Parcelable[] msgs = ex == null ? null : ex.getParcelableArray("android.messages");
            if (msgs != null) {
                JSONArray ja = new JSONArray();
                for (Parcelable p : msgs) {
                    if (p instanceof Bundle) {
                        Bundle b = (Bundle) p;
                        JSONObject m = new JSONObject();
                        m.put("text", b.getCharSequence("text") == null
                                ? JSONObject.NULL : String.valueOf(b.getCharSequence("text")));
                        m.put("sender", b.getParcelable("sender_person") instanceof Person
                                ? String.valueOf(((Person) b.getParcelable("sender_person")).getName())
                                : String.valueOf(b.getCharSequence("sender")));
                        m.put("time", b.getLong("time", 0));
                        ja.put(m);
                    } else {
                        ja.put(String.valueOf(p));
                    }
                }
                o.put("messages", ja);
            }
            if (ex != null) {
                for (String k : ex.keySet()) {
                    if ("android.messages".equals(k) || "android.messages.historical".equals(k)) continue;
                    Object v = ex.get(k);
                    String vs = v instanceof Parcelable ? String.valueOf(v) : String.valueOf(v);
                    o.put("x_" + k, vs.length() > 2000 ? vs.substring(0, 2000) : vs);
                }
            }
        } catch (Exception e) {
            return "{\"event\":\"json_error\",\"err\":" + Json.quote(String.valueOf(e)) + "}";
        }
        return o.toString();
    }

    private void appendLine(String line) {
        try {
            File f = new File(getFilesDir(), "noti_log.jsonl");
            if (f.length() > MAX_LOG) {
                File old = new File(getFilesDir(), "noti_log.jsonl.1");
                old.delete();
                f.renameTo(old);
            }
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8);
            w.write(line + "\n");
            w.close();
        } catch (Exception e) {
            Log.e(TAG, "append", e);
        }
    }

    /** 调试页自测通知已移到 MainActivity（channel 由 Activity 建才不崩） */
}
