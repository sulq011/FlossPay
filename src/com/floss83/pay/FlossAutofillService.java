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
 * خدمة ملء البطاقات التلقائي (Android Autofill) — Flossx83 Lab — v2.
 *
 * عند الضغط على حقل بطاقة في أي تطبيق/صفحة دفع تظهر بطاقات المحفظة
 * كاقتراحات نظامية، ويُملأ النموذج كاملًا:
 * رقم البطاقة + تاريخ الانتهاء + CVC + الاسم + عنوان الفاتورة (ص.ب/مدينة/رمز بريدي/دولة).
 *
 * التصنيف يعتمد على وسوم Android autofill hints (التي ينقلها Chromium من HTML autocomplete).
 */
public class FlossAutofillService extends AutofillService {

    /** الحقول المكتشفة في الشاشة (قد يكون بعضها null). */
    private static class Fields {
        AutofillId number, expiry, expMonth, expYear, cvv, name, street, city, postal, country;
    }

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

            // الحقل المركّز (الذي ضغط عليه المستخدم)
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
            if (focusedId == null) {
                callback.onSuccess(null);
                return;
            }

            // تصنيف كل حقول الشاشة حسب الوسوم
            Fields fields = new Fields();
            for (int w = 0; w < structure.getWindowNodeCount(); w++) {
                classifyTree(structure.getWindowNodeAt(w).getRootViewNode(), fields);
            }

            boolean cardContext = fields.number != null || fields.cvv != null
                    || fields.expiry != null || fields.expMonth != null || fields.expYear != null;
            boolean focusedIsCard = isCardHint(focused) || focusedMatches(fields, focusedId);

