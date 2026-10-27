package com.floss83.diag;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FlossDiag — التطبيق الأساسي (build-11):
 *
 * 1) اختبار جهاز POS (NFC): يحدد مبلغ الاختبار ← يؤكده ← يشغّل الاختبار عبر المحفظة
 *    (FlossPay) التي تتعامل مع الجهاز كبطاقة حقيقية وتقرأ ردوده ← النتيجة تعود هنا
 *    تلقائيًا وتُحفظ في ملف النتائج.
 * 2) اختبار بوابة دفع / متجر (أونلاين): تسجيل نتيجة استخدام البطاقة في بوابة أو متجر
 *    حقيقي — الموافقة/كود الرفض/3DS/الخطأ التقني — في نفس ملف النتائج.
 * 3) بطاقات الاختبار الرسمية (build-12): أرقام الاختبار المعلنة من وثائق البوابات
 *    الشائعة (Moyasar / MyFatoorah / PayTabs / HyperPay / Checkout.com …) مع زر نسخ،
 *    لتوحيد المدخلات عند فحص صفحة الدفع ومقارنة ردود البوابة.
 * 4) النتائج: ملف محفوظ (results.jsonl) يعرض كل القراءات والأخطاء، مع مشاركة الملف
 *    ورفعه لخادم التشخيص.
 * 5) Lithic (build-16): إصدار بطاقات من مُصدر مرخص حقيقي عبر جسر lithic_proxy.py —
 *    بطاقة VIRTUAL كاملة (PAN/CVV/انتهاء) + محاكاة شراء بنتيجة حقيقية من المُصدر
 *    (APPROVED / DECLINED + أكواد مُعرّبة) + سجل آخر المعاملات، ويُسجَّل الكل
 *    في ملف النتائج. المفتاح لا يمر عبر التطبيق — يبقى في بيئة الخادم فقط.
 */
public class DiagActivity extends Activity {

    private static final String PREFS = "flossdiag";
    private static final String HOST_KEY = "diag_host";
    private static final String DEFAULT_HOST = "sull.world:8096";
    private static final String LITHIC_HOST_KEY = "lithic_host";
    private static final String DEFAULT_LITHIC_HOST = "sull.world:8097";

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private long confirmedAmountMinor = -1;
    private TextView confirmLabel;
    private EditText posAmount, gwStore, gwAmount, gwCode, gwNotes, hostEdit;
    private Spinner gwNetwork, gwOutcome, cardGateway, posNetwork, billCompany;
    private LinearLayout resultsBox, cardsBox;
    private EditText billAccount, billAmount;

    // ===== Lithic (build-16) =====
    private EditText lithicHost, lithicLimit, lithicPan, lithicAmount, lithicDescriptor;
    private LinearLayout lithicBox;

    private static final String[] NETWORKS = {"Visa", "MasterCard", "Amex", "mada", "أخرى"};

    // ===== فحص دعم الشبكات (build-13): AID كل شبكة (نفس ثوابت المحفظة) =====
    private static final String[] PROBE_NETWORKS = {
            "كل الشبكات (وضع عادي)", "mada", "Visa", "MasterCard", "Amex", "JCB", "Discover", "Diners", "RuPay"
    };
    private static final String[] PROBE_AIDS = {
            "", "A0000002281010", "A0000000031010", "A0000000041010", "A0000000251010",
            "A0000000651010", "A0000000151010", "A0000001523010", "A0000005241010"
    };

    // ===== سداد الفواتير (build-15): الشركة + رقم حساب الفاتورة + قيمة الفاتورة =====
    private static final String[] BILL_COMPANIES = {
            "الشركة السعودية للكهرباء", "شركة المياه الوطنية"
    };
    private static final String[] BILL_URLS = {
            "https://www.se.com.sa/ar-SA/GuestViewBill",
            "https://www.nwc.com.sa/ar-SA/Pages/default.aspx"
    };

    private static final String[] OUTCOMES = {
            "موافقة (عملية ناجحة)", "رفض بكود", "فشل 3DS / OTP", "خطأ تقني", "انتهت مهلة"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(box);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("🧪 FlossDiag — فاحص أجهزة وبوابات الدفع");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("التطبيق الأساسي: يحدد مبلغ الاختبار ويؤكده، ثم يفحص الجهاز أو البوابة عبر المحفظة (FlossPay)، ويحفظ نتيجة القراءة والأخطاء في ملف.");
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        buildPosSection(box);
        buildGatewaySection(box);
        buildTestCardsSection(box);
        buildBillPaySection(box);
        buildLithicSection(box);
        buildResultsSection(box);
    }

