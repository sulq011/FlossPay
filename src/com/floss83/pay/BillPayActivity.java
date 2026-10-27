package com.floss83.pay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * سداد الفواتير (build-15) — شركة الكهرباء أولًا:
 * يستقبل من التطبيق الأساسي (FlossDiag) الشركة + رقم حساب الفاتورة + قيمة الفاتورة،
 * ثم يسدد عبر بطاقة من المحفظة على سويتش المعمل (ISO 8583 → المصدر المحاكى).
 *
 * الحد الصادق المعروض في الواجهة:
 * - لا يوجد API عام لاستعلام فواتير شركة الكهرباء (تحققنا: SEC صفحة GuestViewBill فقط،
 *   وسداد SADAD الحقيقي يتطلب شراكة مرخّصة مثل SingleView B2B) — لذلك قيمة الفاتورة
 *   الحقيقية تُقرأ من موقع الشركة الرسمي (زر يفتحه) وتُدخل هنا.
 * - السداد هنا محاكاة معملية: سويتش Flossx83 ومصدره المحاكى، ليس سداد SADAD حقيقيًا.
 * - السداد الحقيقي: من موقع الشركة نفسه أو تطبيق البنك (خدمة سداد).
 */
public class BillPayActivity extends Activity {

    public static final String EXTRA_COMPANY = "company";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_AMOUNT_MINOR = "amountMinor";
    public static final String URL_SEC = "https://www.se.com.sa/ar-SA/GuestViewBill";

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private String company, account;
    private long amountMinor;
    private Spinner cardSpinner;
    private List<Card> cards;
    private Button payBtn;
    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        company = getIntent().getStringExtra(EXTRA_COMPANY);
        account = getIntent().getStringExtra(EXTRA_ACCOUNT);
        amountMinor = getIntent().getLongExtra(EXTRA_AMOUNT_MINOR, -1);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(box);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("⚡ سداد الفواتير");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        if (company == null || account == null || amountMinor <= 0) {
            TextView err = new TextView(this);
            err.setText("\nتُفتح هذه الشاشة من التطبيق الأساسي «FlossDiag»: قسم سداد الفواتير — الشركة + رقم حساب الفاتورة + قيمة الفاتورة.");
            err.setTextSize(14);
            box.addView(err);
            return;
        }

        TextView bill = new TextView(this);
        bill.setText("الشركة: " + company
                + "\nرقم حساب الفاتورة: " + account
                + "\nقيمة الفاتورة: " + String.format(Locale.US, "%.2f ريال", amountMinor / 100.0));
        bill.setTextSize(17);
        bill.setTypeface(null, Typeface.BOLD);
        bill.setGravity(Gravity.CENTER);
        bill.setPadding(0, dp(12), 0, dp(12));
        box.addView(bill);

