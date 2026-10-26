package com.floss83.pay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * FlossPay — محفظة معمل Flossx83:
 * - بطاقات متعددة (موثّقة = معروفة لدى المُصدر + Luhn) بأرصدة محلية.
 * - الدفع: رسالة ISO 8583 0200 حقيقية عبر السويتش (تأطير 2 بايت) ثم رد 0210 بـ F38/F39.
 * - NFC: قراءة البطاقات + محاكاة بطاقة (HCE) لجهاز آخر.
 */
public class MainActivity extends Activity {

    private CardStore store;
    private List<Card> cards;
    private LinearLayout cardsBox;
    private TextView logView;
    private EditText amountEdit;
    private EditText hostEdit;
    private Button payButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new CardStore(this);
        cards = store.load();
        if (store.getSelectedPan() == null && !cards.isEmpty()) {
            store.setSelectedPan(cards.get(0).pan);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(box);
        setContentView(scroll);
        setTitle("FlossPay — محفظة المعمل");

        TextView title = new TextView(this);
        title.setText("💳 FlossPay");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(4));
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("بطاقات موثّقة بأرصدة — دفع ISO 8583 حقيقي عبر السويتش");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(12));
        box.addView(sub);

        hostEdit = new EditText(this);
        hostEdit.setHint("عنوان السويتش (host:port)");
        hostEdit.setText(store.getHost());
        hostEdit.setTextSize(14);
        box.addView(hostEdit);

        TextView cardsTitle = new TextView(this);
        cardsTitle.setText("البطاقات");
        cardsTitle.setTextSize(18);
        cardsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardsTitle.setPadding(0, dp(16), 0, dp(8));
        box.addView(cardsTitle);

        cardsBox = new LinearLayout(this);
        cardsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(cardsBox);

        Button addCard = new Button(this);
        addCard.setText("＋ إضافة بطاقة");
        addCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCardDialog();
            }
        });
        box.addView(addCard);

        TextView payTitle = new TextView(this);
        payTitle.setText("الدفع");
        payTitle.setTextSize(18);
        payTitle.setTypeface(Typeface.DEFAULT_BOLD);
        payTitle.setPadding(0, dp(20), 0, dp(8));
        box.addView(payTitle);

        amountEdit = new EditText(this);
        amountEdit.setHint("المبلغ (مثال: 25.50)");
        amountEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(amountEdit);

        payButton = new Button(this);
        payButton.setText("💳 دفع الآن");
        payButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pay();
            }
        });
        box.addView(payButton);

        Button nfcRead = new Button(this);
        nfcRead.setText("📡 قراءة NFC (بطاقة أو هاتف)");
        nfcRead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NfcReaderActivity.class));
            }
        });
        box.addView(nfcRead);

        Button hce = new Button(this);
        hce.setText("⚙️ تفعيل NFC Pay (هذا الهاتف = بطاقة)");
        hce.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this,
                        "من الإعدادات: فعّل NFC ثم اختر Tap & pay ← FlossPay، وحدد البطاقة المختارة هنا",
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "افتح إعدادات NFC يدويًا", Toast.LENGTH_SHORT).show();
                }
            }
        });
        box.addView(hce);

        Button diag = new Button(this);
        diag.setText("🩺 سجل تشخيص POS (البوت)");
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });
        box.addView(diag);

        Button autofill = new Button(this);
        autofill.setText("💳 تفعيل ملء البطاقات التلقائي (صفحات الدفع)");
        autofill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableAutofill();
            }
        });
        box.addView(autofill);

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setPadding(0, dp(16), 0, 0);
        logView.setTypeface(Typeface.MONOSPACE);
        box.addView(logView);

        renderCards();
        log("✅ المحفظة جاهزة. البطاقة المختارة للدفع وللـ NFC: "
                + Util.mask(store.getSelectedPan() == null ? "" : store.getSelectedPan()));
        log("ℹ️ العنوان الافتراضي: سويتش الخادم sull.world:5000 (متطلب منفذ 5000 مفتوح).");
    }

    private void renderCards() {
        cardsBox.removeAllViews();
        String selected = store.getSelectedPan();
        // تجميع البطاقات حسب الشبكة: Visa / MasterCard / أمريكان إكسبريس / الشبكات الأخرى (build-11)
        int[] groups = {CardProfile.GROUP_VISA, CardProfile.GROUP_MASTERCARD,
                CardProfile.GROUP_AMEX, CardProfile.GROUP_OTHER};
        for (int g : groups) {
            java.util.ArrayList<Card> gc = new java.util.ArrayList<Card>();
            for (Card c : cards) if (CardProfile.group(c.pan) == g) gc.add(c);
            if (gc.isEmpty()) continue;
            TextView header = new TextView(this);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextSize(15);
            header.setPadding(dp(8), dp(10), dp(8), dp(6));
            header.setBackgroundColor(Color.rgb(225, 230, 240));
            header.setText("💳 " + CardProfile.groupName(g) + "  (" + gc.size() + ")");
            cardsBox.addView(header);
            for (final Card c : gc) {
                renderCardRow(selected, c);
            }
        }
    }

    private void renderCardRow(String selected, final Card c) {
        {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(selected != null && selected.equals(c.pan)
                    ? Color.rgb(225, 240, 255) : Color.rgb(245, 245, 245));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(lp);

            TextView head = new TextView(this);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextSize(15);
            head.setText(CardProfile.detect(c.pan) + " • " + (c.holder == null || c.holder.isEmpty() ? "بدون اسم" : c.holder)
                    + (selected != null && selected.equals(c.pan) ? "   ★" : ""));
            row.addView(head);

            TextView pan = new TextView(this);
            pan.setText(Util.mask(c.pan) + "   تنتهي " + c.expiry);
            pan.setTextSize(13);
            row.addView(pan);

            TextView bal = new TextView(this);
            bal.setTextSize(13);
            bal.setTypeface(Typeface.DEFAULT_BOLD);
            bal.setText("الرصيد: " + Util.formatAmount(c.balanceMinor)
                    + (c.verified ? "   ✓ موثّقة" : "   ⚠ غير موثّقة"));
            bal.setTextColor(c.verified ? Color.rgb(20, 130, 60) : Color.rgb(190, 120, 0));
            row.addView(bal);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    store.setSelectedPan(c.pan);
                    renderCards();
                    log("★ البطاقة المختارة: " + Util.mask(c.pan));
                }
            });

            cardsBox.addView(row);
        }
    }

    private void pay() {
        final Card card = store.byPan(cards, store.getSelectedPan());
        if (card == null) {
            Toast.makeText(this, "اختر بطاقة أولًا (المس أي بطاقة)", Toast.LENGTH_SHORT).show();
            return;
        }
        final long minor;
        try {
            minor = parseAmountMinor(amountEdit.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, "أدخل مبلغًا صحيحًا مثل 25.50", Toast.LENGTH_SHORT).show();
            return;
        }
        if (minor <= 0) {
            Toast.makeText(this, "أدخل مبلغًا صحيحًا مثل 25.50", Toast.LENGTH_SHORT).show();
            return;
        }

        final String hostPort = hostEdit.getText().toString().trim();
        store.setHost(hostPort);
        final String[] hp = hostPort.split(":");
        if (hp.length != 2) {
            Toast.makeText(this, "العنوان بصيغة host:port", Toast.LENGTH_SHORT).show();
            return;
        }

        payButton.setEnabled(false);
        log("⏳ إرسال 0200 إلى " + hostPort + " — بطاقة " + Util.mask(card.pan)
                + " — مبلغ " + Util.formatAmount(minor) + " …");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String stan = String.format(Locale.US, "%06d",
                            (System.currentTimeMillis() / 1000) % 1000000);
                    String msg = Iso8583.build0200(card.pan, card.cvv, minor, stan);
                    final Iso8583.PayResult r = Iso8583.send(hp[0], Integer.parseInt(hp[1].trim()), msg);
                    if (r.approved) {
                        store.markKnown(card.pan);
                        store.adjustBalance(cards, card.pan, -minor);
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            cards = store.load();
                            renderCards();
                            log("📨 رد: MTI " + r.mti
                                    + " | F39=" + r.responseCode + " (" + Iso8583.describe(r.responseCode) + ")"
                                    + " | F38=" + r.authCode
                                    + " | STAN " + r.stan);
                            log("   الرسالة: " + r.raw);
                            payButton.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            log("❌ فشل: " + e.getMessage());
                            log("   تأكد أن السويتش يعمل وأن العنوان صحيح (host:port)");
                            payButton.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /** تفعيل خدمة الملء التلقائي — تظهر البطاقات كاقتراحات عند الضغط على حقل البطاقة في أي تطبيق. */
    private void enableAutofill() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "الملء التلقائي يحتاج أندرويد 8+", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            i.setData(Uri.fromParts("android.component",
                    new ComponentName(this, FlossAutofillService.class).flattenToString(), null));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,
                    "من الإعدادات: الإكمال التلقائي ← خدمة الملء ← FlossPay",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addCardDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        final EditText pan = new EditText(this);
        pan.setHint("رقم البطاقة (PAN)");
        pan.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(pan);

        final EditText holder = new EditText(this);
        holder.setHint("الاسم على البطاقة");
        box.addView(holder);

        final EditText expiry = new EditText(this);
        expiry.setHint("تاريخ الانتهاء MM/YY");
        box.addView(expiry);

        final EditText cvv = new EditText(this);
        cvv.setHint("CVV");
        cvv.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(cvv);

        final EditText balance = new EditText(this);
        balance.setHint("الرصيد (مثال 1000.00)");
        balance.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(balance);

        new AlertDialog.Builder(this)
                .setTitle("إضافة بطاقة")
                .setView(box)
                .setPositiveButton("إضافة", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String p = pan.getText().toString().trim();
                        if (!Util.luhnValid(p)) {
                            Toast.makeText(MainActivity.this,
                                    "رقم البطاقة غير صحيح (فشل Luhn)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        long bal;
                        try {
                            bal = parseAmountMinor(balance.getText().toString());
                        } catch (Exception e) {
                            bal = 0;
                        }
                        Card c = new Card(p,
                                holder.getText().toString().trim(),
                                expiry.getText().toString().trim(),
                                cvv.getText().toString().trim(),
                                bal, false);
                        cards.add(c);
                        store.save(cards);
                        renderCards();
                        log("＋ أضيفت بطاقة " + Util.mask(p) + " — غير موثّقة حتى أول عملية ناجحة");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** "25.50" -> 2550 (وحدات صغرى). */
    private long parseAmountMinor(String s) {
        s = s.trim().replace(',', '.');
        if (s.isEmpty()) throw new NumberFormatException("فارغ");
        int dot = s.indexOf('.');
        if (dot < 0) return Long.parseLong(s) * 100;
        String whole = s.substring(0, dot);
        String frac = s.substring(dot + 1);
        if (frac.length() > 2) frac = frac.substring(0, 2);
        while (frac.length() < 2) frac += "0";
        long w = whole.isEmpty() ? 0 : Long.parseLong(whole);
        return w * 100 + Long.parseLong(frac);
    }

    private void log(String s) {
        logView.append(s + "\n");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
