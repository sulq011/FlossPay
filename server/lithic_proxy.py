#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
lithic_proxy.py — جسر Lithic Sandbox (build-16)
================================================
يوصل تطبيق FlossDiag بمُصدر Lithic الحقيقي (sandbox) بدون كشف مفتاح API
داخل التطبيق: المفتاح يُقرأ من متغير البيئة LITHIC_API_KEY فقط
ولا يُخزَّن في أي ملف داخل المستودع.

النقاط (endpoints):
  GET  /health                 — حالة الخدمة + هل المفتاح مضبوط
  POST /api/issue_card         — {spend_limit_minor?, memo?} ⇒ بطاقة VIRTUAL كاملة (PAN/CVV/انتهاء/حالة)
  POST /api/simulate_auth      — {pan, amount_minor, descriptor} ⇒ تفويض محاكى + نتيجته (APPROVED/DECLINED + الأكواد)
  GET  /api/transactions       — آخر المعاملات (مختصرة: النتيجة/الوصف/المبلغ/الأكواد)
  GET  /api/cards              — البطاقات الصادرة (مختصرة)
  GET  /                       — لوحة HTML عربية (بطاقات + معاملات بأكواد معرّبة)

تشغيل (على خادم netcup):
  LITHIC_API_KEY=<مفتاحك> LITHIC_PORT=8097 nohup python3 lithic_proxy.py > lithic_proxy.log 2>&1 &
  # ثم إن كان ufw نشطًا: ufw allow 8097/tcp