            if (!focusedIsCard) {
                if (hasNonCardHints(focused)) {
                    callback.onSuccess(null); // حقل معلّم بنوع آخر (كلمة مرور/بريد…) — لا نتدخل
                    return;
                }
                if (!cardContext && !isPlainTextField(focused)) {
                    callback.onSuccess(null);
                    return;
                }
                // حقل نصي بلا وسوم: ملء الرقم فقط (وضع الرجوع)
                if (!cardContext) {
                    respondOnlyNumber(callback, focusedId);
                    return;
                }
                // يوجد سياق بطاقة والتركيز على حقل نصي غير مصنّف: نملأ الرقم فيه
                respondFull(callback, fields, focusedId);
                return;
            }
            respondFull(callback, fields, focusedId);
        } catch (Throwable t) {
            try { callback.onSuccess(null); } catch (Throwable ignored) { }
        }
    }

    /** بناء اقتراحات البطاقات وملء كل الحقول المكتشفة. */
    private void respondFull(FillCallback callback, Fields fields, AutofillId focusedId) {
        CardStore store = new CardStore(this);
        List<Card> cards = store.load();
        if (cards.isEmpty()) {
            callback.onSuccess(null);
            return;
        }
        FillResponse.Builder response = new FillResponse.Builder();
        int added = 0;
        for (Card c : cards) {
            RemoteViews pres = presentation(c);
            Dataset.Builder ds = new Dataset.Builder();
            boolean focusIncluded = false;

            if (fields.number != null) {
                ds.setValue(fields.number, AutofillValue.forText(c.pan), pres);
                focusIncluded |= fields.number.equals(focusedId);
            }
            if (fields.expiry != null && notEmpty(c.expiry)) {
                ds.setValue(fields.expiry, AutofillValue.forText(c.expiry), pres);
                focusIncluded |= fields.expiry.equals(focusedId);
            }
            if (fields.expMonth != null && c.expiry != null && c.expiry.length() >= 2) {
                ds.setValue(fields.expMonth, AutofillValue.forText(c.expiry.substring(0, 2)), pres);
                focusIncluded |= fields.expMonth.equals(focusedId);
            }
            if (fields.expYear != null && c.expiry != null && c.expiry.length() >= 5) {
                ds.setValue(fields.expYear, AutofillValue.forText(c.expiry.substring(3, 5)), pres);
                focusIncluded |= fields.expYear.equals(focusedId);
            }
            if (fields.cvv != null && notEmpty(c.cvv)) {
                ds.setValue(fields.cvv, AutofillValue.forText(c.cvv), pres);
                focusIncluded |= fields.cvv.equals(focusedId);
            }
            if (fields.name != null && notEmpty(c.holder)) {
                ds.setValue(fields.name, AutofillValue.forText(c.holder), pres);
                focusIncluded |= fields.name.equals(focusedId);
            }
            if (fields.street != null && notEmpty(c.addrStreet)) {
                ds.setValue(fields.street, AutofillValue.forText(c.addrStreet), pres);
                focusIncluded |= fields.street.equals(focusedId);
            }
            if (fields.city != null && notEmpty(c.addrCity)) {
                ds.setValue(fields.city, AutofillValue.forText(c.addrCity), pres);
                focusIncluded |= fields.city.equals(focusedId);
            }
            if (fields.postal != null && notEmpty(c.addrPostcode)) {
                ds.setValue(fields.postal, AutofillValue.forText(c.addrPostcode), pres);
                focusIncluded |= fields.postal.equals(focusedId);
            }
            if (fields.country != null && notEmpty(c.addrCountry)) {
                ds.setValue(fields.country, AutofillValue.forText(c.addrCountry), pres);
                focusIncluded |= fields.country.equals(focusedId);
            }
            if (!focusIncluded) {
                // اقتراح لا يحتوي الحقل المركّز لن يظهر — نضمن إدراجه
                ds.setValue(focusedId, AutofillValue.forText(c.pan), pres);
            }
            response.addDataset(ds.build());
            added++;
            if (added >= 6) break;
        }
        callback.onSuccess(added == 0 ? null : response.build());
    }

    /** وضع الرجوع: ملء رقم البطاقة فقط في الحقل المركّز. */
    private void respondOnlyNumber(FillCallback callback, AutofillId focusedId) {
        CardStore store = new CardStore(this);
        List<Card> cards = store.load();
        if (cards.isEmpty()) {
            callback.onSuccess(null);
            return;
        }
        FillResponse.Builder response = new FillResponse.Builder();
        int added = 0;
        for (Card c : cards) {
            Dataset.Builder ds = new Dataset.Builder();
            ds.setValue(focusedId, AutofillValue.forText(c.pan), presentation(c));
            response.addDataset(ds.build());
            added++;
            if (added >= 6) break;
        }
        callback.onSuccess(added == 0 ? null : response.build());
    }

    private RemoteViews presentation(Card c) {
        RemoteViews pres = new RemoteViews(getPackageName(), R.layout.autofill_card);
        pres.setTextViewText(R.id.af_title, Util.brand(c.pan) + "  " + Util.mask(c.pan));
        pres.setTextViewText(R.id.af_sub,
                (c.holder == null || c.holder.isEmpty() ? "" : c.holder + "  •  ")
                        + "\u0628\u064A\u0627\u0646\u0627\u062A \u0643\u0627\u0645\u0644\u0629 (\u0631\u0642\u0645/\u062A\u0627\u0631\u064A\u062E/CVC/\u0627\u0644\u0639\u0646\u0648\u0627\u0646)");
        return pres;
    }

    /** فحص الحقل المركّز هل هو حقل بطاقة مباشر. */
    private boolean isCardHint(AssistStructure.ViewNode node) {
        String[] hints = node.getAutofillHints();
        if (hints == null) return false;
        for (String h : hints) {
            if (h == null) continue;
            String s = h.toLowerCase(Locale.US);
            if (s.contains("card") || s.contains("cc") || s.contains("pan")
                    || s.contains("exp") || s.contains("cvv") || s.contains("cvc")
                    || s.contains("csc") || s.contains("security")) {
                return true;
            }
        }
        return false;
    }

    private boolean focusedMatches(Fields fields, AutofillId focusedId) {
        return equalsId(fields.name, focusedId) || equalsId(fields.street, focusedId)
                || equalsId(fields.city, focusedId) || equalsId(fields.postal, focusedId)
                || equalsId(fields.country, focusedId);
    }

    private boolean equalsId(AutofillId a, AutofillId b) {
        return a != null && b != null && a.equals(b);
    }

    /** هل للحقل وسوم من نوع آخر (كلمة مرور/بريد/هاتف) لا تعنينا؟ */
    private boolean hasNonCardHints(AssistStructure.ViewNode node) {
        String[] hints = node.getAutofillHints();
        if (hints == null || hints.length == 0) return false;
        for (String h : hints) {
            if (h == null) continue;
            String s = h.toLowerCase(Locale.US);
            if (s.contains("password") || s.contains("email") || s.contains("phone")
                    || s.contains("username") || s.contains("otp") || s.contains("one-time")) {
                return true;
            }
        }
        return false;
    }

    /** حقل نصي عادي بلا وسوم (وضع الرجوع). */
    private boolean isPlainTextField(AssistStructure.ViewNode node) {
        int type = node.getInputType();
        if ((type & 0x0f) != InputType.TYPE_CLASS_TEXT) return false;
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) return false;
        return node.getAutofillType() == View.AUTOFILL_TYPE_TEXT;
    }

    /** المشي على شجرة الواجهة وتصنيف الحقول حسب الوسوم. */
    private void classifyTree(AssistStructure.ViewNode node, Fields f) {
        if (node == null) return;
        AutofillId id = node.getAutofillId();
        String[] hints = node.getAutofillHints();
        if (id != null && hints != null) {
            for (String h : hints) {
                if (h == null) continue;
                classifyHint(h.toLowerCase(Locale.US), id, f);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            classifyTree(node.getChildAt(i), f);
        }
    }

    private void classifyHint(String s, AutofillId id, Fields f) {
        if (s.contains("exp")) {                    // cc-exp / creditCardExpiration*
            if (s.contains("month")) {
                if (f.expMonth == null) f.expMonth = id;
            } else if (s.contains("year")) {
                if (f.expYear == null) f.expYear = id;
            } else {
                if (f.expiry == null) f.expiry = id;
            }
            return;
        }
        if (s.contains("cvv") || s.contains("cvc") || s.contains("csc")
                || s.contains("security") || s.contains("verification")) {
            if (f.cvv == null) f.cvv = id;
            return;
        }
        if ((s.contains("name") || s.contains("holder")) && !s.contains("user") && !s.contains("nick")) {
            if (f.name == null) f.name = id;
            return;
        }
        if (s.contains("card") || s.contains("cc") || s.equals("pan")) {
            if (f.number == null) f.number = id;
            return;
        }
        if (s.contains("postal") || s.contains("zip")) {
            if (f.postal == null) f.postal = id;
            return;
        }
        if (s.contains("city") || s.contains("address-level2")) {
            if (f.city == null) f.city = id;
            return;
        }
        if (s.contains("street") || s.contains("address-line") || s.contains("address1")
                || s.contains("pobox") || s.contains("po-box") || s.contains("street-address")) {
            if (f.street == null) f.street = id;
            return;
        }
        if (s.contains("country")) {
            if (f.country == null) f.country = id;
            return;
        }
        if (s.contains("billing")) { // وسم عام لعنوان الفاتورة — يُستخدم كسطر عنوان إذا لم يوجد أدق
            if (f.street == null) f.street = id;
        }
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

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        // لا نطلب حفظ بيانات من التطبيقات الأخرى (المحفظة هي المصدر)
    }
}
