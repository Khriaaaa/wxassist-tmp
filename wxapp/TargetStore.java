package com.khria.wxassist;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 监控名单：普通 SharedPreferences 就够，名字不是秘密 */
public class TargetStore {
    private static final String FILE = "targets";
    private static final String KEY = "names";

    public static List<String> load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String s = sp.getString(KEY, "");
        List<String> out = new ArrayList<>();
        for (String n : s.split("\n")) {
            n = n.trim();
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public static void save(Context c, List<String> names) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        sp.edit().putString(KEY, Str.join(names, "\n")).apply();
    }

    public static boolean hit(Context c, String sender) {
        if (sender == null) return false;
        for (String n : load(c)) if (sender.contains(n)) return true;
        return false;
    }
}
