package com.floss83.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * مستقبل نتائج الاختبار من المحفظة (com.floss83.pay):
 * كل جلسة POS تُسلَّم هنا بعد لمسة الجهاز مباشرة، وتُلحق بملف النتائج.
 */
public class DiagReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String session = intent.getStringExtra("session");
        if (session != null && session.length() > 2) {
            DiagFileStore.append(ctx, session);
        }
    }
}
