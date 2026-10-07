package com.khria.wxassist;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private TextView status;
    private EditText etBase, etKey, etModel, etNames;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        etBase = findViewById(R.id.et_base);
        etKey = findViewById(R.id.et_key);
        etModel = findViewById(R.id.et_model);
        etNames = findViewById(R.id.et_names);

        ((Button) findViewById(R.id.btn_perm)).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
        ((Button) findViewById(R.id.btn_selftest)).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            // 通过一个假微信通知跑完整链路：new 通知走 onNotificationPosted
            runSelfTest();
        });
        ((Button) findViewById(R.id.btn_save)).setOnClickListener(v -> {
            getSharedPreferences("llm", MODE_PRIVATE).edit()
                    .putString("base", etBase.getText().toString().trim())
                    .putString("model", etModel.getText().toString().trim())
                    .apply();
            KeyVault.put(this, etKey.getText().toString().trim());
            refresh();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        ((Button) findViewById(R.id.btn_test)).setOnClickListener(v -> testLink());
        ((Button) findViewById(R.id.btn_names)).setOnClickListener(v -> {
            List<String> names = new java.util.ArrayList<>();
            for (String n : etNames.getText().toString().split("\n")) {
                n = n.trim();
                if (!n.isEmpty()) names.add(n);
            }
            TargetStore.save(this, names);
            Toast.makeText(this, "名单已存 " + names.size() + " 人", Toast.LENGTH_SHORT).show();
        });
        ((Button) findViewById(R.id.btn_history)).setOnClickListener(v -> {
            startActivity(new Intent(this, HistoryActivity.class));
        });
    }

    private void runSelfTest() {
        if (!listenerOn(this)) {
            Toast.makeText(this, "先开通知使用权再自测", Toast.LENGTH_SHORT).show();
            return;
        }
        // 自己发一条 MessagingStyle 假通知；accept 放行自己包名，
        // NLS 会照常走采集日志 + 生成链。channel 在本页建，权限放心
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("selftest", "自测",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);
        Person p = new Person.Builder().setName("自测联系人").build();
        long now = System.currentTimeMillis();
        Notification n = new Notification.Builder(this, "selftest")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(new Notification.MessagingStyle(
                        new Person.Builder().setName("我").build())
                        .addMessage(new Notification.MessagingStyle.Message("想找你帮个忙", now - 60000, p))
                        .addMessage(new Notification.MessagingStyle.Message("在的，你说", now, p)))
                .build();
        nm.notify(1001, n);
        nm.cancel(1001);
        status.setText("自测已发，等链路跑完看建议通知和历史");
        status.postDelayed(this::refresh, 1500);
    }

    private void testLink() {
        String base = etBase.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        if (base.isEmpty() || model.isEmpty()) {
            Toast.makeText(this, "先填 Base URL 和模型", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("链路测试中…");
        new Thread(() -> {
            LlmClient.Cfg cfg = new LlmClient.Cfg();
            cfg.baseUrl = base;
            cfg.apiKey = key;
            cfg.model = model;
            LlmClient.Result r = LlmClient.ping(cfg);
            final String msg = r.ok ? "链路通：有 " + r.cands.size() + " 个模型，生成 OK"
                    : "链路不通：" + r.err;
            runOnUiThread(() -> {
                status.setText(msg);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        }, "wxassist-test").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = listenerOn(this);
        List<String> names = TargetStore.load(this);
        status.setText((on ? "✓ 通知使用权已开" : "✗ 未开通知使用权，点下方按钮去开")
                + " · 名单 " + names.size() + " 人"
                + (getSharedPreferences("llm", MODE_PRIVATE).getString("base", "").isEmpty()
                    ? " · 未配接口" : " · 接口已配"));
        etNames.setText(Str.join(names, "\n"));
        android.content.SharedPreferences sp = getSharedPreferences("llm", MODE_PRIVATE);
        etBase.setText(sp.getString("base", ""));
        etModel.setText(sp.getString("model", ""));
        // key 不回填明文，占位提示有/无
        etKey.setHint(KeyVault.get(this).isEmpty() ? "sk-..." : "已保存（输入即替换）");
    }

    static boolean listenerOn(Context c) {
        String me = c.getPackageName() + "/" + AssistListenerService.class.getName();
        String raw = Settings.Secure.getString(c.getContentResolver(),
                "enabled_notification_listeners");
        if (raw == null) return false;
        for (String s : raw.split(":")) if (s.trim().equals(me)) return true;
        return false;
    }
}
