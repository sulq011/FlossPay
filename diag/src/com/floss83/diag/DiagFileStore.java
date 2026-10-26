package com.floss83.diag;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ملف النتائج المحفوظ (results.jsonl) في منطقة التطبيق:
 * كل سطر = نتيجة اختبار واحدة (جلسة POS من المحفظة، أو اختبار بوابة/متجر مُدخل يدويًا).
 */
public class DiagFileStore {

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "results.jsonl");
    }

    public static synchronized void append(Context ctx, String jsonLine) {
        try {
            FileOutputStream fos = new FileOutputStream(file(ctx), true);
            fos.write((jsonLine.trim() + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignore) {}
    }

    public static List<String> loadAll(Context ctx) {
        List<String> out = new ArrayList<String>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file(ctx)), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
            r.close();
        } catch (Exception ignore) {}
        return out;
    }

    public static void clear(Context ctx) {
        try { file(ctx).delete(); } catch (Exception ignore) {}
    }

    /** رفع سطر نتيجة واحد إلى الخادم — متزامن. */
    public static boolean uploadOne(String host, String jsonLine) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + "/api/sessions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            byte[] body = jsonLine.getBytes("UTF-8");
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

    /** رفع كل النتائج — يرجع عدد المرفوع بنجاح. */
    public static int uploadAll(Context ctx, String host) {
        int ok = 0;
        for (String line : loadAll(ctx)) {
            if (uploadOne(host, line)) ok++;
        }
        return ok;
    }

    /** تحويل سطر إلى JSON بأمان. */
    public static JSONObject parse(String line) {
        try {
            return new JSONObject(line);
        } catch (Exception e) {
            return null;
        }
    }
}
