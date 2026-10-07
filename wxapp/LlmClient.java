package com.khria.wxassist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 生成：直连 OpenAI 兼容 /chat/completions。
 * 重试链按锁定方案：本地宽松解析 → finish_reason==length 调大 max_tokens →
 * 带一句病因修复重发一次 → 备用 provider → 放弃。
 */
public class LlmClient {
    public static class Cfg {
        String baseUrl;
        String apiKey;
        String model;
        String altBaseUrl;
        String altApiKey;
        String altModel;
    }

    public static class Cand {
        public String text;
        public double match;
        public String tag;
    }

    public static class Result {
        public boolean ok;
        public String err;
        public String tension;
        public String intent;
        public String need;
        public String direction;
        public final List<Cand> cands = new ArrayList<>();
    }

    /** 链路测试：列模型 + 一个最小生成往返 */
    public static Result ping(Cfg cfg) {
        JSONObject o = new JSONObject();
        try { o.put("model", cfg.model); } catch (Exception ignore) {}
        HttpResp r = getModels(cfg.baseUrl, cfg.apiKey);
        Result out = new Result();
        if (!r.ok) { out.ok = false; out.err = r.err; return out; }
        try {
            JSONObject ro = new JSONObject(r.body);
            JSONArray da = ro.optJSONArray("data");
            if (da != null) for (int i = 0; i < da.length() && i < 3; i++) {
                Cand cd = new Cand();
                cd.text = String.valueOf(da.optJSONObject(i).optString("id"));
                cd.match = 100 - i;
                out.cands.add(cd);
            }
        } catch (Exception ignore) {}
        // 再发一个 1 token 的最小补全确认生成真通
        try {
            JSONObject req = new JSONObject();
            req.put("model", cfg.model);
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", "ping");
            req.put("messages", new JSONArray().put(msg));
            req.put("max_tokens", 1);
            HttpResp r2 = post(cfg.baseUrl, cfg.apiKey, req);
            if (!r2.ok) { out.ok = false; out.err = r2.err; out.cands.clear(); return out; }
            out.ok = true;
        } catch (Exception e) {
            out.ok = false;
            out.err = String.valueOf(e);
        }
        return out;
    }

