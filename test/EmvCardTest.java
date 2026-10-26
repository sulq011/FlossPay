package com.floss83.pay;

import java.util.Arrays;

/**
 * محاكي POS كامل لاختبار محرك بطاقة EMV — جافا نقية، تشغَّل بجافا عادي خارج الأندرويد.
 * يحاكي: PPSE → SELECT AID → GPO → READ RECORDs → GENERATE AC → EXTERNAL AUTH → 2nd GEN AC
 * مع فك TLV وتحققات صارمة. يطبع سجل APDU مثل مفتاح POS الحقيقي.
 */
public class EmvCardTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        EmvCard card = new EmvCard("4000001234567899", "12/26", "SAD AM");

        log("=== [1] SELECT PPSE (مثل POS حقيقي) ===");
        byte[] r = card.process(unhex("00A404000E" + asciiHex("2PAY.SYS.DDF01") + "00"));
        check("PPSE SW=9000", sw(r).equals("9000"));
        String ppse = hex(noSw(r));
        check("FCI تبدأ بـ 6F", ppse.startsWith("6F"));
        check("يحتوي mada AID", ppse.contains("4F07A0000002281010"));
        check("يحتوي Visa AID", ppse.contains("4F07A0000000031010"));
        check("يحتوي MC AID", ppse.contains("4F07A0000000041010"));
        check("kernel id مada=02", ppse.contains("9F2A0102"));
        check("kernel id Visa=03", ppse.contains("9F2A0103"));
        System.out.println("PPSE FCI: " + pretty(ppse));

        log("=== [2] SELECT mada AID ===");
        r = card.process(unhex("00A4040007A000000228101000"));
        check("mada SW=9000", sw(r).equals("9000"));
        String madaFci = hex(noSw(r));
        check("mada FCI فيها PDOL 9F38", madaFci.contains("9F381B"));
        check("mada FCI فيها TTQ 9F66 في PDOL", madaFci.contains("9F6604"));
        System.out.println("mada SELECT FCI: " + pretty(madaFci));

        log("=== [3] GPO بعد اختيار mada (بيانات PDOL: TTQ+amount) ===");
        r = card.process(unhex("80A800000C830A26800000000000000100000"));
        check("GPO SW=9000", sw(r).equals("9000"));
        String gpo = hex(noSw(r));
        check("GPO ردها قالب 77", gpo.startsWith("77"));
        check("GPO فيها AIP (82)", gpo.contains("82023000"));
        check("GPO فيها AFL (94)", gpo.contains("9408"));
        check("AFL = SFI1 rec1 + SFI2 rec2", gpo.contains("94080801010010020200"));
        System.out.println("GPO response: " + pretty(gpo));

        log("=== [4] READ RECORD SFI1 rec1 (00 B2 01 0C 00) ===");
        r = card.process(unhex("00B2010C00"));
        check("rec1 SW=9000", sw(r).equals("9000"));
        String rec1 = hex(noSw(r));
        check("rec1 قالب 70", rec1.startsWith("70"));
        check("rec1 فيها Track2 (57)", rec1.contains("57"));
        check("rec1 فيها PAN (5A)", rec1.contains("5A084000001234567899"));
        check("rec1 فيها انتهاء 5F24", rec1.contains("5F2403" + "2612" + "31"));
        check("rec1 فيها CTQ 9F6C للنواة 2", rec1.contains("9F6C020001"));
        check("Track2 = PAN D YYMM 201", rec1.contains("57104000001234567899D2612201"));
        check("rec1 فيها اسم الحامل 5F20", rec1.contains("5F20") && hex(card.ascii("SAD AM")).length() > 0);
        check("rec1 فيها عملة SAR 9F42=0682", rec1.contains("9F42020682"));
        System.out.println("rec1: " + pretty(rec1));
        System.out.println("rec1 RAW: " + rec1);

        log("=== [5] READ RECORD SFI2 rec2 (00 B2 02 14 00) ===");
        r = card.process(unhex("00B2021400"));
        check("rec2 SW=9000", sw(r).equals("9000"));
        String rec2 = hex(noSw(r));
        check("rec2 فيها CDOL1 (8C) بطول 15", rec2.contains("8C159F0206"));
        check("rec2 فيها CDOL2 (8D)", rec2.contains("8D159F0206"));
        check("rec2 فيها IACs", rec2.contains("9F0D04") && rec2.contains("9F0E04") && rec2.contains("9F0F04"));
        System.out.println("rec2: " + pretty(rec2));

        log("=== [6] READ RECORD خارج AFL → 6A83 ===");
        r = card.process(unhex("00B2031400"));
        check("rec3 = 6A83", sw(r).equals("6A83"));
        r = card.process(unhex("00B2012400"));
        check("SFI2 rec1 = 6A83", sw(r).equals("6A83"));

        log("=== [7] GENERATE AC أول — CDOL1 حقيقي (29 بايت) ===");
        // 9F02=000000010000 (25.00 SAR) 9F03=0 9F1A=0682 95=0 5F2A=0682 9A=261026 9C=00 9F37=12345678
        String cdol1 = "000000010000" + "000000000000" + "0682" + "0000000000" + "0682" + "261026" + "00" + "12345678";
        r = card.process(unhex("80AE8000" + String.format("%02X", cdol1.length() / 2) + cdol1 + "00"));
        check("GEN AC SW=9000", sw(r).equals("9000"));
        String ac1 = hex(noSw(r));
        check("رد GEN AC قالب 77", ac1.startsWith("77"));
        check("CID = 80 (ARQC)", ac1.contains("9F270180"));
        check("ATC (9F36) موجود", ac1.contains("9F3602"));
        int atcAfter1 = card.getAtc();
        check("ATC زاد إلى 2", atcAfter1 == 2);
        String arqc = tlvValue(ac1, "9F26");
        check("ARQC طوله 8 بايت", arqc.length() == 16);
        System.out.println("GEN AC1: " + pretty(ac1) + "  ATC=" + atcAfter1);
        String arqc1 = arqc;

        log("=== [8] GET DATA ATC (80 CA 9F36 00) ===");
        r = card.process(unhex("80CA9F3600"));
        check("GET DATA 9F36 SW=9000", sw(r).equals("9000"));
        check("GET DATA قيمة ATC صحيحة", hex(noSw(r)).equals("9F36020002"));

        log("=== [9] EXTERNAL AUTHENTICATE (Issuer ARPC) ===");
        r = card.process(unhex("00820000081122334455667788"));
        check("EXT AUTH SW=9000", sw(r).equals("9000"));

        log("=== [10] GENERATE AC ثانٍ — طلب TC ===");
        r = card.process(unhex("80AE4000" + String.format("%02X", cdol1.length() / 2) + cdol1 + "00"));
        check("GEN AC2 SW=9000", sw(r).equals("9000"));
        String ac2 = hex(noSw(r));
        check("CID = 40 (TC) بعد مصادقة المصدر", ac2.contains("9F270140"));
        check("ATC زاد مرة أخرى", card.getAtc() == 3);
        String arqc2 = tlvValue(ac2, "9F26");
        check("الـ TC يختلف عن ARQC (ATC مختلف)", !arqc2.equals(arqc1));
        System.out.println("GEN AC2: " + pretty(ac2));

        log("=== [11] جلسة جديدة عبر PPSE: Visa flow ===");
        card.onDeactivated();
        check("بعد الابتعاد: kernel=0", card.getSelectedKernel() == 0);
        r = card.process(unhex("80A800000C830A26800000000000000100000"));
        check("GPO بدون اختيار AID = 6985", sw(r).equals("6985"));
        r = card.process(unhex("00A4040007A000000003101000"));
        check("SELECT Visa SW=9000", sw(r).equals("9000"));
        r = card.process(unhex("80A800000C830A26800000000000000100000"));
        check("Visa GPO 77", hex(noSw(r)).startsWith("77"));
        r = card.process(unhex("00B2010C00"));
        String vrec1 = hex(noSw(r));
        check("سجل Visa بلا 9F6C (نواة 3)", !vrec1.contains("9F6C"));
        check("سجل Visa فيه 5A", vrec1.contains("5A084000001234567899"));
        r = card.process(unhex("80AE8000" + String.format("%02X", cdol1.length() / 2) + cdol1 + "00"));
        check("Visa GEN AC = ARQC", hex(noSw(r)).contains("9F270180"));
        check("Visa AC فيه IAD واضح FLOSS83", hex(noSw(r)).contains("9F100801") && hex(noSw(r)).contains(hex(card.ascii("FLOSS83"))));
        System.out.println("Visa GEN AC: " + pretty(hex(noSw(r))));
        // ATC يستمر بالزيادة عبر الجلسات كبطاقة حقيقية
        check("ATC تراكمي عبر الجلسات", card.getAtc() == 4);

        log("=== [12] عقد Floss83 المغلق ما زال يعمل (وضع قارئ FlossPay) ===");
        r = card.process(unhex("00A4040008F794F8300000000100"));
        check("SELECT Floss83 SW=9000", sw(r).equals("9000"));
        check("kernel=-1 (وضع FlossPay)", card.getSelectedKernel() == -1);
        r = card.process(unhex("00B2010C00"));
        check("READ RECORD في وضع Floss83 = 6D00 أو رفض", !sw(r).equals("9000"));

        log("=== [13] حالات الخطأ ===");
        r = card.process(unhex("00A4040007A000000099999900"));
        check("AID مجهول = 6A82", sw(r).equals("6A82"));
        r = card.process(unhex("80CA9F1700"));
        check("GET DATA غير مدعوم = 6A88", sw(r).equals("6A88"));
        r = card.process(unhex("00B2010100"));
        check("READ RECORD بـ P2 خاطئ = 6A86", sw(r).equals("6A86"));

        log("=== [14] حتمية الـ ARQC (نفس المدخلات → نفس الناتج لبطاقة نفسها) ===");
        byte[] cg1 = card.cryptogram(unhex(cdol1), 5, 0x80);
        byte[] cg2 = card.cryptogram(unhex(cdol1), 5, 0x80);
        check("MAC حتمي", Arrays.equals(cg1, cg2));
        byte[] cg3 = card.cryptogram(unhex(cdol1.replace("12345678", "87654321")), 5, 0x80);
        check("UN مختلف → MAC مختلف", !Arrays.equals(cg1, cg3));
        EmvCard other = new EmvCard("4651542045869133", "04/29", "ABDULLAH");
        byte[] cg4 = other.cryptogram(unhex(cdol1), 5, 0x80);
        check("PAN مختلف → مفتاح مختلف → MAC مختلف", !Arrays.equals(cg1, cg4));

        log("=== [15] بوت تشخيص POS (build-9): التسجيل + التحليل ===");
        // بطاقة جديدة مع مسجّل — نموذج جلسة POS mada كاملة (لمسة على جهاز إنتاجي)
        EmvCard diagCard = new EmvCard("4000001234567899", "12/26", "SAD AM");
        final java.util.List<EmvDiag.Entry> log1 = new java.util.ArrayList<EmvDiag.Entry>();
        diagCard.setListener(new EmvCard.Listener() {
            @Override public void onExchange(byte[] cmd, byte[] resp, long micros) {
                log1.add(new EmvDiag.Entry(hex(cmd), hex(resp), log1.size() * 1.5));
            }
        });
        diagCard.process(unhex("00A404000E" + asciiHex("2PAY.SYS.DDF01") + "00"));
        diagCard.process(unhex("00A4040007A000000228101000"));
        diagCard.process(unhex("80A800000C830A26800000000000000100000"));
        diagCard.process(unhex("00B2010C00"));
        diagCard.process(unhex("00B2021400"));
        diagCard.process(unhex("80AE8000" + String.format("%02X", cdol1.length() / 2) + cdol1 + "00"));
        EmvDiag.Result full = EmvDiag.analyze(log1);
        check("جلسة كاملة: 6 APDU مسجلة", full.apduCount == 6);
        check("جلسة كاملة: المرحلة 5 (وصلت GENERATE AC)", full.stageNum == 5);
        check("جلسة كاملة: aid = mada", full.aid.contains("mada"));
        check("جلسة كاملة: لا أخطاء SW", full.errors == 0);
        check("جلسة كاملة: التشخيص يذكر مستويات الجهاز",
                full.verdict.contains("مسار EMV كامل") && full.verdict.contains("الشبكة"));

        // جلسة مبتورة: توقف بعد قراءة السجلات (النمط الكلاسيكي لخلل برمجي في POS)
        final java.util.List<EmvDiag.Entry> log2 = new java.util.ArrayList<EmvDiag.Entry>();
        EmvCard c2 = new EmvCard("4000001234567899", "12/26", "SAD AM");
        c2.setListener(new EmvCard.Listener() {
            @Override public void onExchange(byte[] cmd, byte[] resp, long micros) {
                log2.add(new EmvDiag.Entry(hex(cmd), hex(resp), log2.size() * 1.5));
            }
        });
        c2.process(unhex("00A404000E" + asciiHex("2PAY.SYS.DDF01") + "00"));
        c2.process(unhex("00A4040007A000000228101000"));
        c2.process(unhex("80A800000C830A26800000000000000100000"));
        c2.process(unhex("00B2010C00"));
        EmvDiag.Result trunc = EmvDiag.analyze(log2);
        check("جلسة مبتورة: المرحلة 4 (توقف قبل GENERATE AC)", trunc.stageNum == 4);
        check("جلسة مبتورة: التشخيص يقول خللًا برمجيًا", trunc.verdict.contains("خلل"));

        // لمسة موت: لا أوامر إطلاقًا (RF/NFC)
        EmvDiag.Result none = EmvDiag.analyze(new java.util.ArrayList<EmvDiag.Entry>());
        check("لا أوامر: المرحلة 0 (RF)", none.stageNum == 0);
        check("لا أوامر: التشخيص يذكر RF", none.verdict.contains("RF"));

        // PPSE فقط ثم لا اختيار AID (جدول AID غير مهيأ)
        final java.util.List<EmvDiag.Entry> log3 = new java.util.ArrayList<EmvDiag.Entry>();
        EmvCard c3 = new EmvCard("4000001234567899", "12/26", "SAD AM");
        c3.setListener(new EmvCard.Listener() {
            @Override public void onExchange(byte[] cmd, byte[] resp, long micros) {
                log3.add(new EmvDiag.Entry(hex(cmd), hex(resp), log3.size() * 1.5));
            }
        });
        c3.process(unhex("00A404000E" + asciiHex("2PAY.SYS.DDF01") + "00"));
        EmvDiag.Result ppseOnly = EmvDiag.analyze(log3);
        check("PPSE فقط: المرحلة 1 (AID غير مهيأ)", ppseOnly.stageNum == 1);
        check("PPSE فقط: التشخيص يذكر الإعداد", ppseOnly.verdict.contains("إعداد"));

        // جلسة قارئ FlossPay (العقد المغلق) تُوسم وليست POS
        final java.util.List<EmvDiag.Entry> log4 = new java.util.ArrayList<EmvDiag.Entry>();
        EmvCard c4 = new EmvCard("4000001234567899", "12/26", "SAD AM");
        c4.setListener(new EmvCard.Listener() {
            @Override public void onExchange(byte[] cmd, byte[] resp, long micros) {
                log4.add(new EmvDiag.Entry(hex(cmd), hex(resp), log4.size() * 1.5));
            }
        });
        c4.process(unhex("00A4040008F794F8300000000100"));
        c4.process(unhex("80C00000"));
        EmvDiag.Result floss = EmvDiag.analyze(log4);
        check("جلسة FlossPay: موسومة flossReader", floss.flossReader);

        System.out.println("\n=================================");
        System.out.println("PASS: " + passed + "  FAIL: " + failed);
        System.out.println(failed == 0 ? "✅ ALL TESTS GREEN — المحرك جاهز" : "❌ فشل " + failed + " اختبار");
        if (failed > 0) System.exit(1);
    }

    // ============ أدوات =
    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ FAIL: " + name); }
    }

    static void log(String s) { System.out.println("\n" + s); }

    static String sw(byte[] r) { return hex(Arrays.copyOfRange(r, r.length - 2, r.length)); }

    static byte[] noSw(byte[] r) { return Arrays.copyOfRange(r, 0, r.length - 2); }

    static String tlvValue(String data, String tag) { return findTlv(unhex(data), tag); }

    static String findTlv(byte[] b, String tag) {
        int i = 0;
        while (i + 2 < b.length) {
            int t1 = b[i] & 0xFF;
            int tagLen = ((t1 & 0x1F) == 0x1F) ? 2 : 1;
            String tagStr = hex(Arrays.copyOfRange(b, i, i + tagLen));
            int len = b[i + tagLen] & 0xFF;
            int valStart = i + tagLen + 1;
            if (tagStr.equals(tag)) return hex(Arrays.copyOfRange(b, valStart, valStart + len));
            if ((t1 & 0x20) != 0) { // قالب مركّب → انزل داخله
                String inner = findTlv(Arrays.copyOfRange(b, valStart, Math.min(valStart + len, b.length)), tag);
                if (!inner.isEmpty()) return inner;
            }
            i = valStart + len;
        }
        return "";
    }

    static String pretty(String data) {
        byte[] b = unhex(data);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < b.length) {
            int t1 = b[i] & 0xFF;
            int tagLen = ((t1 & 0x1F) == 0x1F) ? 2 : 1;
            String tagStr = hex(Arrays.copyOfRange(b, i, i + tagLen));
            int len = b[i + tagLen] & 0xFF;
            sb.append(tagStr).append("(").append(len).append(") ");
            i += tagLen + 1 + len;
        }
        return sb.toString();
    }

    static byte[] unhex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static String asciiHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(String.format("%02X", (int) c));
        return sb.toString();
    }
}
