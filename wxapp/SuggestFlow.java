package com.khria.wxassist;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 建议通知 + 历史落盘。生成在后台线程跑完再弹 heads-up */
public class SuggestFlow {
    private static final String CHANNEL_SUGGEST = "assist_suggest";
    private static final long MAX_HIST = 512 * 1024;

    /** 入口：AssistListenerService 命中名单后调用（binder 线程），内部切后台 */
    public static void onIncoming(final Context c, final AssistListenerService.ParsedMsg pm) {
        final LlmClient.Cfg cfg = loadCfg(c);
        if (cfg.baseUrl == null || cfg.model == null) return;
        if (dedupe(c, pm)) return;
        new Thread(() -> {
            LlmClient.Result r = LlmClient.generate(cfg, pm.sender, loadRecent(c, pm));
            if (r.ok && !r.cands.isEmpty()) {
                saveHistory(c, pm, r);
                notifySuggest(c, pm, r);
            }
        }, "wxassist-gen").start();
    }

    private static LlmClient.Cfg loadCfg(Context c) {
        LlmClient.Cfg cfg = new LlmClient.Cfg();
        android.content.SharedPreferences sp = c.getSharedPreferences("llm", Context.MODE_PRIVATE);
        cfg.baseUrl = sp.getString("base", null);
        cfg.model = sp.getString("model", null);
        cfg.apiKey = KeyVault.get(c);
        cfg.altBaseUrl = sp.getString("alt_base", null);
        cfg.altModel = sp.getString("alt_model", null);
        cfg.altApiKey = sp.getString("alt_base", null) == null ? null : KeyVault.getAlt(c);
        return cfg;
    }

    /** 同指纹去重：微信同一会话更新通知会重复回调 */
    private static boolean dedupe(Context c, AssistListenerService.ParsedMsg pm) {
        String fp = pm.sender + "|" + pm.text;
        android.content.SharedPreferences sp = c.getSharedPreferences("dedupe", Context.MODE_PRIVATE);
        if (fp.equals(sp.getString("last", null))) return true;
        sp.edit().putString("last", fp).apply();
        return false;
    }

    /** 历史回填：从 history.jsonl 尾部取最近 8 条该联系人的对话行 */
    private static List<String> loadRecent(Context c, AssistListenerService.ParsedMsg pm) {
        List<String> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), "history.jsonl");
            if (!f.exists()) return out;
            long skip = Math.max(0, f.length() - MAX_HIST);
            FileInputStream fi = new FileInputStream(f);
            if (skip > 0) fi.skip(skip);
            InputStreamReader rd = new InputStreamReader(fi, StandardCharsets.UTF_8);
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
            rd.close();
            for (String line : sb.toString().split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    if (!pm.sender.equals(o.optString("sender"))) continue;
                    out.add(o.optString("dir") + "：" + o.optString("text", ""));
                    JSONArray ca = o.optJSONArray("cands");
                    if (ca != null && ca.length() > 0) {
                        out.add("我（建议）：" + ca.optJSONObject(0).optString("text", ""));
                    }
                } catch (Exception ignore) {}
                if (out.size() > 16) out.remove(0);
            }
        } catch (Exception ignore) {}
        return out;
    }

    private static void saveHistory(Context c, AssistListenerService.ParsedMsg pm, LlmClient.Result r) {
        try {
            File f = new File(c.getFilesDir(), "history.jsonl");
            if (f.length() > MAX_HIST * 2) { // 满一半截旧
                f.renameTo(new File(c.getFilesDir(), "history.1.jsonl"));
                f = new File(c.getFilesDir(), "history.jsonl");
            }
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("sender", pm.sender);
            o.put("text", pm.text);
            o.put("tension", r.tension);
            o.put("intent", r.intent);
            o.put("need", r.need);
            o.put("dir", r.direction);
            JSONArray ca = new JSONArray();
            for (LlmClient.Cand cd : r.cands) {
                JSONObject co = new JSONObject();
                co.put("text", cd.text);
                co.put("match", cd.match);
                co.put("tag", cd.tag);
                ca.put(co);
            }
            o.put("cands", ca);
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8);
            w.write(o + "\n");
            w.close();
        } catch (Exception ignore) {}
    }

    private static void notifySuggest(Context c, AssistListenerService.ParsedMsg pm,
                                      LlmClient.Result r) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        LlmClient.Cand best = r.cands.get(0);
        String pct = Math.round(best.match) + "%";
        android.content.SharedPreferences sp = c.getSharedPreferences("idc", Context.MODE_PRIVATE);
        int rc = (int) (System.currentTimeMillis() & 0x7fffffff);

        PendingIntent openPi = PendingIntent.getActivity(c, rc,
                new Intent(c, HistoryActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, CHANNEL_SUGGEST)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(pm.sender + " · " + pct + " " + best.tag)
                .setContentText(best.text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        pm.sender + "：" + pm.text + "\n→ " + best.text))
                .setAutoCancel(true)
                .setContentIntent(openPi);
        for (int i = 0; i < r.cands.size(); i++) {
            LlmClient.Cand cd = r.cands.get(i);
            b.addAction(new Notification.Action.Builder(null,
                    "复制" + (i + 1) + "：" + trim(cd.text, 12),
                    CopyActionReceiver.pi(c, cd.text, rc + i)).build());
        }
        nm.notify(2000, b.build());
    }

    private static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 历史页读全部 */
    public static List<String> readHistory(Context c) {
        List<String> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), "history.jsonl");
            FileInputStream fi = new FileInputStream(f);
            InputStreamReader rd = new InputStreamReader(fi, StandardCharsets.UTF_8);
            char[] buf = new char[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
            rd.close();
            for (String line : sb.toString().split("\n")) out.add(line);
        } catch (Exception ignore) {}
        return out;
    }
}
