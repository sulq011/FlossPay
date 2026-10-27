package com.floss83.diag;

/**
 * LithicCodes (build-16) — منطق الإصدار الحقيقي من Lithic، منقول بجافا نقية:
 *
 * المصدر: وثائق docs.lithic.com + ملاحظات ملاحظة فعلية على حساب sandbox البرنامج
 * (2026-10-27): بطاقة 4111111449434706 قُبل تفويضها بمبلغ 38.31$ (APPROVED)،
 * ورفض تفويض 9,999.99$ بسبب ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED، ورفضت محاولات
 * بدون ASA سليم بسبب MALFORMED_ASA_RESPONSE.
 *
 * - حالات البطاقة (card states) كما في منصة Lithic: OPEN / PAUSED / CLOSED
 * - قاموس أكواد النتائج (detailed_results) بشرح عربي — للمجهول شرح عام
 * - دالة قرار حد الصرف اليومي (منطق المُصدر) بحدود صريحة
 *
 * جافا نقية بالكامل: تُختبر في test/LithicCodesTest.java خارج الأندرويد
 * (مع CardProfile من المحفظة لفحص Luhn)، وتُستخدم هنا لتعريب نتائج محاكاة Lithic.
 */
public final class LithicCodes {

    private LithicCodes() {}

    // ===== حالات البطاقة =====
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_SUSPENDED = "SUSPENDED";

    /** هل تسمح حالة البطاقة بمرور التفويضات؟ فقط OPEN. */
    public static boolean stateAllowsAuth(String state) {
        return STATE_OPEN.equals(state);
    }

    /** شرح عربي لحالة البطاقة. */
    public static String stateLabelAr(String state) {
        if (state == null) return "حالة غير معروفة";
        switch (state) {
            case STATE_OPEN:     return "مفتوحة — تقبل الشراء";
            case STATE_PAUSED:   return "موقوفة مؤقتًا (إيقاف من الحامل/البرنامج) — ترفض كل تفويض";
            case STATE_CLOSED:   return "مغلقة نهائيًا";
            case STATE_SUSPENDED: return "معلّقة (خطر/مراجعة) — ترفض حتى رفع التعليق";
            default: return "حالة غير معروفة: " + state;
        }
    }

    // ===== أكواد النتائج (detailed_results) =====
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    public static final String ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED = "ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED";
    public static final String MALFORMED_ASA_RESPONSE = "MALFORMED_ASA_RESPONSE";
    public static final String CARD_PAUSED = "CARD_PAUSED";
    public static final String CARD_SPEND_LIMIT_EXCEEDED = "CARD_SPEND_LIMIT_EXCEEDED";
    public static final String CARD_CLOSED = "CARD_CLOSED";
    public static final String CARD_EXPIRED = "CARD_EXPIRED";
    public static final String CARD_NOT_FOUND = "CARD_NOT_FOUND";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String VELOCITY_LIMIT_EXCEEDED = "VELOCITY_LIMIT_EXCEEDED";
    public static final String MCC_NOT_ALLOWED = "MCC_NOT_ALLOWED";
    public static final String AUTH_RULE = "AUTH_RULE";
    public static final String NETWORK_DECLINE = "NETWORK_DECLINE";

    /** هل النتيجة قبول؟ */
    public static boolean isApproved(String result) {
        return APPROVED.equals(result);
    }

