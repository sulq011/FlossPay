package com.floss83.pay;

import android.app.assist.AssistStructure;
import android.os.CancellationSignal;
import android.service.autofill.AutofillId;
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
    public void onFill(FillRequest request, CancellationSignal signal, final FillCallback callback) {
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
            AutofillId focusedId = request.getFocusedId();
            if (focusedId == null) {
                callback.onSuccess(null);
                return;
            }
            AssistStructure.ViewNode focused =
                    findNode(structure.getWindowNodeAt(0).getRootViewNode(), focusedId);
            if (focused == null || !isFillableCardField(focused)) {
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
                                + "تنتهي " + c.expiry);
                Dataset.Builder ds = new Dataset.Builder();
                ds.setValue(focusedId, AutofillValue.forText(c.pan), presentation);
                response.addDataset(ds.build());
                added++;
                if (added >= 6) break; // قائمة مقترحات مختصرة
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
        if (hints != null) {
            boolean card = false;
            for (String h : hints) {
                if (h == null) continue;
                String s = h.toLowerCase(Locale.US);
                if (s.contains("credit") || s.contains("card")
                        || s.contains("cc") || s.equals("pan")) {
                    card = true;
                }
            }
            if (card) return true;
            // hints أخرى (كلمة مرور/بريد...) — لسنا معنيّين بها
            return false;
        }
        // 2) حقول نصية بدون وسوم: نعرض البطاقات أيضًا (يغطي صفحات دفع WebView بلا autocomplete)
        int type = node.getInputType();
        if ((type & 0x0f) != InputType.TYPE_CLASS_TEXT) return false; // نص فقط
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) return false; // ليس كلمة مرور
        return node.getAutofillType() == View.AUTOFILL_TYPE_TEXT;
    }

    private AssistStructure.ViewNode findNode(AssistStructure.ViewNode root, AutofillId id) {
        if (root == null) return null;
        AutofillId nodeId = root.getAutofillId();
        if (nodeId != null && nodeId.equals(id)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AssistStructure.ViewNode found = findNode(root.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    @Override
    public void onSave(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();
    }
}
