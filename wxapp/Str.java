package com.khria.wxassist;

import java.util.List;

/** String.join 是 API 26，minSdk 24 手写替代 */
public final class Str {
    private Str() {}

    public static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(l.get(i));
        }
        return b.toString();
    }
}
