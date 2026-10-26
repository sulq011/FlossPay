package com.floss83.pay;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * تخزين جلسات التشخيص + الاختبارات المعلّقة + الرفع للخادم (build-11).
 */
public class DiagStore {
    private static final String PREFS = "flosspay";
    private static final String KEY = "diag_sessions";
    private static final String HOST_KEY = "diag_host";
    private static final String DEFAULT_HOST = "sull.world:8096";
    private static final String PENDING_KEY = "pending_test";
    private static final int MAX_SESSIONS = 20;

    private static android.content.SharedPreferences prefs(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    public static String getHost(android.content.Context ctx) {
        return prefs(ctx).getString(HOST_KEY, DEFAULT_HOST);
    }

    public static void setHost(android.content.Context ctx, String host) {
        prefs(ctx).edit().putString(HOST_KEY, host).apply();
    }

    // ===== اختبار معلّق (من التطبيق الأساسي FlossDiag) =====

    public static void savePendingTest(android.content.Context ctx, String testId, long amountMinor) {
        try {
            JSONObject o = new JSONObject();
            o.put("testId", testId);
            o.put("amountMinor", amountMinor);
            o.put("ts", System.currentTimeMillis());
            prefs(ctx).edit().putString(PENDING_KEY, o.toString()).apply();
        } catch (Exception ignore) {}
    }

    public static JSONObject loadPendingTest(android.content.Context ctx) {
        try {
            String s = prefs(ctx).getString(PENDING_KEY, "");
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearPendingTest(android.content.Context ctx) {
        prefs(ctx).edit().remove(PENDING_KEY).apply();
    }

    // ===== الجلسات =====

    /**
     * تسجيل جلسة جديدة (تحليل + سجل APDU) وإبقاء آخر 20 جلسة.
     * يرجع JSON الجلسة (لتسليمها للتطبيق الأساسي) أو null عند الفشل.
     */
    public static JSONObject record(android.content.Context ctx, java.util.List<EmvDiag.Entry> apdus, EmvDiag.Result res) {
        try {
            JSONObject session = new JSONObject();
            session.put("ts", System.currentTimeMillis());
            session.put("verdict", res.verdict);
            session.put("stage", res.stage);
            session.put("stageNum", res.stageNum);
            session.put("apduCount", res.apduCount);
            session.put("totalMs", res.totalMs);
            session.put("errors", res.errors);
            session.put("amountMinor", res.amountMinor);
            session.put("amountMismatch", res.amountMismatch);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < apdus.size(); i++) {
                EmvDiag.Entry e = apdus.get(i);
                JSONObject o = new JSONObject();
                o.put("cmd", e.cmd);
                o.put("resp", e.resp);
                o.put("ms", e.ms);
                arr.put(o);
            }
            session.put("apdus", arr);

            String existing = prefs(ctx).getString(KEY, "[]");
            JSONArray all = new JSONArray(existing);
            all.put(session);
            while (all.length() > MAX_SESSIONS) all.remove(0);
            prefs(ctx).edit().putString(KEY, all.toString()).apply();
            return session;
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONArray loadAll(android.content.Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** رفع جلسة واحدة إلى الخادم — متزامن، استدعِه من Thread. */
    public static boolean upload(android.content.Context ctx, JSONObject session) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("http://" + getHost(ctx) + "/api/sessions");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            byte[] body = session.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
