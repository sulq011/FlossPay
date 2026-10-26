package com.floss83.pay;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * وضع قراءة NFC:
 * - يقرأ بطاقة FlossPay HCE من هاتف آخر (بيانات كاملة + دفع حقيقي عبر AID مخصص).
 * - يقرأ البطاقات المادية (EMV): Visa/MC/Amex — البيانات مقنّعة جزئيًا بحسب حماية EMV.
 */
public class NfcReaderActivity extends Activity implements NfcAdapter.ReaderCallback {

    private NfcAdapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setGravity(Gravity.START);
        status.setTextSize(15);
        ScrollView sc = new ScrollView(this);
        sc.addView(status);
        setContentView(sc);
        setTitle("📡 قراءة NFC");

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC)) {
            log("❌ هذا الجهاز لا يدعم NFC.\n");
            return;
        }
        adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null || !adapter.isEnabled()) {
            log("❌ NFC غير مفعّل. فعّله من إعدادات الجهاز أولًا.\n");
            return;
        }
        log("📖 قرّب أي بطاقة دفع أو هاتفًا يشغّل وضع NFC Pay في FlossPay…\n");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    new Bundle());
        }
    }

    @Override
    protected void onPause() {
        if (adapter != null) adapter.disableReaderMode(this);
        super.onPause();
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            log("\n⏹ التاغ ليس بطاقة ISO-DEP.");
            return;
        }
        try {
            iso.connect();
            iso.setTimeout(10000);
            log("\n🔗 اتصال NFC قائم…");

            // 1) بطاقة FlossPay HCE (هاتف آخر)
            byte[] selResp = iso.transceive(select(EmvCard.AID_FLOSS83));
            if (isOk(selResp)) {
                byte[] getCard = {(byte) 0x80, (byte) 0xC0, 0x00, 0x00, 0x00};
                byte[] resp = iso.transceive(getCard);
                if (isOk(resp)) {
                    String data = new String(resp, 0, resp.length - 2, java.nio.charset.StandardCharsets.US_ASCII);
                    String[] parts = data.split("\\|");
                    log("📱 بطاقة FlossPay (NFC):\n");
                    if (parts.length >= 1) log("   PAN: " + parts[0]);
                    if (parts.length >= 2) log("   تنتهي: " + parts[1]);
                    if (parts.length >= 3) log("   الرصيد: " + Util.formatAmount(Long.parseLong(parts[2])));
                    if (parts.length >= 4) log("   " + ("1".equals(parts[3]) ? "موثّقة ✓" : "غير موثّقة ⚠"));
                    log("\nللدفع عبر NFC: أرسل أمر الدفع من جهاز القارئ (0xD0).");
                }
                return;
            }

            // 2) بطاقة مادية EMV
            log("💳 بطاقة مادية — قراءة EMV (بيانات جزئية بحسب حماية EMV)…");
            try {
                byte[] ppse = iso.transceive(select("2PAY.SYS.DDF01"));
                log("   PPSE: " + Util.hex(ppse));
            } catch (Exception e) {
                log("   PPSE: " + e.getMessage());
            }
            String[] aids = {"A0000000031010", "A0000000041010", "A00000002501", "A000000333010101"};
            boolean found = false;
            for (String aid : aids) {
                try {
                    byte[] r = iso.transceive(select(aid));
                    if (isOk(r)) {
                        found = true;
                        log("   التطبيق (AID): " + aid);
                        byte[] gpo = {(byte) 0x80, (byte) 0xA8, 0x00, 0x00, 0x02, (byte) 0x83, 0x00};
                        try {
                            byte[] gpoResp = iso.transceive(gpo);
                            log("   GPO: " + Util.hex(gpoResp));
                        } catch (Exception e) {
                            log("   GPO: " + e.getMessage());
                        }
                        byte[] rr = {(byte) 0x00, (byte) 0xB2, 0x01, (byte) 0x0C, 0x00};
                        try {
                            byte[] rec = iso.transceive(rr);
                            log("   السجل: " + Util.hex(rec));
                            log("ℹ️ البطاقات الحقيقية تُظهر بيانات مقنّعة/مشفّرة فقط — هذا أساسي في مواصفات EMV وليس خطأ.");
                        } catch (Exception e) {
                            log("   السجل: " + e.getMessage());
                        }
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!found) log("   لم يتم التعرف على تطبيق دفع على البطاقة.");

        } catch (Exception e) {
            log("❌ خطأ في القراءة: " + e.getMessage());
        } finally {
            try {
                iso.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] select(String aidHex) {
        byte[] aid = Util.unhex(aidHex);
        return concat(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, (byte) aid.length},
                aid, new byte[]{0x00});
    }

    private boolean isOk(byte[] resp) {
        return resp != null && resp.length >= 2
                && (resp[resp.length - 2] & 0xFF) == 0x90
                && (resp[resp.length - 1] & 0xFF) == 0x00;
    }

    private byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) bos.write(p, 0, p.length);
        return bos.toByteArray();
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.append(s + "\n");
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
