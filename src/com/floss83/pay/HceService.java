package com.floss83.pay;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * خدمة محاكاة البطاقة (HCE): تحوّل الهاتف إلى بطاقة NFC.
 * - جهاز آخر يشغّل وضع القراءة في FlossPay يقرأها بالكامل.
 * - أمر الدفع (0xD0) يمرّ فعليًا عبر السويتش ثم المُصدر — قرار حقيقي.
 *
 * عقد APDU المغلق (AID: F794F83000000001):
 * - SELECT (00 A4 04 00) + AID            -> 9000
 * - GET_CARD (80 C0 00 00 00)             -> PAN|expiry|balance|verified + 9000
 * - PAY (80 D0 00 00 LC + amount digits) -> responseCode+authCode + 9000
 */
public class HceService extends HostApduService {

    public static final String AID = "F794F83000000001";

    private static final byte[] SW_OK = {(byte) 0x90, 0x00};
    private static final byte[] SW_NOT_FOUND = {(byte) 0x6A, (byte) 0x82};
    private static final byte[] SW_INS_NOT_SUPPORTED = {(byte) 0x6D, 0x00};

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return SW_INS_NOT_SUPPORTED;

        int cla = apdu[0] & 0xFF;
        int ins = apdu[1] & 0xFF;

        if (cla == 0x00 && ins == 0xA4) { // SELECT
            if (apdu.length >= 5) {
                int lc = apdu[4] & 0xFF;
                if (apdu.length >= 5 + lc) {
                    String aid = Util.hex(Arrays.copyOfRange(apdu, 5, 5 + lc));
                    if (AID.equals(aid)) {
                        return SW_OK;
                    }
                }
            }
            return SW_NOT_FOUND;
        }

        if (cla == 0x80) {
            CardStore store = new CardStore(this);
            java.util.List<Card> cards = store.load();
            String selected = store.getSelectedPan();
            Card card = store.byPan(cards, selected);

            if (ins == 0xC0) { // GET_CARD
                if (card == null) return SW_NOT_FOUND;
                String data = card.pan + "|" + card.expiry + "|" + card.balanceMinor
                        + "|" + (card.verified ? "1" : "0");
                return concat(Util.ascii(data), SW_OK);
            }

            if (ins == 0xD0) { // PAY — مبلغ بالوحدات الصغرى في بيانات الأمر
                if (card == null) return SW_NOT_FOUND;
                int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
                String amtStr = Util.asciiStr(Arrays.copyOfRange(apdu, 5, Math.min(5 + lc, apdu.length))).trim();
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
                    return concat(Util.ascii(rc + ac), SW_OK);
                } catch (Exception e) {
                    return concat(Util.ascii("91000000"), SW_OK);
                }
            }
        }

        return SW_INS_NOT_SUPPORTED;
    }

    @Override
    public void onDeactivated(int reason) {
        // البطاقة ابتعدت عن القارئ
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return bos.toByteArray();
    }
}
