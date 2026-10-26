package com.floss83.pay;

import java.util.ArrayList;
import java.util.List;

/**
 * محرك تحليل جلسات POS — «بوت تشخيص أخطاء نقاط البيع» (جافا نقية، بلا Android).
 *
 * يستقبل سجل أوامر APDU الكاملة (أمر/رد/زمن) كما سجّلتها HceService أثناء اللمسة،
 * ويحدد إلى أي مرحلة وصل جهاز POS فعليًا، ويشخّص الطبقة المتعثرة:
 *
 *   0 RF/توجيه      — الجهاز لم يرسل أي أمر إطلاقًا (هوائي/NFC/Tap&pay)
 *   1 PPSE          — قرأ الدليل ولم يختر تطبيقًا (جدول AID غير مهيأ)
 *   2 SELECT AID    — اختار التطبيق ولم يكمل GPO (تعطل kernel مبكر)
 *   3 GPO          — نجح GPO ولم يقرأ السجلات (تعطل kernel بعد GPO)
 *   4 READ RECORD  — قرأ البيانات كاملة وتوقف قبل GENERATE AC (نمط خلل برمجي)
 *   5 GENERATE AC   — مسار EMV كامل حتى الاعتماد الأونلاين (الرفض من الشبكة = متوقع لبطاقة معمل)
 *   6 EXT AUTH/GEN2 — اكتمل المسار حتى ما بعد استجابة المصدر (جهاز سليم 100%)
 *
 * جوهر الفائدة التشخيصية: التاجر يبدّل جهازه بلا سبب — هذا المحرك يقول له
 * بالضبط أين تعثر الجهاز (إعداد؟ برمجية kernel؟ هاردوير RF؟ أم أن الشبكة هي التي رفضت).
 */
public class EmvDiag {

    /** أمر/رد واحد كما تبادله الجهاز مع البطاقة. */
    public static class Entry {
        public final String cmd;
        public final String resp;
        public final double ms;

        public Entry(String cmd, String resp, double ms) {
            this.cmd = cmd == null ? "" : cmd;
            this.resp = resp == null ? "" : resp;
            this.ms = ms;
        }
    }

    /** نتيجة التحليل. */
    public static class Result {
        public int stageNum;          // 0..6
        public String stage;         // اسم المرحلة
        public String verdict;       // التشخيص بالعربي
        public String aid = "-";     // التطبيق الذي اختاره الجهاز
        public String kernel = "-";  // mada/M-Chip أو Visa qVSDC
        public int apduCount;
        public double totalMs;
        public int errors;           // عدد الردود غير 9000
        public boolean flossReader;  // جلسة قارئ FlossPay (عقدنا المغلق)
        public List<String> lines = new ArrayList<String>(); // تفصيل APDU بـ APDU
    }

    // ============ التحليل ============
    public static Result analyze(List<Entry> entries) {
        Result r = new Result();
        if (entries == null || entries.isEmpty()) {
            r.stageNum = 0; r.stage = "لا أوامر";
            r.verdict = "لم يُرسل الجهاز أي أمر ISO للبطاقة. إن كان الجهاز ملتصقًا بالهاتف فالمشكلة في طبقة RF/الهوائي، "
                    + "أو NFC مقفل، أو FlossPay ليس التطبيق الافتراضي في Tap & pay.";
            return r;
        }

        r.apduCount = entries.size();
        double start = entries.get(0).ms;
        double end = entries.get(entries.size() - 1).ms;
        r.totalMs = Math.max(0, end - start);

        boolean hasPpse = false, hasAidSelect = false, hasGpo = false, hasReadRec = false,
                hasGenAc = false, hasExtAuth = false, hasFloss = false;

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String kind = classify(e.cmd);
            String sw = e.resp.length() >= 4 ? e.resp.substring(e.resp.length() - 4) : e.resp;
            boolean ok = sw.equals("9000");

            if (kind.startsWith("SELECT PPSE")) hasPpse = true;
            else if (kind.startsWith("SELECT")) {
                hasAidSelect = true;
                String aid = kind.contains("mada") ? "mada (A0000002281010)"
                        : kind.contains("Visa") ? "Visa (A0000000031010)"
                        : kind.contains("MasterCard") ? "MasterCard (A0000000041010)"
                        : kind.contains("FlossPay") ? "FlossPay (مغلق)" : kind;
                r.aid = aid;
                r.kernel = kind.contains("Visa") ? "Kernel 3 (qVSDC)" : "Kernel 2 (M/Chip)";
            }
            else if (kind.equals("GPO")) hasGpo = true;
            else if (kind.equals("READ RECORD")) hasReadRec = true;
            else if (kind.startsWith("GENERATE AC")) hasGenAc = true;
            else if (kind.equals("EXTERNAL AUTH")) hasExtAuth = true;

            if (kind.contains("FlossPay") || e.cmd.startsWith("80C0") || e.cmd.startsWith("80D0")) hasFloss = true;
            if (!ok) r.errors++;

            r.lines.add(String.format(java.util.Locale.US, "#%d %s%s | %s | %.1fms",
                    i + 1, kind, ok ? "" : " ←" + sw, e.cmd, e.ms - start));
        }

