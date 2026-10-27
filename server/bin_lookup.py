#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bin_lookup.py — أداة استعلام BIN الرسمي (Mastercard BIN Lookup API) لمعمل Flossx83.
المصدر الرسمي: github.com/Mastercard-Samples/bin-lookup-sample-code (Examples/single-lookup-example.py).

🎯 الغاية التشخيصية:
    هذا الـ API هو نفسه ما تفعله بوابات الدفع عند رفض البطاقات المعملية:
    استعلام «أول 8 أرقام من البطاقة» مقابل سجل نطاقات الحسابات (90k+) لدى الشبكة.
    - بطاقة معملية (مثال: 40000012) → لا نطاق مطابق = دليل موثّق أن الرفض يحدث عند باب الـ BIN lookup.
    - بطاقة حقيقية → نطاق معروف (الشبكة/المُصدِر/المنتج).
    الاتجاه واحد فقط: قراءة. لا يمكن لهذه الأداة «تعريف» بطاقة بحساب بنكي —
    التعريف الوحيد يأتي من بنك مرخّص يُصدر BIN حقيقي مسجّل لدى الشبكة.

الاستخدام (على الخادم):
    pip install mastercard-oauth1-signer requests
    python3 bin_lookup.py 40000012 42424242        # استعلام عدة BINs دفعة واحدة
    python3 bin_lookup.py                          # وضع تفاعلي (مثل العينة الرسمية)
    python3 bin_lookup.py --lab                     # بطاقة المعمل + مقارنات معروفة

الإعداد (أحدهما يكفي):
    1) ملف bin_lookup_config.json بجانب السكربت (انسخه من bin_lookup_config.example.json)
    2) متغيرات البيئة: MC_BASE_URL / MC_CONSUMER_KEY / MC_P12_PATH / MC_P12_PASS

