package com.floss83.pay;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * محرك بطاقة EMV Contactless — جافا نقية (بلا أي Android API) لقابلية الاختبار خارج الجهاز.
 *
 * يجيب على تسلسل POS الإنتاجي الحقيقي:
 *   SELECT PPSE (2PAY.SYS.DDF01) → FCI دليل AIDs (mada/Visa/MasterCard + معرف النواة 9F2A)
 *   SELECT AID → FCI فيها PDOL (9F38)
 *   GPO (80 A8) → 77 { AIP 82, AFL 94 }
 *   READ RECORD (00 B2) → سجلات 70 { 57 Track2, 5A PAN, 5F24 انتهاء, 8C/8D CDOLs, ... }
 *   GENERATE AC (80 AE) → 77 { 9F26 ARQC, 9F27 CID=80, 9F36 ATC, 9F10 IAD }
 *   EXTERNAL AUTHENTICATE (00 82) → 9000
 *
 * حدود صادقة (موثقة عمدًا):
 *   - لا SDA/DDA/CDA: لا توجد مفاتيح مرخّصة من Visa/MC — بتاتها في AIP صفرية،
 *     فتتخطى نواة POS المصادقة خارج الخط وتمشي مباشرة لل online authorization.
 *   - الـ ARQC بصيغة MAC 3DES-CBC (بأسلوب CVN10) لكن بمفتاح معمل مشتق من PAN —
 *     أي مصدر حقيقي في العالم سيرفضه. هذا مقصود: نحن أداة تشخيص POS، لسنا شبكة دفع.
 *   - وضع MSD القديم غير مدعوم (نواة qVSDC/M-Chip فقط).
 */
public class EmvCard {

    // AIDs — mada (SAMA SPAN/M/Chip) ثم Visa ثم MasterCard
    public static final String PPSE = "325041592E5359532E4444463031"; // "2PAY.SYS.DDF01"
    public static final String PSE  = "315041592E5359532E4444463031"; // "1PAY.SYS.DDF01"
    public static final String AID_MADA   = "A0000002281010"; // kernel 2 (M/Chip)
    public static final String AID_VISA   = "A0000000031010"; // kernel 3 (qVSDC)
    public static final String AID_MC     = "A0000000041010"; // kernel 2 (M/Chip)
    public static final String AID_FLOSS83 = "F794F83000000001"; // عقدنا المغلق (وضع قارئ FlossPay)

    // تسجيل تشخيصي: كل أمر ورد يمر من هنا (بوت تشخيص POS)
    public interface Listener { void onExchange(byte[] cmd, byte[] resp, long micros); }
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    // حالة الجلسة
    private int selectedKernel = 0;   // 0=لا شيء، 2=M/Chip(mada/mc)، 3=qVSDC(visa)، -1=floss83
    private int atc = 1;              // عداد المعاملات (يبقى في الذاكرة طوال تشغيل الخدمة)
    private boolean issuerAuthed = false;

    // معطيات البطاقة المعروضة
    private final String pan;      // أرقام فقط
    private final String expiryMMYY;
    private final String holder;
    private final String appCurrency; // BCD مثل "0682" (SAR)
    private final String issuerCountry; // BCD مثل "0682" (SA)

    public EmvCard(String pan, String expiryMMYY, String holder) {
        this(pan, expiryMMYY, holder, "0682", "0682");
    }

    public EmvCard(String pan, String expiryMMYY, String holder, String appCurrency, String issuerCountry) {
        this.pan = (pan == null || pan.length() < 12) ? "4000001234567899" : pan;
        this.expiryMMYY = (expiryMMYY == null || expiryMMYY.length() != 5) ? "12/26" : expiryMMYY;
        this.holder = holder == null ? "SAD AM" : holder;
        this.appCurrency = appCurrency;
        this.issuerCountry = issuerCountry;
    }

    public int getSelectedKernel() { return selectedKernel; }
    public int getAtc() { return atc; }

    /** نهاية الجلسة (ابتعد عن القارئ) — يبقى ATC محفوظًا كبطاقة حقيقية. */
    public void onDeactivated() { selectedKernel = 0; issuerAuthed = false; }

    // ============ نقطة الدخول الرئيسية (مع التسجيل التشخيصي) ============
    public byte[] process(byte[] apdu) {
        long t0 = System.nanoTime();
        byte[] resp = processInner(apdu);
        if (listener != null) {
            try { listener.onExchange(apdu, resp, (System.nanoTime() - t0) / 1000); } catch (Exception ignore) {}
        }
        return resp;
    }

