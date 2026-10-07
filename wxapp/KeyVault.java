package com.khria.wxassist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * API key 存储。没有 androidx security-crypto，用系统 JCA 自己做 AES-GCM：
 * 随机 key 存普通 prefs（设备本地），真密文存另一个 prefs。
 * 个人单设备侧载场景够用，不宣称对抗 root。
 */
public class KeyVault {
    private static final String MAIN = "vault_main";
    private static final String DATA = "vault_data";
    private static final String DATA_ALT = "vault_data_alt";
    private static final String K_KEY = "k";
    private static final String V_KEY = "v";

    private static byte[] getKey(Context c) {
        SharedPreferences sp = c.getSharedPreferences(MAIN, Context.MODE_PRIVATE);
        String s = sp.getString(K_KEY, null);
        if (s == null) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            sp.edit().putString(K_KEY, Base64.encodeToString(k, Base64.NO_WRAP)).apply();
            return k;
        }
        return Base64.decode(s, Base64.NO_WRAP);
    }

    public static String get(Context c) {
        return get(c, DATA);
    }

    public static String getAlt(Context c) {
        return get(c, DATA_ALT);
    }

    public static void putAlt(Context c, String plain) {
        put(c, DATA_ALT, plain);
    }

    private static String get(Context c, String file) {
        try {
            String s = c.getSharedPreferences(file, Context.MODE_PRIVATE).getString(V_KEY, null);
            if (s == null) return "";
            int i = s.indexOf(':');
            byte[] iv = Base64.decode(s.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(s.substring(i + 1), Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getKey(c), "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(ci.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static void put(Context c, String plain) {
        put(c, DATA, plain);
    }

    private static void put(Context c, String file, String plain) {
        try {
            byte[] k = getKey(c);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = ci.doFinal(plain.getBytes("UTF-8"));
            String out = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ct, Base64.NO_WRAP);
            c.getSharedPreferences(file, Context.MODE_PRIVATE)
                    .edit().putString(V_KEY, out).apply();
        } catch (Exception ignored) {
        }
    }
}