الحصول على المفاتيح: حساب مجاني على developer.mastercard.com ← إنشاء مشروع BIN Lookup
← تنزيل sandbox.p12 + Consumer Key من لوحة المشروع.
"""

import json
import os
import sys

LAB_BINS = {
    "40000012": "البطاقة المعملية في المحفظة (PAN ‎4000001234567899) — المتوقع: لا نطاق مطابق",
    "42424242": "بطاقة Visa اختبارية قياسية — للمقارنة",
    "54133300": "نطاق MasterCard معروف تاريخيًا — للمقارنة",
    "22210000": "MasterCard السلسلة الجديدة (2-series) — للمقارنة",
}

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def die(msg):
    print("❌ " + msg)
    sys.exit(1)


def load_config():
    """الإعداد: متغيرات البيئة تتفوق على bin_lookup_config.json"""
    cfg = {}
    jpath = os.path.join(SCRIPT_DIR, "bin_lookup_config.json")
    if os.path.exists(jpath):
        try:
            with open(jpath, encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception as e:
            die("تعذّرت قراءة bin_lookup_config.json: %s" % e)

    def pick(env_key, json_key):
        v = os.environ.get(env_key)
        return v if v else cfg.get(json_key)

    return {
        "base_url": pick("MC_BASE_URL", "base_url"),
        "consumer_key": pick("MC_CONSUMER_KEY", "consumer_key"),
        "p12_path": pick("MC_P12_PATH", "p12_path"),
        "p12_pass": pick("MC_P12_PASS", "p12_pass"),
    }


def build_signer(cfg):
    """محاكاة العينة الرسمية: OAuth1 بشهادة p12"""
    try:
        import requests  # noqa: F401
        from requests.auth import AuthBase
        import oauth1.authenticationutils as authenticationutils
        from oauth1.signer import OAuthSigner
    except ImportError:
        die(
            "المكتبات غير مثبتة. شغّل أولًا:\n"
            "    pip install mastercard-oauth1-signer requests"
        )

    missing = [k for k in ("base_url", "consumer_key", "p12_path", "p12_pass") if not cfg.get(k)]
    if missing:
        die(
            "الإعداد ناقص: %s\n"
            "الحل: أنشئ bin_lookup_config.json بجانب السكربت (راجع bin_lookup_config.example.json)\n"
            "أو صدّر المتغيرات: MC_BASE_URL, MC_CONSUMER_KEY, MC_P12_PATH, MC_P12_PASS\n"
            "المفاتيح تُؤخذ من مشروعك على developer.mastercard.com (تنزيل sandbox.p12 + Consumer Key)"
            % ", ".join(missing)
        )

    class MCSigner(AuthBase):
        def __init__(self, consumer_key, signing_key):
            self.signer = OAuthSigner(consumer_key, signing_key)

        def __call__(self, request):
            self.signer.sign_request(request.url, request)
            return request

    p12 = cfg["p12_path"]
    if not os.path.isabs(p12):
        p12 = os.path.join(SCRIPT_DIR, p12)
    if not os.path.exists(p12):
        die("شهادة p12 غير موجودة في: %s" % p12)

    signing_key = authenticationutils.load_signing_key(p12, cfg["p12_pass"])
    return cfg["base_url"].rstrip("/"), MCSigner(cfg["consumer_key"], signing_key)


def lookup_bin(base_url, signer, bin8, timeout=20):
    """استعلام نطاق واحد — نفس نداء العينة الرسمية"""
    import requests

    resp = requests.post(
        base_url + "/bin-ranges/account-searches",
        auth=signer,
        json={"accountRange": bin8},
        timeout=timeout,
    )
    if resp.status_code != 200:
        return {"_http_status": resp.status_code, "_body": resp.text[:500]}
    try:
        return resp.json()
    except ValueError:
        return {"_http_status": resp.status_code, "_body": resp.text[:500]}


def render(bin8, result):
    """عرض النتيجة + حكم تشخيصي بالعربية"""
    label = LAB_BINS.get(bin8, "")
    print()
    print("=" * 62)
    print("🔎 BIN: %s%s" % (bin8, ("  — " + label) if label else ""))
    print("=" * 62)

    status = result.get("_http_status")
    if status:
        print("⚠️ فشل النداء (HTTP %s)" % status)
        print(result.get("_body", ""))
        if status in (401, 403):
            print("   تلميح: تحقق من Consumer Key وشهادة p12 وكلمة مرورها (keystore password).")
        return

    # طباعة الرد الخام كاملًا للتوثيق
    print(json.dumps(result, ensure_ascii=False, indent=2))

    data = result if isinstance(result, list) else result.get("accountRanges") or result.get("ranges") or []
    if isinstance(data, dict):
        data = [data]
    if not data:
        print()
        print("❓ الحكم التشخيصي: لا يوجد نطاق مطابق لهذا الـ BIN لدى الشبكة.")
        print("   البطاقة غير معروفة للمُصدّرين — أي بوابة دفع سترفضها عند فحص الـ BIN،")
        print("   وهذا بالضبط نمط «لا يتم التعرف على العملية» في المتاجر. عطل متوقع وليس خللًا.")
    else:
        print()
        print("✅ الحكم التشخيصي: الـ BIN ضمن نطاق معروف (%d نطاق/نطاقات مطابقة)." % len(data))
        print("   بطاقات هذا النطاق تُقبل عند فحص الـ BIN (القرار بعد ذلك للمُصدِر والرصيد و3DS).")


def main():
    args = sys.argv[1:]
    bins = []
    if args and args[0] == "--lab":
        bins = list(LAB_BINS.keys())
    else:
        for a in args:
            b = "".join(ch for ch in a if ch.isdigit())[:8]
            if len(b) != 8:
                die("BIN غير صالح: %s (المطلوب 8 أرقام)" % a)
            bins.append(b)
        if not bins:
            print("أدخل BIN من 8 أرقام (Enter للخروج):")
            line = input().strip()
            if not line:
                return
            bins = ["".join(ch for ch in line if ch.isdigit())[:8]]

    cfg = load_config()
    base_url, signer = build_signer(cfg)

    print("🌐 الهدف: %s/bin-ranges/account-searches" % base_url)
    for b in bins:
        try:
            render(b, lookup_bin(base_url, signer, b))
        except Exception as e:
            print("❌ فشل استعلام %s: %s" % (b, e))


if __name__ == "__main__":
    main()
