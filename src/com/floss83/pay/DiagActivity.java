package com.floss83.pay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * شاشة سجل التشخيص (build-9): كل لمسة POS تُحفظ هنا مع تحليل البوت،
 * مع تفصيل APDU-بـ-APDU وزر رفع الجلسة إلى خادمك.
 */
public class DiagActivity extends Activity {

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

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
        title.setText("🩺 سجل تشخيص POS (البوت)");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("كل لمسة على جهاز POS تُحفظ كجلسة. افتح الجلسة لترى إلى أي مرحلة وصل الجهاز وأين تعثر. آخر 20 جلسة محفوظة.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(12));
        box.addView(hint);

        // ===== إعداد الخادم =====
        final LinearLayout hostBox = new LinearLayout(this);
        hostBox.setOrientation(LinearLayout.HORIZONTAL);
        final EditText hostEdit = new EditText(this);
        hostEdit.setText(DiagStore.getHost(this));
        hostEdit.setTextSize(13);
        hostEdit.setHint("خادم التشخيص (host:port)");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hostEdit.setLayoutParams(hp);
        Button saveHost = new Button(this);
        saveHost.setText("حفظ");
        saveHost.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DiagStore.setHost(DiagActivity.this, hostEdit.getText().toString().trim());
                Toast.makeText(DiagActivity.this, "تم حفظ الخادم", Toast.LENGTH_SHORT).show();
            }
        });
        hostBox.addView(hostEdit);
        hostBox.addView(saveHost);
        box.addView(hostBox);

        // ===== زر مسح السجل =====
        Button clear = new Button(this);
        clear.setText("🗑 مسح كل الجلسات");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences("flosspay", MODE_PRIVATE).edit().putString("diag_sessions", "[]").apply();
                Toast.makeText(DiagActivity.this, "تم المسح", Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        box.addView(clear);

        // ===== الجلسات =====
        JSONArray sessions = DiagStore.loadAll(this);
        if (sessions.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("\nلا جلسات بعد.\n\nطريقة الاستخدام:\n1) فعّل بطاقة HCE من الشاشة الرئيسية\n2) المس جهاز POS المشبوه بالهاتف\n3) ارجع هنا وافتح الجلسة الجديدة");
            empty.setTextSize(14);
            box.addView(empty);
        }

        // الأحدث أولًا
        for (int i = sessions.length() - 1; i >= 0; i--) {
            final JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            box.addView(makeSessionView(s));
        }
    }

    private View makeSessionView(final JSONObject s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        int stageNum = s.optInt("stageNum", -1);
        String stage = s.optString("stage", "-");
        int errors = s.optInt("errors", 0);
        int apduCount = s.optInt("apduCount", 0);
        long amountMinor = s.optLong("amountMinor", -1);
        boolean amountMismatch = s.optBoolean("amountMismatch", false);

        // لون حسب الحالة
        int bg;
        if (stageNum == 9) bg = 0xFF1B5E20;        // جلسة FlossPay (مغلق)
        else if (stageNum >= 5) bg = 0xFF0D3B0D;   // وصول كامل — أخضر غامق
        else if (stageNum >= 2) bg = 0xFF7A4A00;  // تعثر متوسط — بني
        else bg = 0xFF6A1010;                      // تعثر مبكر — أحمر غامق
        card.setBackgroundColor(bg);

        TextView head = new TextView(this);
        long ts = s.optLong("ts", 0);
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(ts));
        String amountTxt = amountMinor >= 0 ? String.format(java.util.Locale.US, "%.2f ريال", amountMinor / 100.0) : "بدون مبلغ";
        head.setText(when + "  |  مرحلة " + stageNum + ": " + stage + "  |  مبلغ: " + amountTxt
                + (amountMismatch ? "  ⚠️ تضارب مبالغ" : "")
                + "  |  APDU: " + apduCount + (errors > 0 ? "  |  أخطاء SW: " + errors : ""));
        head.setTextColor(Color.WHITE);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextSize(13);
        card.addView(head);

        TextView verdict = new TextView(this);
        String v = s.optString("verdict", "");
        verdict.setText(v.length() > 140 ? v.substring(0, 140) + "…" : v);
        verdict.setTextColor(0xFFDDDDDD);
        verdict.setTextSize(12);
        verdict.setPadding(0, dp(6), 0, 0);
        card.addView(verdict);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        Button open = new Button(this);
        open.setText("تفصيل APDU");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(s);
            }
        });
        actions.addView(open);

        Button up = new Button(this);
        up.setText(s.optBoolean("uploaded") ? "مرفوعة ✓" : "⬆ رفع للخادم");
        up.setEnabled(!s.optBoolean("uploaded"));
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                up.setEnabled(false);
                up.setText("…جارٍ الرفع");
                final Button btn = up;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = DiagStore.upload(DiagActivity.this, s);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    btn.setText("مرفوعة ✓");
                                    Toast.makeText(DiagActivity.this, "تم الرفع إلى الخادم", Toast.LENGTH_SHORT).show();
                                } else {
                                    btn.setEnabled(true);
                                    btn.setText("⬆ رفع للخادم");
                                    Toast.makeText(DiagActivity.this, "فشل الرفع — تأكد من الخادم أعلى الشاشة", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        actions.addView(up);

        card.addView(actions);
        return card;
    }

    private void showDetail(JSONObject s) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("تفصيل الجلسة");

        StringBuilder sb = new StringBuilder();
        sb.append("المرحلة ").append(s.optInt("stageNum", -1)).append(": ").append(s.optString("stage", "-")).append("\n");
        long amt = s.optLong("amountMinor", -1);
        if (amt >= 0) sb.append("المبلغ المطلوب: ").append(String.format(java.util.Locale.US, "%.2f ريال", amt / 100.0)).append("\n");
        if (s.optBoolean("amountMismatch")) sb.append("⚠️ تضارب مبالغ بين GPO وGENERATE AC\n");
        sb.append("\n");
        sb.append(s.optString("verdict", "")).append("\n\n——— APDU ———\n");
        JSONArray apdus = s.optJSONArray("apdus");
        if (apdus != null) {
            for (int i = 0; i < apdus.length(); i++) {
                JSONObject e = apdus.optJSONObject(i);
                if (e == null) continue;
                sb.append("#").append(i + 1).append(" CMD: ").append(e.optString("cmd"))
                  .append("\n     RSP: ").append(e.optString("resp"))
                  .append(String.format(java.util.Locale.US, "  (%.1fms)\n", e.optDouble("ms", 0)));
            }
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        b.setView(sv);
        b.setPositiveButton("إغلاق", null);
        b.show();
    }
}