"""
import json
import os
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("LITHIC_PORT", "8097"))
API_KEY = os.environ.get("LITHIC_API_KEY", "").strip()
BASE = "https://sandbox.lithic.com"
DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lithic_cards.jsonl")

# أكواد النتائج الشائعة — نفس قاموس LithicCodes.java في التطبيق
DECLINE_AR = {
    "APPROVED": "✔ قبول",
    "DECLINED": "✘ رفض",
    "ACCOUNT_DAILY_SPEND_LIMIT_EXCEEDED": "تجاوز الحد اليومي للحساب",
    "MALFORMED_ASA_RESPONSE": "رد ASA غير سليم (قواعد تفويض خارجية)",
    "CARD_PAUSED": "البطاقة موقوفة",
    "CARD_SPEND_LIMIT_EXCEEDED": "تجاوز حد البطاقة",
    "CARD_CLOSED": "البطاقة مغلقة",
    "CARD_EXPIRED": "منتهية الصلاحية",
    "CARD_NOT_FOUND": "PAN غير معروف للمُصدر",
    "INSUFFICIENT_FUNDS": "رصيد غير كافٍ",
    "VELOCITY_LIMIT_EXCEEDED": "تجاوز حد السرعة",
    "MCC_NOT_ALLOWED": "فئة تاجر غير مسموحة",
    "AUTH_RULE": "رفض بقاعدة تفويض مبرمجة",
    "NETWORK_DECLINE": "رفض من الشبكة",
}
STATE_AR = {"OPEN": "مفتوحة", "PAUSED": "موقوفة", "CLOSED": "مغلقة", "SUSPENDED": "معلّقة"}


def ar_code(code):
    return DECLINE_AR.get(code, "كود غير معروف: %s" % code)


def ar_state(state):
    return STATE_AR.get(state or "", state or "-")


def lithic(method, path, body=None):
    """نداء API لدى Lithic — يرجع (status, json)."""
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", API_KEY)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def condense_tx(t):
    """اختزال معاملة إلى الحقول التي يحتاجها التطبيق/اللوحة."""
    ev = (t.get("events") or [{}])[0]
    return {
        "result": t.get("result"),
        "created": t.get("created"),
        "descriptor": (t.get("merchant") or {}).get("descriptor") or t.get("descriptor") or "-",
        "amount_minor": ev.get("amount"),
        "detailed_results": ev.get("detailed_results") or [],
        "token": t.get("token"),
    }


def record_card(card):
    try:
        with open(DATA_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "token": card.get("token"),
                "pan": card.get("pan"),
                "cvv": card.get("cvv"),
                "exp_month": card.get("exp_month"),
                "exp_year": card.get("exp_year"),
                "state": card.get("state"),
                "spend_limit": card.get("spend_limit"),
                "created": card.get("created"),
            }, ensure_ascii=False) + "\n")
    except Exception:
        pass


def load_cards():
    out = []
    if not os.path.exists(DATA_FILE):
        return out
    with open(DATA_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def log_message(self, fmt, *args):
        print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), fmt % args), flush=True)

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            self._send(200, json.dumps({
                "status": "ok", "service": "lithic_proxy",
                "key_configured": bool(API_KEY), "port": PORT,
            }, ensure_ascii=False))
        elif path == "/api/transactions":
            limit = 10
            if "?" in self.path:
                for kv in self.path.split("?")[1].split("&"):
                    if kv.startswith("limit="):
                        try:
                            limit = max(1, min(50, int(kv[6:])))
                        except ValueError:
                            pass
            st, data = lithic("GET", "/v1/transactions?page_size=%d" % limit)
            if st != 200:
                self._send(502, json.dumps({"error": "lithic", "status": st, "detail": data}))
                return
            txs = [condense_tx(t) for t in (data.get("data") or [])]
            self._send(200, json.dumps({"data": txs}, ensure_ascii=False))
        elif path == "/api/cards":
            st, data = lithic("GET", "/v1/cards?page_size=50")
            if st != 200:
                self._send(502, json.dumps({"error": "lithic", "status": st, "detail": data}))
                return
            cards = [{
                "token": c.get("token"),
                "last_four": c.get("last_four"),
                "pan": c.get("pan"),
                "exp_month": c.get("exp_month"),
                "exp_year": c.get("exp_year"),
                "state": c.get("state"),
                "spend_limit": c.get("spend_limit"),
                "created": c.get("created"),
            } for c in (data.get("data") or [])]
            self._send(200, json.dumps({"data": cards}, ensure_ascii=False))
        elif path == "/":
            self._send(200, self.render_html(), "text/html; charset=utf-8")
        else:
            self._send(404, json.dumps({"error": "not found"}))

    # ---------- POST ----------
    def do_POST(self):
        path = self.path.split("?")[0]
        if not API_KEY:
            self._send(500, json.dumps({
                "error": "LITHIC_API_KEY غير مضبوط على الخادم — صدّره في بيئة التشغيل"},
                ensure_ascii=False))
            return
        try:
            req = json.loads(self._read_body().decode("utf-8") or "{}")
        except Exception as e:
            self._send(400, json.dumps({"error": str(e)}))
            return

        if path == "/api/issue_card":
            body = {"type": "VIRTUAL"}
            sl = req.get("spend_limit_minor")
            if isinstance(sl, (int, float)) and sl > 0:
                body["spend_limit"] = int(sl)
                body["spend_limit_duration"] = "TRANSACTION"
            if req.get("memo"):
                body["memo"] = str(req["memo"])[:100]
            st, card = lithic("POST", "/v1/cards", body)
            if st not in (200, 201):
                self._send(502, json.dumps({"error": "lithic", "status": st, "detail": card}))
                return
            record_card(card)
            out = dict(card)
            out["state_ar"] = ar_state(card.get("state"))
            self._send(200, json.dumps(out, ensure_ascii=False))

        elif path == "/api/simulate_auth":
            pan = str(req.get("pan") or "").strip()
            amount = req.get("amount_minor")
            descriptor = str(req.get("descriptor") or "FLOSS DIAG SHOP")[:40]
            if not pan or not isinstance(amount, (int, float)) or amount <= 0:
                self._send(400, json.dumps({"error": "pan و amount_minor مطلوبان"}, ensure_ascii=False))
                return
            st, sim = lithic("POST", "/v1/simulate/authorize", {
                "amount": int(amount), "descriptor": descriptor, "pan": pan,
            })
            if st not in (200, 201):
                self._send(502, json.dumps({"error": "lithic", "status": st, "detail": sim}))
                return
            # المحاكاة غير متزامنة: ننتظر قليلًا ثم نقرأ أحدث معاملة لنفس الوصف لاستخراج النتيجة
            result_tx = None
            for _ in range(6):
                time.sleep(0.8)
                st2, data = lithic("GET", "/v1/transactions?page_size=10")
                if st2 == 200:
                    for t in (data.get("data") or []):
                        c = condense_tx(t)
                        if c["descriptor"] == descriptor and (c["amount_minor"] or 0) == int(amount):
                            result_tx = c
                            break
                if result_tx:
                    break
            if result_tx:
                out = dict(result_tx)
                out["simulate_token"] = sim.get("token")
                self._send(200, json.dumps(out, ensure_ascii=False))
            else:
                self._send(200, json.dumps({
                    "simulate_token": sim.get("token"),
                    "pending": True,
                    "note": "أُرسلت المحاكاة ولم تظهر النتيجة بعد — جرّب قائمة المعاملات",
                }, ensure_ascii=False))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    # ---------- لوحة HTML عربية ----------
    def render_html(self):
        cards = load_cards()[-50:]
        rows = []
        for c in reversed(cards):
            rows.append(
                '<div style="background:#14406B;color:#fff;padding:12px;margin:10px 0;border-radius:8px">'
                '💳 <b>%s</b> •••• %s | CVV %s | %s/%s | %s | حد %s$'
                "</div>" % (
                    c.get("pan", "-"), (c.get("pan") or "")[-4:], c.get("cvv", "-"),
                    c.get("exp_month", "-"), c.get("exp_year", "-"),
                    ar_state(c.get("state")),
                    (c.get("spend_limit") or 0) / 100.0,
                )
            )
        st, data = lithic("GET", "/v1/transactions?page_size=20")
        tx_rows = []
        if st == 200:
            for t in (data.get("data") or []):
                c = condense_tx(t)
                ok = c["result"] == "APPROVED"
                codes = "، ".join(ar_code(x) for x in c["detailed_results"]) or "-"
                tx_rows.append(
                    '<div style="background:%s;color:#fff;padding:12px;margin:10px 0;border-radius:8px">'
                    '%s — 🧪 %s | %.2f$ | <b>%s</b> | %s'
                    "</div>" % (
                        "#0D3B0D" if ok else "#6A1010",
                        c.get("created", "-"), c.get("descriptor", "-"),
                        (c.get("amount_minor") or 0) / 100.0,
                        c.get("result", "-"), codes,
                    )
                )
        key_note = "✅ المفتاح مضبوط" if API_KEY else "⚠️ LITHIC_API_KEY غير مضبوط — التصدير: LITHIC_API_KEY=… python3 lithic_proxy.py"
        html = (
            "<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='utf-8'>"
            "<title>🏦 جسر Lithic — مُصدر حقيقي (sandbox)</title>"
            "<style>body{font-family:Tahoma,Arial,sans-serif;background:#111;color:#eee;"
            "max-width:760px;margin:24px auto;padding:0 16px}h1{font-size:22px}"
            "h2{font-size:17px;color:#8ecbff}small{color:#ccc}</style></head><body>"
            "<h1>🏦 جسر Lithic Sandbox — بطاقات من مُصدر مرخص</h1>"
            "<p>%s</p>" % key_note
        )
        html += "<h2>البطاقات الصادرة عبر الجسر (%d)</h2>%s" % (len(cards), "".join(rows) or "<p>لا بطاقات بعد.</p>")
        html += "<h2>آخر معاملات Lithic</h2>%s" % ("".join(tx_rows) or "<p>لا معاملات (أو المفتاح غير مضبوط).</p>")
        html += "</body></html>"
        return html


if __name__ == "__main__":
    if not API_KEY:
        print("⚠️ تنبيه: LITHIC_API_KEY غير مضبوط — ستعمل اللوحة لكن نداءات API سترفض", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("lithic_proxy: listening on 0.0.0.0:%d → %s" % (PORT, BASE), flush=True)
    server.serve_forever()
