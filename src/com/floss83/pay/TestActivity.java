package com.floss83.pay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * شاشة «اختبار POS» في المحفظة — تستقبل أمر الاختبار من التطبيق الأساسي (FlossDiag):
 * مبلغ الاختبار + معرّف الاختبار، تحفظهما كاختبار معلّق، ثم عند لمس الجهاز
 * تقارن HceService المبلغ المرسل من الجهاز بالمبلغ المتوقع وتسلّم النتيجة للتطبيق الأساسي.
 */
public class TestActivity extends Activity {

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        long amountMinor = getIntent().getLongExtra("amountMinor", -1);
        String testId = getIntent().getStringExtra("testId");

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(box);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("🧪 اختبار جهاز POS");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        if (amountMinor < 0 || testId == null) {
            TextView err = new TextView(this);
            err.setText("\nهذه الشاشة تُفتح من التطبيق الأساسي «FlossDiag» مع مبلغ الاختبار المؤكد.\n\n" +
                    "ثبّت FlossDiag وافتحه: حدّد المبلغ ← أكّده ← ابدأ الاختبار.");
            err.setTextSize(14);
            box.addView(err);
            return;
        }

        // حفظ الاختبار المعلّق — HceService ستقرأه عند لمسة الجهاز
        DiagStore.savePendingTest(this, testId, amountMinor);

        TextView amount = new TextView(this);
        amount.setText("المبلغ المؤكد للاختبار:\n" + String.format(java.util.Locale.US, "%.2f ريال", amountMinor / 100.0));
        amount.setTextSize(26);
        amount.setTypeface(null, Typeface.BOLD);
        amount.setGravity(Gravity.CENTER);
        amount.setPadding(0, dp(12), 0, dp(12));
        box.addView(amount);

        TextView steps = new TextView(this);
        steps.setText("خطوات الاختبار:\n\n" +
                "1) تأكد أن NFC مفعّل وأن FlossPay هو التطبيق الافتراضي في Tap & pay\n" +
                "2) اختر البطاقة المطلوبة من المحفظة (قسم شبكتها)\n" +
                "3) اذهب إلى جهاز POS المشبوه وأدخل المبلغ المؤكد نفسه في لوحة الجهاز\n" +
                "4) قرّب الهاتف من الجهاز وأكمل المحاولة\n\n" +
                "عند رفع الهاتف تُحلَّل الجلسة فورًا: المرحلة التي وصلها الجهاز، " +
                "والمبلغ الذي أرسله فعليًا مقارنة بالمبلغ المتوقع، وأي ردود غير قياسية.\n\n" +
                "النتيجة تُسلَّم تلقائيًا للتطبيق الأساسي FlossDiag (ملف النتائج) وتُحفظ هنا أيضًا.");
        steps.setTextSize(14);
        box.addView(steps);

        Button openWallet = new Button(this);
        openWallet.setText("💳 فتح المحفظة واختيار البطاقة");
        openWallet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TestActivity.this, MainActivity.class));
            }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(12);
        openWallet.setLayoutParams(lp1);
        box.addView(openWallet);

        Button openDiag = new Button(this);
        openDiag.setText("🩺 سجل التشخيص داخل المحفظة");
        openDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TestActivity.this, DiagActivity.class));
            }
        });
        box.addView(openDiag);
    }
}
