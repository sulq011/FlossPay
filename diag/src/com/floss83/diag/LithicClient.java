package com.floss83.diag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * LithicClient (build-16) — عميل HTTP بسيط لجسر Lithic (lithic_proxy.py):
 * GET/POST بإرجاع نص الرد كما هو (JSON يُحلَّل عند المستدعي).
 * المفتاح لا يمر عبر التطبيق إطلاقًا — يبقى في بيئة الخادم فقط.
 */
public final class LithicClient {

    private LithicClient() {}

    /** GET — يرجع نص الرد، أو يرمي استثناء برسالة واضحة عند الفشل. */
    public static String get(String host, String path) throws Exception {
        return call("GET", host, path, null);
    }

    /** POST بجسم JSON — يرجع نص الرد. */
    public static String post(String host, String path, String jsonBody) throws Exception {
        return call("POST", host, path, jsonBody);
    }

    private static String call(String method, String host, String path, String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(25000); // محاكاة التفويض قد تستغرق ثوانٍ (ينتظر الجسر النتيجة)
            if (jsonBody != null) {
                conn.setDoOutput(true);
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = conn.getResponseCode();
            String bodyText = readAll(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code >= 200 && code < 300) return bodyText;
            throw new Exception("HTTP " + code + ": " + shorten(bodyText));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