    private void buildPosSection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("🛒 1) اختبار جهاز POS (NFC عبر المحفظة)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(8), 0, dp(4));
        box.addView(h);

        posAmount = new EditText(this);
        posAmount.setHint("مبلغ الاختبار بالريال (مثال: 40 أو 40.50)");
        posAmount.setTextSize(14);
        posAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(posAmount);

        // فحص دعم الشبكات (build-13): بطاقة أحادية الشبكة تكشف ردود الجهاز
        posNetwork = new Spinner(this);
        ArrayAdapter<String> pn = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, PROBE_NETWORKS);
        pn.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        posNetwork.setAdapter(pn);
        box.addView(posNetwork);

        TextView probeHint = new TextView(this);
        probeHint.setText("🛰 فحص دعم الشبكات: اختر شبكة واحدة (بدل «كل الشبكات») فتعلن المحفظة AID تلك الشبكة وحدها في PPSE. "
                + "لو أكمل الجهاز بعد الدليل (اختر التطبيق وتابع GPO) فهو يدعمها ✔؛ لو قرأ الدليل وتوقف فهو لا يدعمها ✘. "
                + "كرر الاختبار شبكة شبكة على الجهاز نفسه لترسم خريطة دعمه الكاملة (Visa/MC/mada/Amex…).");
        probeHint.setTextSize(12);
        probeHint.setPadding(dp(4), dp(2), dp(4), dp(4));
        box.addView(probeHint);

        Button confirm = new Button(this);
        confirm.setText("✓ تأكيد المبلغ");
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long minor = parseAmount(posAmount.getText().toString());
                if (minor <= 0) {
                    Toast.makeText(DiagActivity.this, "أدخل مبلغًا صحيحًا أولًا", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmedAmountMinor = minor;
                confirmLabel.setText("المبلغ المؤكد: " + fmt(minor) + " ريال — جاهز لبدء الاختبار");
                Toast.makeText(DiagActivity.this, "تم تأكيد المبلغ", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(confirm);

        confirmLabel = new TextView(this);
        confirmLabel.setText("لم يُؤكَّد مبلغ بعد.");
        confirmLabel.setTextSize(14);
        confirmLabel.setTypeface(null, Typeface.BOLD);
        confirmLabel.setPadding(dp(4), dp(2), dp(4), dp(6));
        box.addView(confirmLabel);

        Button start = new Button(this);
        start.setText("▶ بدء الاختبار عبر المحفظة");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (confirmedAmountMinor <= 0) {
                    Toast.makeText(DiagActivity.this, "أكّد المبلغ أولًا", Toast.LENGTH_SHORT).show();
                    return;
                }
                String testId = "T" + System.currentTimeMillis();
                try {
                    Intent i = new Intent();
                    i.setClassName("com.floss83.pay", "com.floss83.pay.TestActivity");
                    i.putExtra("amountMinor", confirmedAmountMinor);
                    i.putExtra("testId", testId);
                    i.putExtra("networkProfile", PROBE_AIDS[posNetwork.getSelectedItemPosition()]);
                    startActivity(i);
                    String net = PROBE_NETWORKS[posNetwork.getSelectedItemPosition()];
                    Toast.makeText(DiagActivity.this,
                            net.equals("كل الشبكات (وضع عادي)")
                                    ? "المحفظة جاهزة — أدخل المبلغ نفسه في الجهاز وقرّب الهاتف. النتيجة تعود هنا تلقائيًا"
                                    : "وضع فحص شبكة " + net + " — اختر بطاقة من نفس الشبكة في المحفظة ثم قرّب الهاتف",
                            Toast.LENGTH_LONG).show();
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(DiagActivity.this,
                            "ثبّت تطبيق المحفظة FlossPay أولًا (نفس الإصدار build-14)", Toast.LENGTH_LONG).show();
                }
            }
        });
        box.addView(start);