    private byte[] processInner(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return SW_INS_NOT_SUPPORTED;
        int cla = apdu[0] & 0xFF;
        int ins = apdu[1] & 0xFF;
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;

        // SELECT by DF name
        if (ins == 0xA4 && (cla == 0x00 || cla == 0x0C)) return select(apdu);
        // GPO
        if (cla == 0x80 && ins == 0xA8) return gpo();
        // READ RECORD
        if (cla == 0x00 && ins == 0xB2) return readRecord(p1, p2);
        // GENERATE AC
        if (cla == 0x80 && ins == 0xAE) return generateAc(p1, dataOf(apdu));
        // EXTERNAL AUTHENTICATE (Issuer Authentication)
        if (cla == 0x00 && ins == 0x82) {
            issuerAuthed = true;
            return SW_OK;
        }
        // GET DATA
        if (ins == 0xCA && (cla == 0x80 || cla == 0x00)) return getData(p1, p2);
        return SW_INS_NOT_SUPPORTED;
    }

    // ============ SELECT ============
    private byte[] select(byte[] apdu) {
        int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
        if (apdu.length < 5 + lc || lc == 0) return SW_NOT_FOUND;
        String name = hex(Arrays.copyOfRange(apdu, 5, 5 + lc));

        if (PPSE.equals(name) || PSE.equals(name)) {
            return concat(ppseFci(name), SW_OK);
        }
        if (AID_MADA.equals(name)) { selectedKernel = 2; return concat(selectFci(AID_MADA, "mada"), SW_OK); }
        if (AID_VISA.equals(name)) { selectedKernel = 3; return concat(selectFci(AID_VISA, "VISA"), SW_OK); }
        if (AID_MC.equals(name))   { selectedKernel = 2; return concat(selectFci(AID_MC, "MasterCard"), SW_OK); }
        if (AID_FLOSS83.equals(name)) { selectedKernel = -1; return concat(selectFci(AID_FLOSS83, "FlossPay"), SW_OK); }
        return SW_NOT_FOUND;
    }

    /** FCI دليل PPSE: 6F { 84 DF name, A5 { BF0C { 61{4F,50,87,9F2A} ×3 } } } */
    private byte[] ppseFci(String dfName) {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        write(entries, entry(AID_MADA, "mada", 1, "02"));
        write(entries, entry(AID_VISA, "VISA", 2, "03"));
        write(entries, entry(AID_MC, "MasterCard", 3, "02"));
        byte[] bf0c = tlv("BF0C", entries.toByteArray());
        byte[] a5 = tlv("A5", bf0c);
        ByteArrayOutputStream fci = new ByteArrayOutputStream();
        write(fci, tlv("84", unhex(dfName)));
        write(fci, a5);
        return tlv("6F", fci.toByteArray());
    }

    /** مدخل دليل واحد: 61 { 4F AID, 50 label, 87 priority, 9F2A kernel id } */
    private byte[] entry(String aid, String label, int prio, String kernelId) {
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        write(e, tlv("4F", unhex(aid)));
        write(e, tlv("50", ascii(label)));
        write(e, tlv("87", new byte[]{(byte) prio}));
        write(e, tlv("9F2A", unhex(kernelId)));
        return tlv("61", e.toByteArray());
    }

    /** FCI اختيار التطبيق: 6F { 84 AID, A5 { 50, 87, 9F38 PDOL, 5F2D } } */
    private byte[] selectFci(String aid, String label) {
        // PDOL: 9F66 TTQ, 9F02 amount, 9F03 cashback, 9F1A term country, 95 TVR,
        //       5F2A currency, 9A date, 9C type, 9F37 UN, 9F35 terminal type
        byte[] pdol = unhex("9F6604" + "9F0206" + "9F0306" + "9F1A02" + "9505" +
                "5F2A02" + "9A03" + "9C01" + "9F3704" + "9F3501");
        ByteArrayOutputStream a5 = new ByteArrayOutputStream();
        write(a5, tlv("50", ascii(label)));
        write(a5, tlv("87", new byte[]{0x00}));
        write(a5, tlv("9F38", pdol));
        write(a5, tlv("5F2D", ascii("ar")));
        ByteArrayOutputStream fci = new ByteArrayOutputStream();
        write(fci, tlv("84", unhex(aid)));
        write(fci, tlv("A5", a5.toByteArray()));
        return tlv("6F", fci.toByteArray());
    }

    // ============ GPO ============
    /** رد GPO (وضع EMV format 2): 77 { 82 AIP, 94 AFL } */
    private byte[] gpo() {
        if (selectedKernel != 2 && selectedKernel != 3) return SW_COND_NOT_SATISFIED;
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        // AIP: 30 00 = دعم CVM + إدارة مخاطر الطرف؛ SDA/DDA/CDA = صفر (مقصد تشخيصي)
        write(t, tlv("82", unhex("3000")));
        // AFL: SFI1 سجل1 (بيانات التطبيق) + SFI2 سجل2 (CDOLs) — لا سجلات ODA
        write(t, tlv("94", unhex("08010100" + "10020200")));
        return concat(tlv("77", t.toByteArray()), SW_OK);
    }