    /**
     * تعريب كود النتيجة. القاموس يغطي الأكواد المرصودة فعليًا في حسابنا
     * + الأكواد المنشورة الشائعة في وثائق Lithic؛ والمجهول يُشرح شرحًا عامًا
     * مع الكود الأصلي (لا نفترض معنى غير مؤكد).
     */
    public static String explainAr(String code) {
        if (code == null || code.isEmpty()) return "لا يوجد كود نتيجة";
        switch (code) {
            case APPROVED: return "✔ قبول — التفويض مرّ بنجاح من المُصدر";
            case DECLINED: return "✘ رفض — لم يمرّ التفويض";
            case ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED:
                return "✘ تجاوز الحد اليومي للصرف على الحساب (رصدناه فعليًا: 9,999.99$ رُفضت بهذا الكود)";
            case MALFORMED_ASA_RESPONSE:
                return "✘ رد ASA غير سليم — قواعد التفويض الخارجية (Auth Stream Access) لم تُجب إجابة صالحة";
            case CARD_PAUSED: return "✘ البطاقة موقوفة مؤقتًا — الحالة PAUSED";
            case CARD_SPEND_LIMIT_EXCEEDED: return "✘ تجاوز حد الصرف المضبوط على البطاقة نفسها";
            case CARD_CLOSED: return "✘ البطاقة مغلقة";
            case CARD_EXPIRED: return "✘ البطاقة منتهية الصلاحية";
            case CARD_NOT_FOUND: return "✘ رقم البطاقة غير معروف لدى المُصدر (PAN غير صادر)";
            case INSUFFICIENT_FUNDS: return "✘ رصيد غير كافٍ في الحساب الممول";
            case VELOCITY_LIMIT_EXCEEDED: return "✘ تجاوز حد السرعة (عدد/قيمة المعاملات في فترة قصيرة)";
            case MCC_NOT_ALLOWED: return "✘ فئة التاجر (MCC) غير مسموحة على هذه البطاقة";
            case AUTH_RULE: return "✘ رفض بقاعدة تفويض مبرمجة (Authorization Rule) — راجع rule_results";
            case NETWORK_DECLINE: return "✘ رفض من الشبكة (Visa/MasterCard) لا من المُصدر";
            default: return "✘ كود مُصدر غير معروف: " + code + " — راجع وثائق Lithic";
        }
    }

    /** أول كود شرح من مصفوفة أكواد (detailed_results). */
    public static String explainFirstAr(java.util.List<String> codes) {
        if (codes == null || codes.isEmpty()) return explainAr(null);
        return explainAr(codes.get(0));
    }

    // ===== قرار حد الصرف اليومي (منطق المُصدر) =====

    /** نتيجة تقييم التفويض مقابل الحد اليومي. */
    public static class SpendDecision {
        public boolean approved;
        public String reason;        // كود النتيجة (APPROVED أو سبب الرفض)
        public long remainingMinor;  // المتبقي من الحد بعد العملية (إن رُفضت يبقى كما كان)

        @Override public String toString() {
            return (approved ? "APPROVED" : reason) + " remaining=" + remainingMinor;
        }
    }

    /**
     * منطق حد الصرف اليومي كما يطبقه مُصدر حقيقي:
     * - حد ≤ 0 = لا حد مضبوط صراحةً ⇒ قبول (مع ملاحظة أن البرنامج الفعلي قد
     *   يفرض حدودًا افتراضية — كما رأينا في sandbox التجريبي: مبالغ صغيرة قُبلت
     *   ومبلغ 9,999.99$ رُفض بحد البرنامج الافتراضي).
     * - spent + amount ≤ limit ⇒ قبول (حتى لو على الحد تمامًا).
     * - تجاوز ⇒ رفض ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED.
     */
    public static SpendDecision authorizeAgainstDailyLimit(
            long dailyLimitMinor, long spentTodayMinor, long amountMinor) {
        SpendDecision d = new SpendDecision();
        if (dailyLimitMinor <= 0) {
            d.approved = true;
            d.reason = APPROVED;
            d.remainingMinor = -1; // لا حد ⇒ لا متبقيّ محسوب
            return d;
        }
        if (spentTodayMinor < 0) spentTodayMinor = 0;
        if (amountMinor < 0) amountMinor = 0;
        long remaining = dailyLimitMinor - spentTodayMinor;
        d.remainingMinor = remaining;
        if (spentTodayMinor + amountMinor <= dailyLimitMinor) {
            d.approved = true;
            d.reason = APPROVED;
            d.remainingMinor = remaining - amountMinor;
        } else {
            d.approved = false;
            d.reason = ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED;
        }
        return d;
    }

    /** تحويل مبلغ نصي بالدولار إلى وحدات صغرى (سنت) — "38.31" → 3831. */
    public static long parseUsdMinor(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()) * 100.0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** تنسيق سنتات إلى دولار: 3831 → "38.31$". */
    public static String fmtUsd(long minor) {
        if (minor < 0) return "-";
        return String.format(java.util.Locale.US, "%.2f$", minor / 100.0);
    }
}