    private static HttpResp getModels(String baseUrl, String key) {
        HttpResp out = new HttpResp();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(baseUrl.replaceAll("/+$", "") + "/models");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            out.body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) { out.err = "HTTP " + code + "：" + trim(out.body, 120); return out; }
            out.ok = true;
        } catch (Exception e) {
            out.err = String.valueOf(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static final String PROMPT_TMPL =
            "你是聊天回复建议器。根据对话历史为最后一条对方消息生成回复候选。\n"
            + "对方：{SENDER}\n最近对话：\n{HIST}\n"
            + "只输出 JSON，结构：\n"
            + "{\"tension\":\"紧张度高/中/低+一句话\",\"intent\":\"可能意图\",\"need\":\"可能需要\","
            + "\"direction\":\"建议方向\",\"candidates\":[{\"text\":\"...\",\"match\":86,\"tag\":\"标签\"}]}\n"
            + "候选恰好 3 条，match 是 0-100 整数按从高到低排，text 是可直接发送的口语短句（不超过 40 字）";

    /** 主入口：阻塞调用，调用方放后台线程 */
    public static Result generate(Cfg cfg, String sender, List<String> hist) {
        String histTxt = Str.join(hist, "\n");
        return runChain(cfg, sender, histTxt, null, cfg.baseUrl, cfg.apiKey, cfg.model, 1, 2400);
    }

    private static Result runChain(Cfg cfg, String sender, String histTxt,
                                   String fixNote, String baseUrl, String key, String model,
                                   int attempt, int maxTokens) {
        JSONObject req = buildReq(sender, histTxt, fixNote, model, maxTokens);
        HttpResp r = post(baseUrl, key, req);
        if (!r.ok) {
            return fail(r.err, cfg, sender, histTxt, attempt, baseUrl, key, model, maxTokens);
        }
        ParseRes pr = parseBody(r.body);
        if (pr.result != null) return pr.result;
        // 解析失败分支链
        Result alt = altProvider(cfg, sender, histTxt);
        if (alt.ok) return alt;
        if ("length".equals(pr.finishReason) && attempt < 3) {
            return runChain(cfg, sender, histTxt, null, baseUrl, key, model, attempt + 1, maxTokens * 2);
        }
        if (attempt < 3) {
            return runChain(cfg, sender, histTxt,
                    "上次输出不是合法 JSON，原因：" + shortErr(pr.err) + "。重新输出完整 JSON。",
                    baseUrl, key, model, attempt + 1, maxTokens);
        }
        Result out = new Result();
        out.ok = false;
        out.err = pr.err == null ? "解析失败" : pr.err;
        return out;
    }

    private static Result altProvider(Cfg cfg, String sender, String histTxt) {
        if (cfg.altBaseUrl == null || cfg.altModel == null) return null2();
        JSONObject req = buildReq(sender, histTxt, null, cfg.altModel, 2400);
        HttpResp r = post(cfg.altBaseUrl, cfg.altApiKey, req);
        if (!r.ok) return null2();
        ParseRes pr = parseBody(r.body);
        return pr.result != null ? pr.result : null2();
    }

    private static Result null2() {
        Result out = new Result();
        out.ok = false;
        out.err = "alt失败";
        return out;
    }

    private static Result fail(String err, Cfg cfg, String sender, String histTxt,
                               int attempt, String baseUrl, String key, String model, int maxTokens) {
        Result alt = altProvider(cfg, sender, histTxt);
        if (alt.ok) return alt;
        if (attempt < 3) {
            return runChain(cfg, sender, histTxt, null, baseUrl, key, model, attempt + 1, maxTokens);
        }
        Result out = new Result();
        out.ok = false;
        out.err = err;
        return out;
    }

    private static String shortErr(String e) {
        return e == null ? "结构不符" : (e.length() > 60 ? e.substring(0, 60) : e);
    }

    private static JSONObject buildReq(String sender, String histTxt, String fixNote,
                                       String model, int maxTokens) {
        try {
            String p = PROMPT_TMPL
                    .replace("{SENDER}", sender == null ? "" : sender)
                    .replace("{HIST}", histTxt == null ? "" : histTxt);
            if (fixNote != null) p = p + "\n" + fixNote;
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", p);
            JSONObject o = new JSONObject();
            o.put("model", model);
            o.put("messages", new JSONArray().put(msg));
            o.put("max_tokens", maxTokens);
            o.put("temperature", fixNote == null ? 0.7 : 0.4);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static class HttpResp {
        boolean ok;
        String body;
        String err;
    }

    private static HttpResp post(String baseUrl, String key, JSONObject req) {
        HttpResp out = new HttpResp();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(baseUrl.replaceAll("/+$", "") + "/chat/completions");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            out.body = readAll(is);
            if (code >= 400) {
                out.err = "HTTP " + code + "：" + trim(out.body, 120);
                return out;
            }
            out.ok = true;
        } catch (Exception e) {
            out.err = String.valueOf(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    static class ParseRes {
        Result result;
        String finishReason;
        String err;
    }

    /** 先宽松解析（去围栏、补括号），再标准解析 */
    private static ParseRes parseBody(String body) {
        ParseRes out = new ParseRes();
        try {
            JSONObject root = new JSONObject(body);
            out.finishReason = root.optJSONArray("choices") != null
                    && root.optJSONArray("choices").length() > 0
                    ? root.optJSONArray("choices").optJSONObject(0).optString("finish_reason")
                    : null;
            String content = root.optJSONArray("choices") != null
                    && root.optJSONArray("choices").length() > 0
                    ? root.optJSONArray("choices").optJSONObject(0)
                        .optJSONObject("message").optString("content") : "";
            String jso = loose(content);
            if (jso == null) {
                out.err = "没有JSON体";
                return out;
            }
            JSONObject o = new JSONObject(jso); // 补括号后再试
            Result r = new Result();
            r.ok = true;
            r.tension = o.optString("tension", "");
            r.intent = o.optString("intent", "");
            r.need = o.optString("need", "");
            r.direction = o.optString("direction", "");
            JSONArray ca = o.optJSONArray("candidates");
            if (ca != null) {
                int m = Math.min(3, ca.length());
                for (int i = 0; i < m; i++) {
                    JSONObject c = ca.optJSONObject(i);
                    if (c == null) continue;
                    Cand cd = new Cand();
                    cd.text = c.optString("text", "");
                    if (cd.text.isEmpty()) continue;
                    cd.match = c.optDouble("match", 0);
                    cd.tag = c.optString("tag", "");
                    r.cands.add(cd);
                }
            }
            if (r.cands.isEmpty()) {
                out.err = "缺 candidates";
                return out;
            }
            r.cands.sort((a, b) -> Double.compare(b.match, a.match));
            out.result = r;
            return out;
        } catch (JSONException e) {
            // 第二步：松补括号重试一次
            String jso = loose(body);
            if (jso != null) {
                String fixed = closeBrackets(jso);
                if (fixed != null) {
                    try {
                        JSONObject o = new JSONObject(fixed);
                        // 递归一次简化版解析
                        ParseRes pr2 = parseInner(o);
                        if (pr2.result != null) return pr2;
                    } catch (JSONException ignore) {}
                }
            }
            out.err = "JSON坏：" + shortErr(String.valueOf(e));
            return out;
        }
    }

    private static ParseRes parseInner(JSONObject o) {
        ParseRes out = new ParseRes();
        try {
            Result r = new Result();
            r.ok = true;
            r.tension = o.optString("tension", "");
            r.intent = o.optString("intent", "");
            r.need = o.optString("need", "");
            r.direction = o.optString("direction", "");
            JSONArray ca = o.optJSONArray("candidates");
            if (ca != null) {
                int m = Math.min(3, ca.length());
                for (int i = 0; i < m; i++) {
                    JSONObject c = ca.optJSONObject(i);
                    if (c == null) continue;
                    Cand cd = new Cand();
                    cd.text = c.optString("text", "");
                    if (cd.text.isEmpty()) continue;
                    cd.match = c.optDouble("match", 0);
                    cd.tag = c.optString("tag", "");
                    r.cands.add(cd);
                }
            }
            if (r.cands.isEmpty()) out.err = "缺 candidates";
            else {
                r.cands.sort((a, b) -> Double.compare(b.match, a.match));
                out.result = r;
            }
        } catch (Exception e) {
            out.err = String.valueOf(e);
        }
        return out;
    }

    private static String loose(String content) {
        if (content == null) return null;
        String s = content.trim();
        if (s.startsWith("```")) {
            int a = s.indexOf('\n');
            int b = s.lastIndexOf("```");
            if (a > 0 && b > a) s = s.substring(a + 1, b);
        }
        int st = s.indexOf('{');
        int en = s.lastIndexOf('}');
        if (st < 0 || en <= st) return null;
        return s.substring(st, en + 1);
    }

    private static String closeBrackets(String s) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            if (ch == '"') inStr = !inStr;
            if (inStr) continue;
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
        }
        if (depth <= 0) return s;
        StringBuilder b = new StringBuilder(s);
        if (inStr) b.append('"');
        for (int i = 0; i < depth; i++) b.append('}');
        return b.toString();
    }
}