    // ============ READ RECORD ============
    private byte[] readRecord(int record, int p2) {
        if ((p2 & 0x07) != 0x04) return SW_WRONG_P1P2;
        if (selectedKernel != 2 && selectedKernel != 3) return SW_COND_NOT_SATISFIED;
        int sfi = (p2 >> 3) & 0x1F;
        byte[] rec;
        if (sfi == 1 && record == 1) rec = record1();
        else if (sfi == 2 && record == 2) rec = record2();
        else return SW_REC_NOT_FOUND;
        return concat(tlv("70", rec), SW_OK);
    }

    /** SFI1/سجل1 — بيانات التطبيق الأساسية */
    private byte[] record1() {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        // CTQ للبطاقة (نواة M/Chip تقرأها من السجل الأول) — القيمة من تتبع حقيقي موثق
        if (selectedKernel == 2) write(r, tlv("9F6C", unhex("0001")));
        // Track2: PAN D YYMM 201 [discretionary] F-pad
        write(r, tlv("57", packNibbles(track2String())));
        // PAN (5A) — BCD مع حشوة F للأطوال الفردية
        write(r, tlv("5A", packNibbles(pan + (pan.length() % 2 == 1 ? "F" : ""))));
        // انتهاء الصلاحية (5F24) = آخر يوم من الشهر
        write(r, tlv("5F24", unhex(expiryYYMM() + "31")));
        write(r, tlv("5F25", unhex("240101")));      // تاريخ بداية الصلاحية
        write(r, tlv("5F34", new byte[]{0x00}));     // تسلسل PAN
        write(r, tlv("5F28", unhex(issuerCountry))); // بلد المصدر (SA)
        write(r, tlv("9F42", unhex(appCurrency)));   // عملة التطبيق (SAR)
        write(r, tlv("9F44", new byte[]{0x02}));     // أس العملة
        write(r, tlv("9F07", unhex("F8F8")));        // صلاحيات الاستخدام: الكل
        write(r, tlv("50", ascii(selectedKernel == 3 ? "VISA" : (selectedKernel == 2 ? "mada/MC" : "CARD"))));
        write(r, tlv("5F20", ascii(holder)));         // اسم حامل البطاقة (يظهر على شاشة POS أحيانًا)
        write(r, tlv("5F2D", ascii("ar")));          // اللغة المفضلة
        return r.toByteArray();
    }

    /** SFI2/سجل2 — CDOLs ورموز إجراء المصدر */
    private byte[] record2() {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        // CDOL1/CDOL2 — نفس مجموعة CDOL1 الموثقة من تتبع حقيقي (طولها 1D)
        String cdol = "9F0206" + "9F0306" + "9F1A02" + "9505" + "5F2A02" + "9A03" + "9C01" + "9F3704";
        write(r, tlv("8C", unhex(cdol)));
        write(r, tlv("8D", unhex(cdol)));
        // IACs صفرية: لا نطلب رفضًا آليًا — دع المعاملة تصل للاعتماد الأونلاين (قيمة تشخيصية)
        write(r, tlv("9F0D", new byte[4])); // IAC default
        write(r, tlv("9F0E", new byte[4])); // IAC denial
        write(r, tlv("9F0F", new byte[4])); // IAC online
        return r.toByteArray();
    }

    // ============ GENERATE AC ============
    /**
     * P1 bits 6-7: 00=AAC, 01=TC, 10=ARQC.
     * أول GENERATE AC نرجع دائمًا ARQC (بطاقة أونلاين-فقط — ندفع POS نحو المصدر/الشبكة،
     * وهذا هو مسار الرفض الحقيقي الذي نريد تشخيصه).
     * بعد EXTERNAL AUTHENTICATE نطيع نوع AC المطلوب في الثاني.
     */
    private byte[] generateAc(int p1, byte[] cdolData) {
        if (selectedKernel != 2 && selectedKernel != 3) return SW_COND_NOT_SATISFIED;
        int requested = (p1 >> 6) & 0x03;
        int cid;
        if (!issuerAuthed) {
            cid = 0x80; // ARQC دائمًا في الأول
        } else {
            cid = requested == 0x01 ? 0x40 : (requested == 0x02 ? 0x80 : 0x00); // الثاني: كما طلب
        }
        atc++;
        byte[] ac = cryptogram(cdolData, atc, cid);
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        write(t, tlv("9F26", ac));
        write(t, tlv("9F27", new byte[]{(byte) cid}));
        write(t, tlv("9F36", bcd2(atc)));
        write(t, tlv("9F10", concat(new byte[]{0x01}, ascii("FLOSS83")))); // IAD معمل واضح
        return concat(tlv("77", t.toByteArray()), SW_OK);
    }

