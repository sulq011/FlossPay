package com.floss83.diag;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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
 * 3) النتائج: ملف محفوظ (results.jsonl) يعرض كل القراءات والأخطاء، مع مشاركة الملف
 *    ورفعه لخادم التشخيص.
 */
public class DiagActivity extends Activity {

    private static final String PREFS = "flossdiag";
    private static final String HOST_KEY = "diag_host";
    private static final String DEFAULT_HOST = "sull.world:8096";

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private long confirmedAmountMinor = -1;
    private TextView confirmLabel;
    private EditText posAmount, gwStore, gwAmount, gwCode, gwNotes, hostEdit;
    private Spinner gwNetwork, gwOutcome;
    private LinearLayout resultsBox;

    private static final String[] NETWORKS = {"Visa", "MasterCard", "Amex", "mada", "أخرى"};
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
                    startActivity(i);
                    Toast.makeText(DiagActivity.this,
                            "المحفظة جاهزة — أدخل المبلغ نفسه في الجهاز وقرّب الهاتف. النتيجة تعود هنا تلقائيًا",
                            Toast.LENGTH_LONG).show();
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(DiagActivity.this,
                            "ثبّت تطبيق المحفظة FlossPay أولًا (نفس الإصدار build-11)", Toast.LENGTH_LONG).show();
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

    private void buildResultsSection(LinearLayout box) {
        TextView h = new TextView(this);
        h.setText("📄 3) النتائج — ملف محفوظ (results.jsonl)");
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
        } else {
            int stageNum = o.optInt("stageNum", -1);
            boolean match = o.optBoolean("amountMatch", false) && o.optLong("amountMinor", -1) >= 0;
            bg = match ? 0xFF0D3B0D : (o.optLong("amountMinor", -1) >= 0 ? 0xFF6A1010 : 0xFF333333);
            head = when + " | 🛒 POS"
                    + " | مبلغ الاختبار: " + fmt(o.optLong("expectedAmount", -1))
                    + " | أرسل الجهاز: " + (o.optLong("amountMinor", -1) >= 0 ? fmt(o.optLong("amountMinor", -1)) : "لا شيء")
                    + (match ? " ✔" : (o.optLong("amountMinor", -1) >= 0 ? " ⚠️ مختلف" : ""))
                    + " | مرحلة " + stageNum + ": " + o.optString("stage", "-")
                    + " | أخطاء SW: " + o.optInt("errors", 0);
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