        if (hasFloss && !hasPpse) {
            r.flossReader = true;
            r.stageNum = 9; r.stage = "قارئ FlossPay";
            r.verdict = "جلسة قارئ FlossPay (عقدنا المغلق بين جهازينا) — ليست جلسة POS خارجية، "
                    + "سجلتها للتوثيق فقط.";
            return r;
        }

        if (!hasPpse) {
            r.stageNum = 0; r.stage = "قبل PPSE";
            r.verdict = "الجهاز أرسل أوامر لكنه لم يبدأ بـ SELECT PPSE — بنية غير قياسية: "
                    + "أغلب الظن أن الجهاز يطلب AID بشكل مباشر أو إصدار قديم. راجع السطر الأول أدناه.";
        } else if (!hasAidSelect) {
            r.stageNum = 1; r.stage = "بعد PPSE";
            r.verdict = "وصل الجهاز إلى الدليل (PPSE) وقرأ قائمة التطبيقات ثم لم يختر أي تطبيق — "
                    + "هذا نمط «جدول AID / Combination غير مهيأ» في إعداد الجهاز: مشكلة إعداد، لا عيب هاردوير.";
        } else if (!hasGpo) {
            r.stageNum = 2; r.stage = "بعد اختيار التطبيق";
            r.verdict = "اختار الجهاز التطبيق (" + r.aid + ") لكنه لم يكمل GET PROCESSING OPTIONS — "
                    + "تعطل مبكر في kernel الجهاز. جرّب إعادة تشغيل الجهاز؛ تكرارها يعني خللًا برمجيًا في نسخته.";
        } else if (!hasReadRec) {
            r.stageNum = 3; r.stage = "بعد GPO";
            r.verdict = "نجح GPO لكن الجهاز لم يقرأ أي سجل بيانات — تعطل kernel بعد GPO. "
                    + "نمط برمجي (تطبيق الدفع في الجهاز)، وليس قراءة/هوائي.";
        } else if (!hasGenAc) {
            r.stageNum = 4; r.stage = "بعد قراءة السجلات";
            r.verdict = "قرأ الجهاز بيانات البطاقة كاملة (Track2/PAN/CDOLs) ثم توقف قبل GENERATE AC — "
                    + "هذا هو النمط الكلاسيكي لخلل برمجي في تطبيق/kernel الجهاز: "
                    + "الجهاز استلم كل شيء ثم فشل داخليًا. مرشح قوي للتحديث أو الاستبدال.";
        } else if (!hasExtAuth) {
            r.stageNum = 5; r.stage = "بعد GENERATE AC";
            r.verdict = "مسار EMV كامل: القراءة والاختيار والـ cryptogram كلها نجحت، وذهب الجهاز "
                    + "للاعتماد الأونلاين. أي رفض في هذه الحالة يأتي من الشبكة/المصدر "
                    + "(متوقع ومقصود لبطاقة معمل) — مستويات الجهاز الخمس سليمة.";
        } else {
            r.stageNum = 6; r.stage = "اكتمال كامل";
            r.verdict = "اكتمل المسار حتى بعد استجابة المصدر (EXTERNAL AUTHENTICATE + GENERATE AC ثانٍ) — "
                    + "الجهاز يعالج EMV بالكامل وبلا أي تعثر. جهاز سليم 100% في كل المراحل.";
        }

        if (r.errors > 0) {
            r.verdict += " — لاحظ " + r.errors + " ردًّا غير 9000 (راجع «←» في التفصيل: ردود 6A86/6D00 "
                    + "من البطاقة تشير لأوامر غير قياسية أرسلها الجهاز = مؤشر خلل برمجي عنده، "
                    + "بينما 6A82/6A83 أثناء البحث عن تطبيقات سلوك طبيعي).";
        }
        return r;
    }

    /** تصنيف أمر APDU إلى اسم بشري. */
    static String classify(String cmd) {
        if (cmd == null || cmd.length() < 8) return "أمر غير معروف";
        String cla = cmd.substring(0, 2);
        String ins = cmd.substring(2, 4);
        String data = cmd.length() > 10 ? cmd.substring(Math.min(10, cmd.length())) : "";

        if (ins.equals("A4")) {
            if (data.contains(EmvCard.PPSE)) return "SELECT PPSE (الدليل)";
            if (data.contains(EmvCard.AID_MADA)) return "SELECT AID mada";
            if (data.contains(EmvCard.AID_VISA)) return "SELECT AID Visa";
            if (data.contains(EmvCard.AID_MC)) return "SELECT AID MasterCard";
            if (data.contains(EmvCard.AID_FLOSS83)) return "SELECT AID FlossPay";
            return "SELECT AID آخر: " + data;
        }
        if (cla.equals("80") && ins.equals("A8")) return "GPO";
        if (cla.equals("00") && ins.equals("B2")) return "READ RECORD";
        if (cla.equals("80") && ins.equals("AE")) return "GENERATE AC";
        if (cla.equals("00") && ins.equals("82")) return "EXTERNAL AUTH";
        if (ins.equals("CA")) return "GET DATA";
        if (cla.equals("80") && ins.equals("C0")) return "GET_CARD (FlossPay)";
        if (cla.equals("80") && ins.equals("D0")) return "PAY (FlossPay)";
        return "أمر آخر " + cla + " " + ins;
    }
}
