package com.floss83.pay;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * خدمة محاكاة البطاقة (HCE) — ثلاثة مستويات في خدمة واحدة:
 *
 * 1) بطاقة EMV Contactless حقيقية لأي جهاز POS إنتاجي (build-8):
 *    PPSE (2PAY.SYS.DDF01) → FCI دليل AIDs (mada/Visa/MasterCard + 9F2A)
 *    → SELECT AID (PDOL) → GPO (AIP/AFL) → READ RECORD (Track2/PAN/CDOLs)
 *    → GENERATE AC (ARQC بـ 3DES معمل + ATC + CID) → EXTERNAL AUTH.
 *
 * 2) عقد Floss83 المغلق (قارئ FlossPay على جهاز آخر):
 *    SELECT F794F83000000001 → 9000
 *    GET_CARD (80 C0) → PAN|expiry|balance|verified
 *    PAY (80 D0 + amount) → دفع فعلي عبر السويتش → المُصدر.
 *
 * 3) بوت تشخيص POS (build-9): كل أمر/رد يُسجَّل خلال اللمسة،
 *    وعند انتهائها (onDeactivated) يُحلَّل السجل بـ EmvDiag ويُحفظ في DiagStore
 *    ليراه التاجر في «سجل تشخيص POS»: إلى أي مرحلة وصل الجهاز وأين تعثر.
 */
public class HceService extends HostApduService {

    private EmvCard emv;
    private String emvPan;

    // ===== تسجيل تشخيصي للجلسة الحالية =====
    private List<EmvDiag.Entry> session = new ArrayList<EmvDiag.Entry>();
    private long sessionStartMs = 0;
    private boolean listenerAttached = false;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        CardStore store = new CardStore(this);
        java.util.List<Card> cards = store.load();
        Card card = store.byPan(cards, store.getSelectedPan());
        if (card == null && !cards.isEmpty()) card = cards.get(0);
        String pan = card != null ? card.pan : "4000001234567899";
        String expiry = (card != null && card.expiry != null && !card.expiry.isEmpty()) ? card.expiry : "12/26";
        String holder = (card != null && card.holder != null && !card.holder.isEmpty()) ? card.holder : "SAD AM";

        // محرك EMV واحد يبقى حيًّا طوال تشغيل الخدمة (ATC يتراكم كبطاقة حقيقية)
        if (emv == null || !pan.equals(emvPan)) {
            emv = new EmvCard(pan, expiry, holder);
            emvPan = pan;
        }

        // بوت التشخيص: علّق المسجّل مرة واحدة على المحرك
        if (!listenerAttached) {
            emv.setListener(new EmvCard.Listener() {
                @Override
                public void onExchange(byte[] cmd, byte[] resp, long micros) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (sessionStartMs == 0) sessionStartMs = now;
                    session.add(new EmvDiag.Entry(hex(cmd), hex(resp), now - sessionStartMs));
                }
            });
            listenerAttached = true;
        }

        // مستوى 2: عقد Floss83 المغلق — فقط بعد SELECT لـ AID الخاص بنا
        if (emv.getSelectedKernel() == -1 && apdu != null && apdu.length >= 5
                && (apdu[0] & 0xFF) == 0x80) {
            byte[] legacy = legacyCommand(apdu, card, store, cards);
            if (legacy != null) {
                // سجّل أوامر العقد المغلق أيضًا (تظهر في السجل كجلسة FlossPay)
                long now = android.os.SystemClock.elapsedRealtime();
                if (sessionStartMs == 0) sessionStartMs = now;
                session.add(new EmvDiag.Entry(hex(apdu), hex(legacy), now - sessionStartMs));
                return legacy;
            }
        }

        // مستوى 1: بطاقة EMV كاملة (أو رفضات ISO نظيفة لأي أمر آخر)
        return emv.process(apdu);
    }

    /** عقد FlossPay المغلق: GET_CARD / PAY — نفس سلوك الإصدارات السابقة تمامًا. */
    private byte[] legacyCommand(byte[] apdu, Card card, CardStore store, java.util.List<Card> cards) {
        int ins = apdu[1] & 0xFF;
        if (card == null) return EmvCard.SW_NOT_FOUND;

        if (ins == 0xC0) { // GET_CARD
            String data = card.pan + "|" + card.expiry + "|" + card.balanceMinor
                    + "|" + (card.verified ? "1" : "0");
            return concat(Util.ascii(data), EmvCard.SW_OK);
        }

        if (ins == 0xD0) { // PAY — مبلغ بالوحدات الصغرى في بيانات الأمر
            int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
            String amtStr = Util.asciiStr(Arrays.copyOfRange(apdu, 5,
                    Math.min(5 + lc, apdu.length))).trim();
            try {
                long amount = Long.parseLong(amtStr);
                String hostPort = store.getHost();
                String[] hp = hostPort.split(":");
                String stan = String.format(Locale.US, "%06d",
                        (System.currentTimeMillis() / 1000) % 1000000);
                String msg = Iso8583.build0200(card.pan, card.cvv, amount, stan);
                Iso8583.PayResult r = Iso8583.send(hp[0], Integer.parseInt(hp[1].trim()), msg);
                if (r.approved) {
                    store.adjustBalance(cards, card.pan, -amount);
                }
                String rc = (r.responseCode == null || r.responseCode.isEmpty()) ? "91" : r.responseCode;
                String ac = (r.authCode == null || r.authCode.isEmpty()) ? "000000" : r.authCode;
                return concat(Util.ascii(rc + ac), EmvCard.SW_OK);
            } catch (Exception e) {
                return concat(Util.ascii("91000000"), EmvCard.SW_OK);
            }
        }
        return null; // أمر آخر في وضع Floss83 → مرر للمحرك (سيرفضه 6D00)
    }

    @Override
    public void onDeactivated(int reason) {
        if (emv != null) emv.onDeactivated(); // ينهي الجلسة ويحفظ ATC

        // بوت التشخيص: حلّل الجلسة المنتهية واحفظها، ومع وجود اختبار معلّق
        // (من التطبيق الأساسي FlossDiag) قارن المبلغ وسلّم النتيجة له (build-11)
        if (!session.isEmpty()) {
            List<EmvDiag.Entry> snapshot = session;
            try {
                EmvDiag.Result res = EmvDiag.analyze(snapshot);
                org.json.JSONObject pending = DiagStore.loadPendingTest(this);
                long expected = -1;
                String testId = "";
                if (pending != null) {
                    expected = pending.optLong("amountMinor", -1);
                    testId = pending.optString("testId", "");
                    if (expected >= 0) {
                        res.verdict += "\n" + EmvDiag.amountCheckLine(expected, res.amountMinor);
                    }
                }
                org.json.JSONObject sessionJson = DiagStore.record(this, snapshot, res);
                if (pending != null && sessionJson != null) {
                    sessionJson.put("type", "pos");
                    sessionJson.put("expectedAmount", expected);
                    sessionJson.put("amountMatch", res.amountMinor >= 0 && res.amountMinor == expected);
                    sessionJson.put("testId", testId);
                    DiagStore.clearPendingTest(this);
                    // تسليم النتيجة للتطبيق الأساسي (إن كان مثبتًا)
                    try {
                        android.content.Intent send = new android.content.Intent();
                        send.setClassName("com.floss83.diag", "com.floss83.diag.DiagReceiver");
                        send.putExtra("session", sessionJson.toString());
                        sendBroadcast(send);
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
            session = new ArrayList<EmvDiag.Entry>();
            sessionStartMs = 0;
        }
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return bos.toByteArray();
    }
}
