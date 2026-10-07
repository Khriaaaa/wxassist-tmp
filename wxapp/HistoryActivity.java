package com.khria.wxassist;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(13f);
        sv.addView(tv);
        setContentView(sv);

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        List<String> lines = SuggestFlow.readHistory(this);
        if (lines.isEmpty()) {
            sb.append("还没有记录\n\n命中名单的微信消息会在这里留痕：原文、紧张度/意图/需要、三条候选和匹配度");
        }
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(line.trim());
                sb.append(fmt.format(new Date(o.getLong("ts"))))
                        .append(' ').append(o.optString("sender", ""));
                String text = o.optString("text", "");
                if (!text.isEmpty()) sb.append("：").append(text);
                sb.append('\n');
                String tn = o.optString("tension", "");
                if (!tn.isEmpty()) sb.append("  紧张度 ").append(tn).append('\n');
                String it = o.optString("intent", "");
                if (!it.isEmpty()) sb.append("  意图 ").append(it).append('\n');
                String nd = o.optString("need", "");
                if (!nd.isEmpty()) sb.append("  需要 ").append(nd).append('\n');
                JSONArray ca = o.optJSONArray("cands");
                if (ca != null) {
                    for (int i = 0; i < ca.length(); i++) {
                        JSONObject cd = ca.optJSONObject(i);
                        sb.append("  ").append(Math.round(cd.optDouble("match", 0))).append('%');
                        String tag = cd.optString("tag", "");
                        if (!tag.isEmpty()) sb.append('/').append(tag);
                        sb.append(' ').append(cd.optString("text", "")).append('\n');
                    }
                }
                sb.append('\n');
            } catch (Exception e) {
                sb.append("(坏行)").append('\n');
            }
        }
        tv.setText(sb.length() == 0 ? "无" : sb.toString());
        tv.setOnLongClickListener(v -> {
            // 长按分享全部
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_TEXT, tv.getText());
            startActivity(android.content.Intent.createChooser(i, "分享历史"));
            return true;
        });
        Toast.makeText(this, "长按可分享全部历史", Toast.LENGTH_SHORT).show();
    }
}
