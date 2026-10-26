package com.floss83.pay;

/**
 * محرك تعريف الشبكة من رقم البطاقة (BIN/IIN) — build-11.
 *
 * القواعد منقولة وموسّعة من juspay/card-validator (MIT © 2016 Juspay Technologies):
 * https://github.com/juspay/card-validator
 *
 * يستخدمه المحفظة لتجميع البطاقات (فيزا / ماستركارد / أمريكان إكسبريس / شبكات أخرى)
 * ولعرض اسم الشبكة الصحيح لكل بطاقة. جافا نقية — قابلة للاختبار خارج الأندرويد.
 */
public class CardProfile {

    public static final String NETWORK_VISA = "Visa";
    public static final String NETWORK_VISA_ELECTRON = "Visa Electron";
    public static final String NETWORK_MASTERCARD = "MasterCard";
    public static final String NETWORK_AMEX = "Amex";
    public static final String NETWORK_JCB = "JCB";
    public static final String NETWORK_DINERS = "Diners";
    public static final String NETWORK_DISCOVER = "Discover";
    public static final String NETWORK_MAESTRO = "Maestro";
    public static final String NETWORK_RUPAY = "RuPay";
    public static final String NETWORK_OTHER = "أخرى";

    /** مجموعات العرض في المحفظة. */
    public static final int GROUP_VISA = 0;
    public static final int GROUP_MASTERCARD = 1;
    public static final int GROUP_AMEX = 2;
    public static final int GROUP_OTHER = 3;

    private static final long[][] RUPAY_RANGES = {
        {508500, 508999}, {606985, 607384}, {607385, 607484}, {607485, 607984},
        {608001, 608100}, {608101, 608200}, {608201, 608300}, {608301, 608350},
        {608351, 608500}, {652150, 652849}, {652850, 653049}, {653050, 653149},
        {817200, 819899}, {819900, 820199},
    };

    /** يكتشف الشبكة من الـ PAN (قواعد juspay بترتيبها الأصلي). */
    public static String detect(String pan) {
        String p = pan == null ? "" : pan.trim();
        if (p.length() < 4) {
            if (p.startsWith("34") || p.startsWith("37")) return NETWORK_AMEX;
            if (p.startsWith("4")) return NETWORK_VISA;
            return NETWORK_OTHER;
        }

        // Amex: 34/37 (15 خانة، CVV 4)
        if (p.startsWith("34") || p.startsWith("37")) return NETWORK_AMEX;

        // Diners Club / Carte Blanche: 300-305 — Diners Intl: 36/38/39 + 309
        if (p.startsWith("30") && p.charAt(2) >= '0' && p.charAt(2) <= '5') return NETWORK_DINERS;
        if (p.startsWith("36") || p.startsWith("38") || p.startsWith("39") || p.startsWith("309")) return NETWORK_DINERS;

        // JCB: 3528-3589
        if (p.startsWith("35")) {
            char c3 = p.charAt(2), c4 = p.charAt(3);
            boolean jcb = (c3 == '2' && (c4 == '8' || c4 == '9')) || (c3 >= '3' && c3 <= '8');
            if (jcb) return NETWORK_JCB;
        }

        // Visa Electron قبل Visa
        if (startsWithAny(p, new String[]{"4026", "417500", "4508", "4844", "4913", "4917"})) return NETWORK_VISA_ELECTRON;
        if (p.startsWith("4")) return NETWORK_VISA;

        long bin6 = bin6(p);

        // MasterCard: 51-55 + السلسلة الجديدة 222100-272099
        if (inRange(bin6, 222100, 272099) || inRange(bin6, 510000, 559999)) return NETWORK_MASTERCARD;

        // Maestro
        if (startsWithAny(p, new String[]{"5018", "5081", "5044", "5020", "5038", "603845", "6304",
                "6759", "6761", "6762", "6763", "6220", "504834", "504817", "504645"})) return NETWORK_MAESTRO;

        // RuPay (الهند)
        for (long[] r : RUPAY_RANGES) {
            if (inRange(bin6, r[0], r[1])) return NETWORK_RUPAY;
        }

        // Discover: 6011, 622126-622925, 65
        if (p.startsWith("6011")) return NETWORK_DISCOVER;
        if (p.startsWith("622") && inRange(bin6, 622126, 622925)) return NETWORK_DISCOVER;
        if (p.startsWith("65")) return NETWORK_DISCOVER;

        return NETWORK_OTHER;
    }

    /** مجموعة العرض في المحفظة. */
    public static int group(String pan) {
        String n = detect(pan);
        if (NETWORK_VISA.equals(n) || NETWORK_VISA_ELECTRON.equals(n)) return GROUP_VISA;
        if (NETWORK_MASTERCARD.equals(n) || NETWORK_MAESTRO.equals(n)) return GROUP_MASTERCARD;
        if (NETWORK_AMEX.equals(n)) return GROUP_AMEX;
        return GROUP_OTHER;
    }

    /** اسم المجموعة بالعربي. */
    public static String groupName(int g) {
        switch (g) {
            case GROUP_VISA: return "بطاقات Visa";
            case GROUP_MASTERCARD: return "بطاقات MasterCard";
            case GROUP_AMEX: return "بطاقات أمريكان إكسبريس";
            default: return "الشبكات الأخرى";
        }
    }

    /** تحقق Luhn من رقم البطاقة. */
    public static boolean luhn(String pan) {
        String p = pan == null ? "" : pan.trim();
        if (p.length() < 12) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = p.length() - 1; i >= 0; i--) {
            int d = p.charAt(i) - '0';
            if (d < 0 || d > 9) return false;
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static long bin6(String p) {
        try {
            return Long.parseLong(p.substring(0, 6));
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean inRange(long v, long lo, long hi) {
        return v >= lo && v <= hi;
    }

    private static boolean startsWithAny(String p, String[] prefixes) {
        for (String x : prefixes) if (p.startsWith(x)) return true;
        return false;
    }
}
