package com.floss83.diag;

/**
 * GatewayCards — build-12: مكتبة بطاقات الاختبار الرسمية للبوابات الشائعة
 * (Moyasar / MyFatoorah / PayTabs / HyperPay / Checkout.com / Tap).
 *
 * المصدر: وثائق كل بوابة المعلنة للعموم.
 * ⚠️ مبدأ أساسي: هذه الأرقام تعمل فقط في وضع التجربة (Test/Sandbox) لدى البوابة.
 * في صفحات الدفع الحقيقية (Live) سيُرفض أيٌّ منها — وهذا متوقع وليس عطلًا.
 * الفائدة التشخيصية: إدخال موحّد (نفس البطاقة/التاريخ/CVV) ومقارنة ردود
 * البوابة عبر المبالغ والمتاجر، وتسجيل النتيجة في قسم «اختبار بوابة دفع».
 */
public class GatewayCards {

    public static class Card {
        public final String pan;      // رقم البطاقة (أو المعرف البديل: جوال STC Pay…)
        public final String expiry;  // MM/YY أو "أي تاريخ مستقبلي"
        public final String cvv;
        public final String network;  // Visa / MasterCard / mada / Amex / ...
        public final String expected; // السلوك المتوقع في بيئة تجربة البوابة

        public Card(String pan, String expiry, String cvv, String network, String expected) {
            this.pan = pan;
            this.expiry = expiry;
            this.cvv = cvv;
            this.network = network;
            this.expected = expected;
        }
    }

    public static class Gateway {
        public final String name;
        public final String note;
        public final Card[] cards;

        public Gateway(String name, String note, Card[] cards) {
            this.name = name;
            this.note = note;
            this.cards = cards;
        }
    }

    private static final String ANY_EXP = "أي تاريخ مستقبلي";