    // ============ GET DATA ============
    private byte[] getData(int p1, int p2) {
        int tag = ((p1 & 0xFF) << 8) | (p2 & 0xFF);
        if (tag == 0x9F36) return concat(tlv("9F36", bcd2(atc)), SW_OK);
        return SW_FUNC_NOT_SUPPORTED; // 6A88
    }

    // ============ ARQC — MAC 3DES-CBC أسلوب CVN10 بمفتاح معمل ============
    /**
     * المدخلات: بيانات CDOL1 كما أرسلها POS + ATC + تسلسل PAN + AIP + CID،
     * حشوة 0x80، ثم 3DES-CBC بمتجه ابتدائي صفري. المفتاح = SHA-256("FLOSS83EMVLAB|PAN") أول 24 بايت.
     * هذه بضاعة معمل: لا يمكن لأي مصدر حقيقي التحقق منها (ولا نملك هذا الحق أصلًا).
     */
    byte[] cryptogram(byte[] cdolData, int atcVal, int cid) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (cdolData != null) write(bos, cdolData);
            write(bos, bcd2(atcVal));
            bos.write(0x00);            // 5F34 PAN seq
            bos.write(0x30); bos.write(0x00); // 82 AIP
            bos.write(cid & 0xFF);      // 9F27 CID
            byte[] input = pad80(bos.toByteArray());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(ascii("FLOSS83EMVLAB|" + pan));
            byte[] key24 = Arrays.copyOf(digest, 24); // 3DES يتطلب 24 بايت (SunJCE/Android)
            Cipher c = Cipher.getInstance("DESede/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key24, "DESede"), new IvParameterSpec(new byte[8]));
            byte[] full = c.doFinal(input);
            return Arrays.copyOfRange(full, full.length - 8, full.length); // آخر 8 بايت = MAC
        } catch (Exception e) {
            return new byte[8]; // لا يجب أن يحدث أبدًا
        }
    }

    private static byte[] pad80(byte[] in) {
        int len = Math.max(16, ((in.length / 16) + 1) * 16);
        byte[] out = Arrays.copyOf(in, len);
        out[in.length] = (byte) 0x80;
        return out; // الباقي أصفار (Arrays.copyOf يحشو صفرًا)
    }

    // ============ أدوات مساعدة ============
    private String track2String() {
        // PAN + D + YYMM + service code 201 + discretionary + F-pad
        return pan + "D" + expiryYYMM() + "201" + "0000000";
    }

    private String expiryYYMM() {
        // "12/26" → "2612"
        return expiryMMYY.length() == 5 ? expiryMMYY.substring(3, 5) + expiryMMYY.substring(0, 2) : "2612";
    }

    private static byte[] bcd2(int v) {
        return new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    private static byte[] packNibbles(String s) {
        if (s.length() % 2 == 1) s = s + "F";
        return unhex(s);
    }

    private static byte[] dataOf(byte[] apdu) {
        if (apdu.length <= 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        return Arrays.copyOfRange(apdu, 5, Math.min(5 + lc, apdu.length));
    }

    // بناء TLV (كل القيم لدينا < 127 بايت → طول ببايت واحد)
    static byte[] tlv(String tagHex, byte[] value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        write(bos, unhex(tagHex));
        bos.write(value.length & 0xFF);
        write(bos, value);
        return bos.toByteArray();
    }

    static void write(ByteArrayOutputStream bos, byte[] b) { bos.write(b, 0, b.length); }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) write(bos, p);
        return bos.toByteArray();
    }

    public static byte[] unhex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static byte[] ascii(String s) { return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }

    // كلمات الحالة
    static final byte[] SW_OK = {(byte) 0x90, 0x00};
    static final byte[] SW_NOT_FOUND = {(byte) 0x6A, (byte) 0x82};
    static final byte[] SW_INS_NOT_SUPPORTED = {(byte) 0x6D, 0x00};
    static final byte[] SW_REC_NOT_FOUND = {(byte) 0x6A, (byte) 0x83};
    static final byte[] SW_WRONG_P1P2 = {(byte) 0x6A, (byte) 0x86};
    static final byte[] SW_COND_NOT_SATISFIED = {(byte) 0x69, (byte) 0x85};
    static final byte[] SW_FUNC_NOT_SUPPORTED = {(byte) 0x6A, (byte) 0x88};
}