        TextView note = new TextView(this);
        note.setText("بعد اللمسة: الجلسة (المرحلة + المبلغ الذي أرسله الجهاز + ردوده + الأخطاء) تصل تلقائيًا إلى قسم «النتائج» أدناه وتُحفظ في الملف. للمقارنة: كرر الاختبار بمبلغ آخر على الجهاز نفسه.");
        note.setTextSize(12);
        note.setPadding(dp(4), dp(2), dp(4), dp(8));
        box.addView(note);
    }

    private void buildGatewaySection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("🌐 2) اختبار بوابة دفع / متجر (أونلاين)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
        box.addView(h);

        TextView gh = new TextView(this);
        gh.setText("استخدم بطاقة من المحفظة في صفحة الدفع، ثم سجّل ما ردّت به البوابة — هذا يوثّق نمط «يعمل في الاختبار ويفشل في الإنتاج» لكل شبكة ومبلغ.");
        gh.setTextSize(12);
        gh.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(gh);

        gwStore = new EditText(this);
        gwStore.setHint("اسم المتجر أو البوابة (مثال: متجر X / HyperPay)");
        gwStore.setTextSize(14);
        box.addView(gwStore);

        gwAmount = new EditText(this);
        gwAmount.setHint("المبلغ بالريال");
        gwAmount.setTextSize(14);
        gwAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(gwAmount);

        gwNetwork = new Spinner(this);
        ArrayAdapter<String> na = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, NETWORKS);
        na.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gwNetwork.setAdapter(na);
        box.addView(gwNetwork);

        gwOutcome = new Spinner(this);
        ArrayAdapter<String> oa = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, OUTCOMES);
        oa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gwOutcome.setAdapter(oa);
        box.addView(gwOutcome);

        gwCode = new EditText(this);
        gwCode.setHint("كود الرد إن وجد (مثال: 05 / 51 / N7)");
        gwCode.setTextSize(14);
        box.addView(gwCode);

        gwNotes = new EditText(this);
        gwNotes.setHint("ملاحظات (مثال: يعمل بمبلغ 100 ويفشل بمبلغ 10)");
        gwNotes.setTextSize(14);
        box.addView(gwNotes);

        Button save = new Button(this);
        save.setText("💾 حفظ النتيجة في الملف");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String store = gwStore.getText().toString().trim();
                long minor = parseAmount(gwAmount.getText().toString());
                if (store.isEmpty() || minor <= 0) {
                    Toast.makeText(DiagActivity.this, "أدخل اسم المتجر/البوابة والمبلغ", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "gateway");
                    o.put("ts", System.currentTimeMillis());
                    o.put("store", store);
                    o.put("amountMinor", minor);
                    o.put("network", NETWORKS[gwNetwork.getSelectedItemPosition()]);
                    o.put("outcome", OUTCOMES[gwOutcome.getSelectedItemPosition()]);
                    String code = gwCode.getText().toString().trim();
                    if (!code.isEmpty()) o.put("code", code);
                    String notes = gwNotes.getText().toString().trim();
                    if (!notes.isEmpty()) o.put("notes", notes);
                    DiagFileStore.append(DiagActivity.this, o.toString());
                    Toast.makeText(DiagActivity.this, "حُفظت في ملف النتائج", Toast.LENGTH_SHORT).show();
                    gwCode.setText(""); gwNotes.setText("");
                    renderResults();
                } catch (Exception ignore) {}
            }
        });
        box.addView(save);
    }

    private void buildTestCardsSection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("💳 3) بطاقات اختبار البوابات (رسمية)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
        box.addView(h);

        TextView warn = new TextView(this);
        warn.setText("⚠️ هذه الأرقام من وثائق كل بوابة الرسمية، وتعمل فقط في وضع التجربة (Test/Sandbox) لدى البوابة. في صفحة الدفع الحقيقية (Live) سيُرفض أيٌّ منها — وهذا متوقع وليس عطلًا. الفائدة: نفس الرقم في كل محاولة ⇒ تقارن رد البوابة عبر المبالغ والمتاجر، وتسجّل النتيجة في القسم 2 أعلاه.");
        warn.setTextSize(12);
        warn.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(warn);

        cardGateway = new Spinner(this);
        String[] names = new String[GatewayCards.GATEWAYS.length];
        for (int i = 0; i < names.length; i++) names[i] = GatewayCards.GATEWAYS[i].name;
        ArrayAdapter<String> ga = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cardGateway.setAdapter(ga);
        cardGateway.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                renderGatewayCards(pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(cardGateway);

        cardsBox = new LinearLayout(this);
        cardsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(cardsBox);
    }

    private void renderGatewayCards(int idx) {
        if (cardsBox == null) return;
        cardsBox.removeAllViews();
        final GatewayCards.Gateway g = GatewayCards.GATEWAYS[idx];

        TextView note = new TextView(this);
        note.setText(g.note);
        note.setTextSize(12);
        note.setPadding(dp(4), dp(6), dp(4), dp(4));
        cardsBox.addView(note);

        for (int i = 0; i < g.cards.length; i++) {
            final GatewayCards.Card c = g.cards[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            card.setLayoutParams(lp);
            card.setBackgroundColor(0xFF14406B);

            TextView pan = new TextView(this);
            pan.setText(c.pan);
            pan.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            pan.setTextSize(16);
            pan.setTextColor(0xFFFFFFFF);
            card.addView(pan);

            TextView meta = new TextView(this);
            meta.setText(c.network + " | الانتهاء: " + c.expiry + " | CVV: " + c.cvv);
            meta.setTextSize(12);
            meta.setTextColor(0xFFBBBBBB);
            meta.setPadding(0, dp(2), 0, dp(2));
            card.addView(meta);

            TextView exp = new TextView(this);
            exp.setText("المتوقع: " + c.expected);
            exp.setTextSize(12);
            exp.setTextColor(0xFFDDDDDD);
            card.addView(exp);

            LinearLayout rowBtns = new LinearLayout(this);
            rowBtns.setOrientation(LinearLayout.HORIZONTAL);

            Button copyPan = new Button(this);
            copyPan.setText("📋 نسخ الرقم");
            copyPan.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyText(c.pan, "نُسخ رقم البطاقة");
                }
            });
            rowBtns.addView(copyPan);

            Button copyAll = new Button(this);
            copyAll.setText("📋 نسخ الرقم + التاريخ + CVV");
            copyAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyText(c.pan + " / " + c.expiry + " / " + c.cvv,
                            "نُسخ الرقم والتاريخ وCVV");
                }
            });
            rowBtns.addView(copyAll);

            card.addView(rowBtns);
            cardsBox.addView(card);
        }
    }

    private void copyText(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("card", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void buildBillPaySection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("⚡ 4) سداد الفواتير (شركة الكهرباء)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
        box.addView(h);

        TextView hint = new TextView(this);
        hint.setText("اختر الشركة ← أدخل رقم حساب الفاتورة ← افتح موقع الشركة الرسمي لرؤية قيمة فاتورتك الحقيقية ← أدخل القيمة هنا ← سداد عبر بطاقة المحفظة.");
        hint.setTextSize(12);
        hint.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(hint);

        billCompany = new Spinner(this);
        ArrayAdapter<String> ba = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, BILL_COMPANIES);
        ba.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        billCompany.setAdapter(ba);
        box.addView(billCompany);

        billAccount = new EditText(this);
        billAccount.setHint("رقم حساب الفاتورة (مثال: 4321000123)");
        billAccount.setTextSize(14);
        billAccount.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(billAccount);

        Button openOfficial = new Button(this);
        openOfficial.setText("🌐 عرض قيمة الفاتورة على موقع الشركة الرسمي");
        openOfficial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(BILL_URLS[billCompany.getSelectedItemPosition()])));
                } catch (Exception e) {
                    Toast.makeText(DiagActivity.this, "لا يوجد متصفح", Toast.LENGTH_SHORT).show();
                }
            }
        });
        box.addView(openOfficial);

        billAmount = new EditText(this);
        billAmount.setHint("قيمة الفاتورة بالريال (من الموقع الرسمي — مثال: 210.55)");
        billAmount.setTextSize(14);
        billAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(billAmount);

        Button pay = new Button(this);
        pay.setText("💳 سداد عبر المحفظة (بطاقات + سويتش المعمل)");
        pay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String account = billAccount.getText().toString().trim();
                long minor = parseAmount(billAmount.getText().toString());
                if (account.isEmpty() || minor <= 0) {
                    Toast.makeText(DiagActivity.this,
                            "أدخل رقم حساب الفاتورة وقيمتها (من الموقع الرسمي)", Toast.LENGTH_SHORT).show();
                    return;
                }
                String company = BILL_COMPANIES[billCompany.getSelectedItemPosition()];
                try {
                    Intent i = new Intent();
                    i.setClassName("com.floss83.pay", "com.floss83.pay.BillPayActivity");
                    i.putExtra("company", company);
                    i.putExtra("account", account);
                    i.putExtra("amountMinor", minor);
                    startActivity(i);
                    Toast.makeText(DiagActivity.this,
                            "اختر بطاقة المحفظة وأكمل السداد — النتيجة تعود هنا تلقائيًا",
                            Toast.LENGTH_LONG).show();
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(DiagActivity.this,
                            "ثبّت تطبيق المحفظة FlossPay أولًا (نفس الإصدار build-15)", Toast.LENGTH_LONG).show();
                }
            }
        });
        box.addView(pay);

        TextView note = new TextView(this);
        note.setText("⚠️ الحد الصادق: لا يوجد API عام لاستعلام فواتير الشركات — القيمة الحقيقية تُقرأ من الموقع الرسمي. "
                + "السداد عبر المحفظة محاكاة معملية على سويتش Flossx83 (لا يخصم من فاتورتك الحقيقية). "
                + "السداد الفعلي لفاتورتك: من موقع الشركة أو تطبيق البنك (سداد). النتيجة تُسجّل في ملف النتائج أدناه.");
        note.setTextSize(11);
        note.setPadding(dp(4), dp(2), dp(4), dp(4));
        box.addView(note);
    }

    // ===== Lithic: مُصدر مرخص حقيقي (build-16) =====

    private void buildLithicSection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("🏦 5) Lithic — بطاقة من مُصدر حقيقي (Sandbox)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
        box.addView(h);

        TextView hint = new TextView(this);
        hint.setText("إصدار بطاقة VIRTUAL من مُصدر مرخص فعليًا (Lithic) عبر الجسر على خادمك — القبول/الرفض بمنطق مُصدر بنكي حقيقي (حدود صرف، ASA، قواعد تفويض). "
                + "⚠️ صادقة: هذه بطاقات بيئة الاختبار فقط — لن تُقبل على POS أو بوابة حقيقية؛ قيمتها هنا أننا نرصد سلوك المُصدر الحقيقي ونعرّبه ونسجّله في النتائج. "
                + "مفتاح API لا يمر عبر التطبيق إطلاقًا — يبقى في بيئة الخادم (LITHIC_API_KEY).");
        hint.setTextSize(12);
        hint.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(hint);

        lithicHost = new EditText(this);
        lithicHost.setText(getLithicHost());
        lithicHost.setTextSize(13);
        lithicHost.setHint("خادم الجسر (host:port)");
        box.addView(lithicHost);

        lithicLimit = new EditText(this);
        lithicLimit.setHint("حد صرف البطاقة بالسنت دولار (اختياري — مثال: 10000 = 100$)");
        lithicLimit.setTextSize(14);
        lithicLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(lithicLimit);

        Button issue = new Button(this);
        issue.setText("💳 إصدار بطاقة افتراضية من Lithic");
        issue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                issueLithicCard();
            }
        });
        box.addView(issue);

        lithicPan = new EditText(this);
        lithicPan.setHint("PAN البطاقة (يُملأ تلقائيًا بعد الإصدار)");
        lithicPan.setTextSize(14);
        lithicPan.setTypeface(Typeface.MONOSPACE);
        box.addView(lithicPan);

        lithicAmount = new EditText(this);
        lithicAmount.setHint("المبلغ بالدولار (مثال: 38.31)");
        lithicAmount.setTextSize(14);
        lithicAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(lithicAmount);

        lithicDescriptor = new EditText(this);
        lithicDescriptor.setText("FLOSS DIAG SHOP");
        lithicDescriptor.setTextSize(14);
        box.addView(lithicDescriptor);

        Button sim = new Button(this);
        sim.setText("🧪 محاكاة شراء على البطاقة");
        sim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                simulateLithicAuth();
            }
        });
        box.addView(sim);

        Button listTx = new Button(this);
        listTx.setText("📜 آخر 10 معاملات Lithic");
        listTx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLithicTransactions();
            }
        });
        box.addView(listTx);

        lithicBox = new LinearLayout(this);
        lithicBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(lithicBox);
    }

    private void issueLithicCard() {
        final String host = lithicHost.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "حدد خادم الجسر أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        setLithicHost(host);
        final long limit = parseAmount(lithicLimit.getText().toString());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = "{}";
                try {
                    JSONObject req = new JSONObject();
                    if (limit > 0) req.put("spend_limit_minor", limit);
                    body = req.toString();
                    final String resp = LithicClient.post(host, "/api/issue_card", body);
                    final JSONObject card = new JSONObject(resp);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { showLithicCard(card); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(DiagActivity.this,
                                    "فشل الإصدار: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void showLithicCard(JSONObject card) {
        try {
            String pan = card.optString("pan", "-");
            lithicPan.setText(pan);
            copyText(pan, "نُسخ PAN البطاقة الصادرة");

            JSONObject row = new JSONObject();
            row.put("type", "lithic_card");
            row.put("ts", System.currentTimeMillis());
            row.put("pan", pan);
            row.put("last4", card.optString("last_four", "-"));
            row.put("exp", card.optString("exp_month", "-") + "/" + card.optString("exp_year", "-"));
            row.put("state", card.optString("state", "-"));
            if (card.has("spend_limit")) row.put("spend_limit", card.optLong("spend_limit"));
            DiagFileStore.append(this, row.toString());
            renderResults();

            if (lithicBox == null) return;
            lithicBox.removeAllViews();
            lithicBox.addView(makeLithicCardView(
                    "💳 بطاقة صادرة من Lithic: " + pan
                            + " | CVV " + card.optString("cvv", "-")
                            + " | انتهاء " + card.optString("exp_month", "-") + "/" + card.optString("exp_year", "-")
                            + " | الحالة " + card.optString("state", "-") + " (" + LithicCodes.stateLabelAr(card.optString("state")) + ")",
                    0xFF0D3B0D));
        } catch (Exception ignore) {}
    }

    private void simulateLithicAuth() {
        final String host = lithicHost.getText().toString().trim();
        final String pan = lithicPan.getText().toString().trim();
        final long minor = LithicCodes.parseUsdMinor(lithicAmount.getText().toString());
        final String descriptor = lithicDescriptor.getText().toString().trim();
        if (host.isEmpty()) { Toast.makeText(this, "حدد خادم الجسر أولًا", Toast.LENGTH_SHORT).show(); return; }
        if (pan.isEmpty()) { Toast.makeText(this, "أصدر بطاقة أولًا (أو أدخل PAN)", Toast.LENGTH_SHORT).show(); return; }
        if (minor <= 0) { Toast.makeText(this, "أدخل مبلغًا صحيحًا بالدولار", Toast.LENGTH_SHORT).show(); return; }
        setLithicHost(host);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("pan", pan);
                    req.put("amount_minor", minor);
                    req.put("descriptor", descriptor.isEmpty() ? "FLOSS DIAG SHOP" : descriptor);
                    final String resp = LithicClient.post(host, "/api/simulate_auth", req.toString());
                    final JSONObject tx = new JSONObject(resp);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { showLithicAuthResult(tx); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(DiagActivity.this,
                                    "فشلت المحاكاة: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void showLithicAuthResult(JSONObject tx) {
        boolean pending = tx.optBoolean("pending", false);
        if (pending) {
            if (lithicBox != null) {
                lithicBox.addView(makeLithicCardView(
                        "⏳ أُرسلت المحاكاة ولم تظهر النتيجة بعد — جرّب «آخر المعاملات» بعد لحظات",
                        0xFF7A4A00));
            }
            return;
        }
        String result = tx.optString("result", "-");
        boolean ok = LithicCodes.isApproved(result);
        StringBuilder codes = new StringBuilder();
        org.json.JSONArray arr = tx.optJSONArray("detailed_results");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) codes.append(i == 0 ? "" : "، ").append(arr.optString(i));
        }
        String arExplain = LithicCodes.explainFirstAr(codes.length() == 0
                ? java.util.Collections.<String>emptyList()
                : java.util.Arrays.asList(codes.toString().split("، ")));

        try {
            JSONObject row = new JSONObject();
            row.put("type", "lithic_auth");
            row.put("ts", System.currentTimeMillis());
            row.put("pan", lithicPan.getText().toString().trim());
            row.put("descriptor", tx.optString("descriptor", "-"));
            row.put("amountMinor", tx.optLong("amount_minor", -1));
            row.put("result", result);
            if (codes.length() > 0) row.put("codes", codes.toString());
            DiagFileStore.append(this, row.toString());
            renderResults();
        } catch (Exception ignore) {}

        if (lithicBox == null) return;
        lithicBox.addView(makeLithicCardView(
                (ok ? "✔ " : "✘ ") + "محاكاة: " + tx.optString("descriptor", "-")
                        + " | " + LithicCodes.fmtUsd(tx.optLong("amount_minor", -1))
                        + " | النتيجة " + result
                        + (codes.length() > 0 ? " | الأكواد: " + codes : "")
                        + "\n" + arExplain,
                ok ? 0xFF0D3B0D : 0xFF6A1010));
    }

    private void loadLithicTransactions() {
        final String host = lithicHost.getText().toString().trim();
        if (host.isEmpty()) { Toast.makeText(this, "حدد خادم الجسر أولًا", Toast.LENGTH_SHORT).show(); return; }
        setLithicHost(host);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String resp = LithicClient.get(host, "/api/transactions?limit=10");
                    final JSONObject obj = new JSONObject(resp);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { renderLithicTransactions(obj); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(DiagActivity.this,
                                    "تعذر جلب المعاملات: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void renderLithicTransactions(JSONObject obj) {
        if (lithicBox == null) return;
        lithicBox.removeAllViews();
        org.json.JSONArray arr = obj.optJSONArray("data");
        if (arr == null || arr.length() == 0) {
            lithicBox.addView(makeLithicCardView("لا معاملات بعد على حساب Lithic.", 0xFF333333));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String result = t.optString("result", "-");
            boolean ok = LithicCodes.isApproved(result);
            StringBuilder codes = new StringBuilder();
            org.json.JSONArray cr = t.optJSONArray("detailed_results");
            if (cr != null) {
                for (int j = 0; j < cr.length(); j++) codes.append(j == 0 ? "" : "، ").append(cr.optString(j));
            }
            lithicBox.addView(makeLithicCardView(
                    (ok ? "✔ " : "✘ ") + t.optString("descriptor", "-")
                            + " | " + LithicCodes.fmtUsd(t.optLong("amount_minor", -1))
                            + " | " + result
                            + (codes.length() > 0 ? " | " + codes : "")
                            + " | " + t.optString("created", "-")
                            + "\n" + (codes.length() > 0
                                ? LithicCodes.explainAr(codes.toString().split("، ")[0])
                                : LithicCodes.explainAr(result)),
                    ok ? 0xFF0D3B0D : 0xFF6A1010));
        }
    }

    private View makeLithicCardView(String text, int bgColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        card.setBackgroundColor(bgColor);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(12);
        card.addView(tv);
        return card;
    }

    private String getLithicHost() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(LITHIC_HOST_KEY, DEFAULT_LITHIC_HOST);
    }

    private void setLithicHost(String host) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(LITHIC_HOST_KEY, host);
        e.apply();
    }

    private void buildResultsSection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("📄 6) النتائج — ملف محفوظ (results.jsonl)");
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, dp(12), 0, dp(4));
        box.addView(h);

        hostEdit = new EditText(this);
        hostEdit.setText(getHost());
        hostEdit.setTextSize(13);
        hostEdit.setHint("خادم التشخيص (host:port)");
        box.addView(hostEdit);

        LinearLayout rowBtns = new LinearLayout(this);
        rowBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("🔄 تحديث");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renderResults(); }
        });
        rowBtns.addView(refresh);

        Button share = new Button(this);
        share.setText("📤 مشاركة");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String> all = DiagFileStore.loadAll(DiagActivity.this);
                if (all.isEmpty()) {
                    Toast.makeText(DiagActivity.this, "لا نتائج بعد", Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("FlossDiag — نتائج الفحص (").append(all.size()).append(")\n\n");
                for (String line : all) sb.append(line).append("\n");
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, sb.toString());
                startActivity(Intent.createChooser(send, "مشاركة ملف النتائج"));
            }
        });
        rowBtns.addView(share);

        final Button upload = new Button(this);
        upload.setText("⬆ رفع الكل");
        upload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String host = hostEdit.getText().toString().trim();
                if (host.isEmpty()) { Toast.makeText(DiagActivity.this, "حدد الخادم أولًا", Toast.LENGTH_SHORT).show(); return; }
                setHost(host);
                upload.setEnabled(false);
                upload.setText("…جارٍ الرفع");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final int ok = DiagFileStore.uploadAll(DiagActivity.this, host);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                upload.setEnabled(true);
                                upload.setText("⬆ رفع الكل");
                                Toast.makeText(DiagActivity.this,
                                        "رُفع " + ok + " من النتائج إلى " + host, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        rowBtns.addView(upload);

        Button clear = new Button(this);
        clear.setText("🗑 مسح");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DiagFileStore.clear(DiagActivity.this);
                renderResults();
                Toast.makeText(DiagActivity.this, "تم مسح الملف", Toast.LENGTH_SHORT).show();
            }
        });
        rowBtns.addView(clear);

        box.addView(rowBtns);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(resultsBox);

        renderResults();
    }

    private void renderResults() {
        if (resultsBox == null) return;
        resultsBox.removeAllViews();
        List<String> all = DiagFileStore.loadAll(this);

        TextView count = new TextView(this);
        count.setText(all.isEmpty() ? "لا نتائج بعد — ابدأ اختبار POS أو سجّل نتيجة بوابة."
                                    : "عدد النتائج المحفوظة: " + all.size());
        count.setTextSize(13);
        count.setTypeface(null, Typeface.BOLD);
        count.setPadding(dp(4), dp(6), dp(4), dp(6));
        resultsBox.addView(count);

        // الأحدث أولًا
        for (int i = all.size() - 1; i >= 0; i--) {
            JSONObject o = DiagFileStore.parse(all.get(i));
            if (o == null) continue;
            resultsBox.addView(makeResultView(o));
        }
    }

    private View makeResultView(JSONObject o) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        long ts = o.optLong("ts", 0);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
        boolean isGateway = "gateway".equals(o.optString("type"));

        String head;
        int bg;
        if (isGateway) {
            String outcome = o.optString("outcome", "-");
            boolean ok = outcome.contains("موافقة");
            bg = ok ? 0xFF0D3B0D : 0xFF14406B;
            head = when + " | 🌐 بوابة/متجر: " + o.optString("store", "-")
                    + " | " + o.optString("network", "-")
                    + " | " + fmt(o.optLong("amountMinor", -1)) + " ريال"
                    + " | " + outcome
                    + (o.optString("code", "").isEmpty() ? "" : " (كود " + o.optString("code") + ")");
        } else if ("sadad".equals(o.optString("type"))) {
            boolean approved = o.optBoolean("approved", false);
            bg = approved ? 0xFF0D3B0D : 0xFF7A4A00;
            head = when + " | ⚡ سداد فاتورة: " + o.optString("company", "-")
                    + " | حساب " + o.optString("account", "-")
                    + " | " + fmt(o.optLong("amountMinor", -1)) + " ريال"
                    + " | بطاقة •••• " + o.optString("cardLast4", "-")
                    + (approved ? " | ✔ " : " | ✘ ")
                    + (o.optString("code", "").isEmpty() ? "" : "كود " + o.optString("code"));
        } else if ("lithic_card".equals(o.optString("type"))) {
            bg = 0xFF14406B;
            head = when + " | 🏦 Lithic: بطاقة صادرة " + o.optString("pan", "-")
                    + " | انتهاء " + o.optString("exp", "-")
                    + " | الحالة " + o.optString("state", "-");
        } else if ("lithic_auth".equals(o.optString("type"))) {
            boolean ok = LithicCodes.isApproved(o.optString("result", ""));
            bg = ok ? 0xFF0D3B0D : 0xFF6A1010;
            head = when + " | 🏦 Lithic: محاكاة " + o.optString("descriptor", "-")
                    + " | " + LithicCodes.fmtUsd(o.optLong("amountMinor", -1))
                    + " | " + o.optString("result", "-")
                    + (o.optString("codes", "").isEmpty() ? "" : " | " + o.optString("codes"));
        } else {
            int stageNum = o.optInt("stageNum", -1);
            boolean match = o.optBoolean("amountMatch", false) && o.optLong("amountMinor", -1) >= 0;
            String prof = o.optString("networkProfile", "");
            boolean hasProf = !prof.isEmpty() && !"-".equals(prof) && !"null".equals(prof);
            bg = match ? 0xFF0D3B0D : (o.optLong("amountMinor", -1) >= 0 ? 0xFF6A1010 : 0xFF333333);
            if (hasProf && o.has("networkSupported")) {
                bg = o.optBoolean("networkSupported", false) ? 0xFF0D3B0D : 0xFF6A1010;
            }
            head = when + " | 🛒 POS"
                    + " | مبلغ الاختبار: " + fmt(o.optLong("expectedAmount", -1))
                    + " | أرسل الجهاز: " + (o.optLong("amountMinor", -1) >= 0 ? fmt(o.optLong("amountMinor", -1)) : "لا شيء")
                    + (match ? " ✔" : (o.optLong("amountMinor", -1) >= 0 ? " ⚠️ مختلف" : ""))
                    + " | مرحلة " + stageNum + ": " + o.optString("stage", "-")
                    + " | أخطاء SW: " + o.optInt("errors", 0)
                    + (hasProf ? " | 🛰 " + prof + (o.has("networkSupported")
                        ? (o.optBoolean("networkSupported", false) ? " ✔ مدعومة" : " ✘ غير مدعومة")
                        : "") : "");
        }
        card.setBackgroundColor(bg);

        TextView headTv = new TextView(this);
        headTv.setText(head);
        headTv.setTextColor(0xFFFFFFFF);
        headTv.setTypeface(null, Typeface.BOLD);
        headTv.setTextSize(12);
        card.addView(headTv);

        String verdict = o.optString("verdict", "");
        if (!verdict.isEmpty()) {
            TextView vTv = new TextView(this);
            vTv.setText(verdict.length() > 160 ? verdict.substring(0, 160) + "…" : verdict);
            vTv.setTextColor(0xFFDDDDDD);
            vTv.setTextSize(11);
            vTv.setPadding(0, dp(6), 0, 0);
            card.addView(vTv);
        }
        return card;
    }

    // ===== أدوات =====

    private static long parseAmount(String s) {
        try {
            return Math.round(Double.parseDouble(s.trim()) * 100.0);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(long minor) {
        if (minor < 0) return "-";
        return String.format(Locale.US, "%.2f", minor / 100.0);
    }

    private String getHost() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(HOST_KEY, DEFAULT_HOST);
    }

    private void setHost(String host) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(HOST_KEY, host);
        e.apply();
    }
}