        // الموقع الرسمي — عرض الفاتورة الحقيقية وسدادها الفعلي
        Button openOfficial = new Button(this);
        openOfficial.setText("🌐 عرض الفاتورة الحقيقية على موقع الشركة");
        openOfficial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL_SEC)));
                } catch (Exception e) {
                    Toast.makeText(BillPayActivity.this, "لا يوجد متصفح", Toast.LENGTH_SHORT).show();
                }
            }
        });
        box.addView(openOfficial);

        TextView honest = new TextView(this);
        honest.setText("⚠️ حد صادق: لا يوجد API عام لاستعلام الفواتير — قيمة فاتورتك الحقيقية تظهر على موقع الشركة الرسمي (الزر أعلاه). "
                + "السداد هنا محاكاة معملية عبر سويتش Flossx83 (ISO 8583 + المصدر المحاكى)، وليس سداد SADAD حقيقيًا. "
                + "السداد الفعلي يتم من موقع الشركة أو تطبيق البنك.");
        honest.setTextSize(12);
        honest.setPadding(dp(4), dp(8), dp(4), dp(8));
        box.addView(honest);

        // اختيار بطاقة المحفظة
        CardStore store = new CardStore(this);
        cards = store.load();
        cardSpinner = new Spinner(this);
        if (cards.isEmpty()) {
            TextView noCards = new TextView(this);
            noCards.setText("\nلا بطاقات في المحفظة — أضف بطاقة أولًا من الشاشة الرئيسية.");
            noCards.setTextSize(14);
            box.addView(noCards);
        } else {
            String[] names = new String[cards.size()];
            for (int i = 0; i < cards.size(); i++) {
                Card c = cards.get(i);
                names[i] = CardProfile.detect(c.pan) + " •••• " + c.pan.substring(Math.max(0, c.pan.length() - 4))
                        + " (رصيد " + String.format(Locale.US, "%.2f", c.balanceMinor / 100.0) + ")";
            }
            ArrayAdapter<String> ca = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, names);
            ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            cardSpinner.setAdapter(ca);
            box.addView(cardSpinner);

            payBtn = new Button(this);
            payBtn.setText("💳 سداد عبر سويتش المعمل (" + String.format(Locale.US, "%.2f", amountMinor / 100.0) + " ريال)");
            payBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { pay(); }
            });
            box.addView(payBtn);
        }

        resultView = new TextView(this);
        resultView.setTextSize(14);
        resultView.setPadding(dp(4), dp(10), dp(4), 0);
        box.addView(resultView);
    }

    private void pay() {
        final CardStore store = new CardStore(this);
        final List<Card> cardsNow = store.load();
        int pos = cardSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= cardsNow.size()) return;
        final Card card = cardsNow.get(pos);

        payBtn.setEnabled(false);
        payBtn.setText("…جارٍ السداد");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String rc = "91", ac = "000000";
                boolean approved = false;
                try {
                    String hostPort = store.getHost();
                    String[] hp = hostPort.split(":");
                    String stan = String.format(Locale.US, "%06d",
                            (System.currentTimeMillis() / 1000) % 1000000);
                    String msg = Iso8583.build0200(card.pan, card.cvv, amountMinor, stan);
                    Iso8583.PayResult r = Iso8583.send(hp[0], Integer.parseInt(hp[1].trim()), msg);
                    approved = r.approved;
                    if (r.responseCode != null && !r.responseCode.isEmpty()) rc = r.responseCode;
                    if (r.authCode != null && !r.authCode.isEmpty()) ac = r.authCode;
                } catch (Exception e) {
                    rc = "91"; // issuer unavailable — نمط ISO الصادق
                }
                if (approved) {
                    store.adjustBalance(cardsNow, card.pan, -amountMinor);
                }
                final boolean ok = approved;
                final String fRc = rc, fAc = ac;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        payBtn.setEnabled(true);
                        payBtn.setText("💳 سداد عبر سويتش المعمل (" + String.format(Locale.US, "%.2f", amountMinor / 100.0) + " ريال)");
                        resultView.setText(ok
                                ? "✅ تم السداد (محاكاة): موافقة — كود " + fRc + "، رقم التصريح " + fAc
                                  + "\nخُصم من رصيد البطاقة وسُجّل في ملف نتائج الفاحص."
                                : "❌ رفض السداد (محاكاة): كود " + fRc
                                  + "\nراجع: تشغيل سويتش المعمل (sull.world:5000) أو رصيد البطاقة/حالة المصدر.");
                        Toast.makeText(BillPayActivity.this,
                                ok ? "تم سداد الفاتورة (محاكاة معملية)" : "رفض السداد — كود " + fRc,
                                Toast.LENGTH_LONG).show();
                        deliver(ok, fRc, fAc, card.pan);
                    }
                });
            }
        }).start();
    }

    /** تسجيل نتيجة السداد في ملف نتائج التطبيق الأساسي (FlossDiag). */
    private void deliver(boolean approved, String rc, String ac, String pan) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "sadad");
            o.put("ts", System.currentTimeMillis());
            o.put("company", company);
            o.put("account", account);
            o.put("amountMinor", amountMinor);
            o.put("cardLast4", pan.length() >= 4 ? pan.substring(pan.length() - 4) : pan);
            o.put("approved", approved);
            o.put("code", rc);
            if (approved) o.put("authCode", ac);
            Intent send = new Intent();
            send.setClassName("com.floss83.diag", "com.floss83.diag.DiagReceiver");
            send.putExtra("session", o.toString());
            sendBroadcast(send);
        } catch (Exception ignore) {}
    }
}
