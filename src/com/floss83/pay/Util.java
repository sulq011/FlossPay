package com.floss83.pay;

import java.nio.charset.StandardCharsets;

/** أدوات مساعدة: Luhn، نوع البطاقة، الترميز hex، تنسيق المبالغ. */
public final class Util {

    private Util() {}

    /** تحقق Luhn من صحة رقم البطاقة. */
    public static boolean luhnValid(String pan) {
        if (pan == null || pan.length() < 12) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** نوع البطاقة من الـ BIN. */
    public static String brand(String pan) {
        if (pan == null || pan.length() < 2) return "بطاقة";
        char c0 = pan.charAt(0);
        if (c0 == '4') return "Visa";
        if (c0 == '2') return "Mastercard";
        if (c0 == '5' && pan.charAt(1) >= '1' && pan.charAt(1) <= '5') return "Mastercard";
        if (pan.startsWith("34") || pan.startsWith("37")) return "Amex";
        return "بطاقة";
    }

    /** إخفاء البان: 4000 •••• 7899 */
    public static String mask(String pan) {
        if (pan == null || pan.length() < 8) return pan == null ? "" : pan;
        return pan.substring(0, 4) + " •••• " + pan.substring(pan.length() - 4);
    }

    /** تنسيق مبلغ من الوحدات الصغرى: 2550 -> "25.50" */
    public static String formatAmount(long minor) {
        return String.format(java.util.Locale.US, "%.2f", minor / 100.0);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static String asciiStr(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }
}