    public static final Gateway[] GATEWAYS = {

        new Gateway("Moyasar (ميسر)",
            "بيئة التجربة لدى Moyasar. القواعد: أي تاريخ مستقبلي، CVV ثلاث خانات، واسم حامل البطاقة من مقطعين (مثل: Test Test). أي بطاقة غير المدرجة هنا = فشل.",
            new Card[] {
                new Card("4201320111111010", ANY_EXP, "123", "mada", "موافقة APPROVED (00)"),
                new Card("4201320000013020", ANY_EXP, "123", "mada", "رفض: فشل غير محدد (99)"),
                new Card("4201320000311101", ANY_EXP, "123", "mada", "رفض: رصيد غير كافٍ (51)"),
                new Card("4201321234411220", ANY_EXP, "123", "mada", "رفض عام (05)"),
                new Card("4201322267774310", ANY_EXP, "123", "mada", "رفض: بطاقة منتهية (54)"),
                new Card("4201326324640570", ANY_EXP, "123", "mada", "رفض: تجاوز حد السحب (61)"),
                new Card("4111111111111111", ANY_EXP, "123", "Visa", "موافقة APPROVED (00)"),
                new Card("4111114005765430", ANY_EXP, "123", "Visa", "موافقة + مصادقة 3DS بدون تفاعل (Frictionless)"),
                new Card("4111118250252531", ANY_EXP, "123", "Visa", "3DS غير متاح (ECI 06) — يجب تفعيل الشراء أونلاين من البنك"),
                new Card("4111113343111067", ANY_EXP, "123", "Visa", "خطأ خدمة 3DS أثناء فحص التسجيل"),
                new Card("4111112205628150", ANY_EXP, "123", "Visa", "خطأ 3DS أثناء محاولة المصادقة"),
                new Card("4111115784228433", ANY_EXP, "123", "Visa", "رفض محاولة المصادقة من البنك المصدر"),
                new Card("4111115620358287", ANY_EXP, "123", "Visa", "المصادقة غير متاحة حاليًا — أعد المحاولة لاحقًا"),
                new Card("4111116611600661", ANY_EXP, "123", "Visa", "البطاقة غير مسجلة في خدمة 3DS"),
            }),

        new Gateway("MyFatoorah (ماي فاتورة)",
            "بيئة التجربة (demo) لدى MyFatoorah. اسم حامل البطاقة: test test.",
            new Card[] {
                new Card("5123450000000008", "01/39", "100", "MasterCard", "موافقة — البطاقة القياسية في وثائقهم"),
                new Card("2223000000000007", "01/39", "100", "MasterCard", "موافقة (سلسلة 2xxx)"),
                new Card("5453010000095539", "12/25", "300", "MasterCard", "موافقة"),
                new Card("5457210001000019", "12/25", "212", "MasterCard", "موافقة"),
                new Card("4508750015741019", ANY_EXP, "أي CVV", "Visa", "موافقة"),
                new Card("4012001037141112", "12/24", "207", "Visa", "موافقة"),
                new Card("345678901234564", "04/37", "1000", "Amex", "فشل غير محدد — بطاقة لاختبار الرفض"),
                new Card("4464040000000007", "02/29", "123", "mada", "موافقة (Capture)"),
                new Card("5297412542005689", "05/25", "350", "mada", "موافقة"),
                new Card("0557877988", "-", "OTP: 1234", "STC Pay", "محفظة STC Pay — أدخل الجوال ورمز OTP"),
            }),

        new Gateway("PayTabs",
            "تعمل حصريًا مع حساب Test Profile لدى PayTabs (Dashboard ← Developers ← Testing ← Test Cards). تاريخ انتهاء: أي تاريخ مستقبلي.",
            new Card[] {
                new Card("4000000000000002", ANY_EXP, "123", "Visa", "موافقة — مسجلة في 3DS (يظهر شريط محاكاة المصادقة)"),
                new Card("4111111111111111", ANY_EXP, "123", "Visa", "بدون 3DS"),
                new Card("4012001036983332", ANY_EXP, "530", "Visa", "موافقة"),
                new Card("5498383801606532", ANY_EXP, "977", "MasterCard", "موافقة — مسجلة في 3DS"),
                new Card("5200000000000007", ANY_EXP, "977", "MasterCard", "موافقة — مسجلة في 3DS"),
                new Card("5200000000000114", ANY_EXP, "977", "MasterCard", "بدون 3DS"),
            }),

        new Gateway("HyperPay",
            "بيئة الاختبار test.oppwa.com (منصة ACI). مهم: كود نجاح الاختبار هو 000.100.110 وليس 000.000.000 — وخطأ mada الشائع سببه Entity ID خاص بـ mada.",
            new Card[] {
                new Card("4200000000000000", "05/30", "123", "Visa", "موافقة"),
                new Card("5200000000000007", "05/30", "123", "MasterCard", "موافقة"),
                new Card("4000000000000002", "05/30", "123", "Visa", "رفض — لاختبار مسار الفشل"),
            }),

        new Gateway("Checkout.com",
            "بيئة Sandbox لدى Checkout.com. CVV: ثلاث خانات (أربع لـ Amex)، أي تاريخ مستقبلي. ملاحظة مفيدة جدًا: 4242424242424242 بمبلغ 101 يُحفّز رفض 20051 — بطاقة لاختبار سلوك المبالغ!",
            new Card[] {
                new Card("4242424242424242", ANY_EXP, "100", "Visa", "موافقة (20000/10000) — وبمبلغ 101: رفض (20051)"),
                new Card("4000020000000000", ANY_EXP, "100", "Visa", "موافقة (بطاقة أمريكية)"),
                new Card("4273149019799094", ANY_EXP, "100", "Visa", "للتحقق من البطاقة فقط — الرفض عند Capture/Void مقصود"),
                new Card("378282246310005", ANY_EXP, "1000", "Amex", "موافقة (بطاقة Amex القياسية)"),
            }),

        new Gateway("Tap + بطاقات الشبكات القياسية",
            "Tap لا تنشر أرقام اختبار عامة — تظهر داخل لوحة التاجر فقط (Developers ← Testing). للفحص من صفحة الدفع مباشرة: استخدم البطاقات القياسية أدناه (Luhn سليم) ولاحظ رد الصفحة لكل شبكة، أو بطاقة حقيقية بإذن صاحبها.",
            new Card[] {
                new Card("4242424242424242", "12/30", "100", "Visa", "قياسية — لاختبار رسائل البوابة أمام شبكة Visa"),
                new Card("5555555555554444", "12/30", "100", "MasterCard", "قياسية — سلسلة 55xx"),
                new Card("5105105105105100", "12/30", "100", "MasterCard", "قياسية — سلسلة 51xx"),
                new Card("2221000000000009", "12/30", "100", "MasterCard", "قياسية — سلسلة 2xxx الجديدة"),
                new Card("378282246310005", "12/30", "1000", "Amex", "قياسية — 15 خانة"),
                new Card("4462030000000000", "12/30", "100", "mada", "قياسية — نطاق mada (446203)"),
            }),
    };
}
