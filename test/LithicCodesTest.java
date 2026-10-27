package com.floss83.diag;

import com.floss83.pay.CardProfile;

import java.util.Arrays;

/**
 * LithicCodesTest (build-16) — اختبار منطق الإصدار المنقول من Lithic.
 * جافا نقية، تعمل بجافا عادي خارج الأندرويد:
 *   javac -encoding UTF-8 -d /tmp/emuobj src/com/floss83/pay/{EmvCard,EmvDiag,CardProfile}.java diag/src/com/floss83/diag/LithicCodes.java test/EmvCardTest.java test/LithicCodesTest.java
 *   java -cp /tmp/emuobj com.floss83.diag.LithicCodesTest
 *
 * يشمل التحقق على بيانات حقيقية رُصدت من حساب Lithic sandbox بتاريخ 2026-10-27:
 * - بطاقة صادرة فعليًا: PAN 4111111449434706 (تدخل Luhn — نفس فحص المحفظة)
 * - تفويض 38.31$ قُبل (APPROVED) وتفويض 9,999.99$ رُفض (ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED)
 */
public class LithicCodesTest {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✔ " + name); }
        else    { failed++; System.out.println("  ✘ FAIL: " + name); }
    }

    public static void main(String[] args) {
        System.out.println("=== [L1] حالات البطاقة (card states) ===");
        check("OPEN تسمح بالتفويض", LithicCodes.stateAllowsAuth("OPEN"));
        check("PAUSED ترفض", !LithicCodes.stateAllowsAuth("PAUSED"));
        check("CLOSED ترفض", !LithicCodes.stateAllowsAuth("CLOSED"));
        check("null يرفض", !LithicCodes.stateAllowsAuth(null));
        check("شرح OPEN عربي غير فارغ", LithicCodes.stateLabelAr("OPEN").contains("مفتوحة"));
        check("شرح PAUSED يذكر الإيقاف", LithicCodes.stateLabelAr("PAUSED").contains("موقوفة"));
        check("شرح حالة مجهولة يذكر الكود", LithicCodes.stateLabelAr("WEIRD").contains("WEIRD"));

        System.out.println("=== [L2] أكواد النتائج المرصودة فعليًا (sandbox 2026-10-27) ===");
        check("APPROVED = قبول", LithicCodes.isApproved("APPROVED"));
        check("DECLINED ليس قبولًا", !LithicCodes.isApproved("DECLINED"));
        String daily = LithicCodes.explainAr("ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED");
        check("شرح الحد اليومي عربي ويذكر الرفض", daily.contains("الحد اليومي") && daily.contains("✘"));
        String asa = LithicCodes.explainAr("MALFORMED_ASA_RESPONSE");
        check("شرح ASA يذكر قواعد التفويض", asa.contains("ASA") && asa.contains("✘"));
        check("شرح APPROVED عربي", LithicCodes.explainAr("APPROVED").contains("قبول"));
        String unknown = LithicCodes.explainAr("SOME_NEW_CODE");
        check("الكود المجهول يُعاد أصله في الشرح (لا معنى مفترض)", unknown.contains("SOME_NEW_CODE"));
        check("null يعطي شرحًا آمنًا", LithicCodes.explainAr(null).contains("لا يوجد"));
        check("explainFirstAr يأخذ أول كود",
                LithicCodes.explainFirstAr(Arrays.asList("CARD_PAUSED", "X")).contains("PAUSED"));
        check("explainFirstAr لقائمة فارغة", LithicCodes.explainFirstAr(Arrays.<String>asList()).contains("لا يوجد"));

        System.out.println("=== [L3] قرار حد الصرف اليومي (منطق المُصدر) ===");
        LithicCodes.SpendDecision d1 = LithicCodes.authorizeAgainstDailyLimit(50000, 10000, 3831);
        check("38.31$ داخل حد 500$ مع منفق 100$ ⇒ قبول", d1.approved);
        check("المتبقي بعد القبول = 361.69$ (36169)", d1.remainingMinor == 36169);
        LithicCodes.SpendDecision d2 = LithicCodes.authorizeAgainstDailyLimit(50000, 49000, 99999);
        check("999.99$ فوق المتبقي ⇒ رفض", !d2.approved);
        check("سبب الرفض = ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED",
                d2.reason.equals(LithicCodes.ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED));
        LithicCodes.SpendDecision d3 = LithicCodes.authorizeAgainstDailyLimit(50000, 50000, 0);
        check("على الحد تمامًا بمبلغ 0 ⇒ قبول", d3.approved);
        LithicCodes.SpendDecision d4 = LithicCodes.authorizeAgainstDailyLimit(50000, 45000, 5000);
        check("الوصول للحد تمامًا (450+50=500$) ⇒ قبول", d4.approved);
        check("المتبقي بعد بلوغ الحد = 0", d4.remainingMinor == 0);
        LithicCodes.SpendDecision d5 = LithicCodes.authorizeAgainstDailyLimit(0, 99999, 3831);
        check("لا حد مضبوط (0) ⇒ قبول (ملاحظة: البرنامج الفعلي قد يفرض حدًا افتراضيًا)", d5.approved);
        LithicCodes.SpendDecision d6 = LithicCodes.authorizeAgainstDailyLimit(10000, -5, 10000);
        check("منفق سالب يُعامل كصفر (100$=الحد ⇒ قبول)", d6.approved);

        System.out.println("=== [L4] بطاقة Lithic الصادرة فعليًا مقابل فحص المحفظة ===");
        String realPan = "4111111449434706"; // صادرة من sandbox Lithic 2026-10-27
        check("PAN الحقيقي يجتاز Luhn (نفس فحص المحفظة)", CardProfile.luhn(realPan));
        check("PAN محرف يُرفض بـ Luhn", !CardProfile.luhn("4111111449434707"));
        check("شبكة PAN الحقيقي = Visa", CardProfile.detect(realPan).equals(CardProfile.NETWORK_VISA));

        System.out.println("=== [L5] تحويل المبالغ (سنت دولار) ===");
        check("parseUsdMinor(\"38.31\") = 3831", LithicCodes.parseUsdMinor("38.31") == 3831);
        check("parseUsdMinor(\"9999.99\") = 999999", LithicCodes.parseUsdMinor("9999.99") == 999999);
        check("parseUsdMinor(\"abc\") = -1", LithicCodes.parseUsdMinor("abc") == -1);
        check("fmtUsd(3831) = 38.31$", LithicCodes.fmtUsd(3831).equals("38.31$"));
        check("fmtUsd(-1) = -", LithicCodes.fmtUsd(-1).equals("-"));

        System.out.println();
        System.out.println("LITHIC TESTS: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
