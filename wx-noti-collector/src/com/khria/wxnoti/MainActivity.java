package com.khria.wxnoti;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** 调试页：授权入口、自测通知、日志分享/清空、尾部预览 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        preview = findViewById(R.id.preview);

        ((Button) findViewById(R.id.btn_grant)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        ((Button) findViewById(R.id.btn_selftest)).setOnClickListener(v -> postSelfTest());
        ((Button) findViewById(R.id.btn_share)).setOnClickListener(v -> shareLog());
        ((Button) findViewById(R.id.btn_clear)).setOnClickListener(v -> {
            new File(getFilesDir(), "noti_log.jsonl").delete();
            refresh();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() { super.onResume(); refresh(); }

    private boolean listenerOn() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void refresh() {
        boolean on = listenerOn();
        File f = new File(getFilesDir(), "noti_log.jsonl");
        long size = f.exists() ? f.length() : 0;
        status.setText((on ? "✓ 通知使用权已开" : "✗ 通知使用权未开")
                + "\n日志: " + countLines(f) + " 行 / " + (size / 1024) + " KB"
                + "\n监控包: com.tencent.mm");
        preview.setText(readTail(f));
    }

    private static long countLines(File f) {
        if (!f.exists()) return 0;
        try (LineNumberReader r = new LineNumberReader(new FileReader(f))) {
            r.skip(Long.MAX_VALUE);
            return r.getLineNumber();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readTail(File f) {
        if (!f.exists()) return "(空)";
        try {
            long len = f.length();
            long start = Math.max(0, len - 16384);
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, StandardCharsets.UTF_8);
            int i = s.indexOf('\n');
            if (i >= 0 && start > 0) s = s.substring(i + 1);
            return s;
        } catch (Exception e) {
            return "读取失败: " + e;
        }
    }

    private void postSelfTest() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("selftest", "自测",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "selftest");
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("微信采集器");
        if (Build.VERSION.SDK_INT >= 28) {
            Person me = new Person.Builder().setName("我").build();
            Person her = new Person.Builder().setName("林同学").build();
            Notification.MessagingStyle st = new Notification.MessagingStyle(me);
            st.addMessage("想找你帮个忙", System.currentTimeMillis(), her);
            st.addMessage("在的，你说", System.currentTimeMillis() - 60000, me);
            b.setStyle(st);
        } else {
            b.setContentText("想找你帮个忙");
        }
        nm.notify(1001, b.build());
        Toast.makeText(this, "已发自测通知，回本页看日志", Toast.LENGTH_SHORT).show();
    }

    private void shareLog() {
        File f = new File(getFilesDir(), "noti_log.jsonl");
        if (!f.exists() || f.length() == 0) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.khria.wxnoti.log/noti_log.jsonl"));
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(it, "分享日志"));
    }
}
