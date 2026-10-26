package com.floss83.pay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * عميل ISO 8583:1987 متوافق مع سويتش Flossx83 (TcpServer v1.5):
 * - رسالة 0200 شراء مع F52 (CVV مبطن 16 خانة)
 * - تأطير 2 بايت binary big-endian (نفس --header 2b)
 * - تحليل الرد 0210 واستخراج F38/F39/F11
 */
public final class Iso8583 {

    private Iso8583() {}

    /** نتيجة عملية الدفع. */
    public static class PayResult {
        public String mti;
        public String responseCode; // F39
        public String authCode;     // F38
        public String stan;         // F11
        public boolean approved;
        public String raw;
    }

    /** أطوال الحقول الثابتة في ISO 8583:1987 (ما يحتاجه تحليل الرد + حقول الطلب). */
    private static int fieldLen(int field) {
        switch (field) {
            case 2:  return -1; // LLVAR (n..19)
            case 3:  return 6;
            case 4:  return 12;
            case 5:  return 12;
            case 11: return 6;
            case 12: return 6;
            case 13: return 4;
            case 14: return 4;
            case 22: return 3;
            case 23: return 3;
            case 24: return 3;
            case 25: return 2;
            case 37: return 12;
            case 38: return 6;
            case 39: return 2;
            case 41: return 8;
            case 42: return 15;
            case 49: return 3;
            default: return 0;  // غير معروف — أوقف التحليل
        }
    }

    /** بناء رسالة 0200 شراء مطابقة لصيغة pos-cli v1.2 (bitmap 7238000000001000). */
    public static String build0200(String pan, String cvv, long amountMinor, String stan) {
        SimpleDateFormat f7 = new SimpleDateFormat("MMddHHmmss", Locale.US);
        SimpleDateFormat f12 = new SimpleDateFormat("HHmmss", Locale.US);
        SimpleDateFormat f13 = new SimpleDateFormat("MMdd", Locale.US);
        Date now = new Date();

        String f52 = padCvv(cvv);

        return "0200"
                + "7238000000001000"                                  // bits 2,3,4,7,11,12,13,52
                + String.format(Locale.US, "%02d", pan.length()) + pan // F2 LLVAR
                + "000000"                                           // F3 رمز المعالجة (شراء)
                + String.format(Locale.US, "%012d", amountMinor)      // F4 المبلغ (وحدات صغرى)
                + f7.format(now)                                     // F7
                + stan                                               // F11
                + f12.format(now)                                    // F12
                + f13.format(now)                                    // F13
                + f52;                                               // F52 (16 خانة)
    }

    /** CVV مبطن إلى 16 خانة بحرف F — نفس ما يتوقعه normalizeCvv في السويتش. */
    static String padCvv(String cvv) {
        String c = (cvv == null || cvv.trim().isEmpty()) ? "000" : cvv.trim();
        StringBuilder sb = new StringBuilder(c);
        while (sb.length() < 16) sb.append('F');
        return sb.toString();
    }

    /** إرسال الرسالة مع تأطير 2 بايت واستقبال الرد 0210. */
    public static PayResult send(String host, int port, String msg) throws IOException {
        byte[] payload = msg.getBytes(StandardCharsets.US_ASCII);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            DataOutputStream dout = new DataOutputStream(out);
            dout.writeShort(payload.length); // 2-byte big-endian length header
            dout.write(payload);
            dout.flush();

            DataInputStream din = new DataInputStream(in);
            int len = din.readShort();
            if (len <= 0 || len > 4096) {
                throw new IOException("طول رد غير منطقي: " + len);
            }
            byte[] resp = new byte[len];
            din.readFully(resp);
            return parse0210(new String(resp, StandardCharsets.US_ASCII));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** تحليل رد 0210 (bitmap 3038000006000000) واستخراج F38/F39/F11. */
    public static PayResult parse0210(String raw) {
        PayResult r = new PayResult();
        r.raw = raw;
        if (raw == null || raw.length() < 20) return r;

        r.mti = raw.substring(0, 4);
        String bitmap = raw.substring(4, 20);
        byte[] bm;
        try {
            bm = Util.unhex(bitmap);
        } catch (Exception e) {
            return r;
        }

        Map<Integer, String> fields = new HashMap<Integer, String>();
        int pos = 20;
        for (int bit = 1; bit <= 64; bit++) {
            boolean set = (bm[(bit - 1) / 8] & (0x80 >> ((bit - 1) % 8))) != 0;
            if (!set) continue;
            int len = fieldLen(bit);
            if (len == 0) break; // حقل غير معروف — توقف
            if (len < 0) { // LLVAR
                if (pos + 2 > raw.length()) break;
                int l2 = Integer.parseInt(raw.substring(pos, pos + 2));
                pos += 2;
                if (pos + l2 > raw.length()) break;
                fields.put(bit, raw.substring(pos, pos + l2));
                pos += l2;
            } else {
                if (pos + len > raw.length()) break;
                fields.put(bit, raw.substring(pos, pos + len));
                pos += len;
            }
        }

        r.responseCode = fields.get(Integer.valueOf(39));
        r.authCode = fields.get(Integer.valueOf(38));
        r.stan = fields.get(Integer.valueOf(11));
        r.approved = "00".equals(r.responseCode) || "01".equals(r.responseCode);
        return r;
    }

    /** وصف كود الاستجابة F39 بالعربية. */
    public static String describe(String rc) {
        if (rc == null) return "لا يوجد رد";
        if ("00".equals(rc)) return "موافقة ✅";
        if ("01".equals(rc)) return "موافقة مع مراجعة ⚠";
        if ("05".equals(rc)) return "رفض / لا تشريف ❌";
        if ("14".equals(rc)) return "بطاقة غير معروفة لدى المُصدر ❌";
        if ("51".equals(rc)) return "رصيد غير كافٍ ❌";
        if ("54".equals(rc)) return "بطاقة منتهية ❌";
        if ("91".equals(rc)) return "المُصدر غير متاح ❌";
        return "كود آخر: " + rc;
    }
}
