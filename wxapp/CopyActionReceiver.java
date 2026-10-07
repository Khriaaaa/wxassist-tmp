package com.khria.wxassist;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** 复制按钮：BroadcastReceiver 直接写剪贴板，不弹 Activity */
public class CopyActionReceiver extends BroadcastReceiver {
    public static final String EXTRA_TEXT = "t";

    public static PendingIntent pi(Context c, String text, int rc) {
        Intent i = new Intent(c, CopyActionReceiver.class);
        i.putExtra(EXTRA_TEXT, text);
        return PendingIntent.getBroadcast(c, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String t = i.getStringExtra(EXTRA_TEXT);
        if (t != null) {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("reply", t));
            Toast.makeText(c, "已复制，去粘贴发送", Toast.LENGTH_SHORT).show();
        }
    }
}
