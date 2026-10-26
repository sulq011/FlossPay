package com.floss83.pay;

import android.app.assist.AssistStructure;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * خدمة ملء البطاقات التلقائي (Android Autofill) — Flossx83 Lab.
 *
 * عند الضغط على حقل بطاقة في أي تطبيق/صفحة دفع، تظهر بطاقات المحفظة
 * كاقتراحات من النظام، ويُملأ الحقل برقم البطاقة المختارة (PAN).
 *
 * ملاحظة أمان: هذا تطبيق معمل — الـ PAN محفوظ محليًا فقط.
 */
public class FlossAutofillService extends AutofillService {

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal signal, final FillCallback callback) {
        try {
            List<FillContext> contexts = request.getFillContexts();
            if (contexts == null || contexts.isEmpty()) {
                callback.onSuccess(null);
                return;
            }
            AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
            if (structure == null || structure.getWindowNodeCount() == 0) {
                callback.onSuccess(null);
                return;
            }

            // الحقل المركّز (الذي ضغط عليه المستخدم) — آخر عقدة focused في الشاشة
            AssistStructure.ViewNode focused = null;
            for (int w = 0; w < structure.getWindowNodeCount(); w++) {
                AssistStructure.ViewNode f = findFocused(structure.getWindowNodeAt(w).getRootViewNode());
                if (f != null) focused = f;
            }
            if (focused == null) {
                callback.onSuccess(null);
                return;
            }
            AutofillId focusedId = focused.getAutofillId();
            if (focusedId == null || !isFillableCardField(focused)) {
                callback.onSuccess(null);
                return;
            }

            CardStore store = new CardStore(this);
            List<Card> cards = store.load();
            if (cards.isEmpty()) {
                callback.onSuccess(null);
                return;
            }

            FillResponse.Builder response = new FillResponse.Builder();
            int added = 0;
            for (Card c : cards) {
                RemoteViews presentation = new RemoteViews(getPackageName(), R.layout.autofill_card);
                presentation.setTextViewText(R.id.af_title,
                        Util.brand(c.pan) + "  " + Util.mask(c.pan));
                presentation.setTextViewText(R.id.af_sub,
                        (c.holder == null || c.holder.isEmpty() ? "" : c.holder + "  •  ")
                                + "\u062A\u0646\u062A\u0647\u064A " + c.expiry);
                Dataset.Builder ds = new Dataset.Builder();
                ds.setValue(focusedId, AutofillValue.forText(c.pan), presentation);
                response.addDataset(ds.build());
                added++;
                if (added >= 6) break; // قائمة اقتراحات مختصرة
            }
            callback.onSuccess(added == 0 ? null : response.build());
        } catch (Throwable t) {
            try { callback.onSuccess(null); } catch (Throwable ignored) { }
        }
    }

    /** هل هذا الحقل حقل بطاقة (أو حقل نصي عام يمكن أن يكون حقل بطاقة)؟ */
    private boolean isFillableCardField(AssistStructure.ViewNode node) {
        // 1) حقل معلّم صراحة كحقل بطاقة (Android autofill hints / HTML autocomplete)
        String[] hints = node.getAutofillHints();
        if (hints != null && hints.length > 0) {
            for (String h : hints) {
                if (h == null) continue;
                String s = h.toLowerCase(Locale.US);
                if (s.contains("credit") || s.contains("card")
                        || s.contains("cc") || s.equals("pan")) {
                    return true;
                }
            }
            return false;
        }
        // 2) حقل نصي بدون وسوم: نعرض البطاقات أيضًا (يغطي صفحات دفع WebView بلا autocomplete)
        int type = node.getInputType();
        if ((type & 0x0f) != InputType.TYPE_CLASS_TEXT) return false; // نص فقط
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) return false; // ليس كلمة مرور
        return node.getAutofillType() == View.AUTOFILL_TYPE_TEXT;
    }

    private AssistStructure.ViewNode findFocused(AssistStructure.ViewNode node) {
        if (node == null) return null;
        AssistStructure.ViewNode found = null;
        if (node.isFocused()) found = node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AssistStructure.ViewNode f = findFocused(node.getChildAt(i));
            if (f != null) found = f;
        }
        return found;
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        // لا نطلب حفظ بيانات من التطبيقات الأخرى (المحفظة هي المصدر)
    }
}
