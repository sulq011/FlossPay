#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pos_diag.py — خادم سجل تشخيص POS (build-9)
يستقبل جلسات التشخيص من تطبيق FlossPay (POST /api/sessions)
ويحفظها في diag_sessions.jsonl مع لوحة عرض عربية بسيطة.

تشغيل:  DIAG_PORT=8096 nohup python3 pos_diag.py > pos_diag.log 2>&1 &
"""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("DIAG_PORT", "8096"))
DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "diag_sessions.jsonl")
MAX_RECORDS = 500

STAGE_LABELS = {
    0: "لا أوامر — مشكلة RF/NFC",
    1: "بعد PPSE — AID غير مهيأ",
    2: "بعد اختيار التطبيق — تعطل kernel مبكر",
    3: "بعد GPO — لم يقرأ السجلات",
    4: "بعد قراءة السجلات — خلل برمجي كلاسيكي (توقف قبل GENERATE AC)",
    5: "مسار EMV كامل — الرفض من الشبكة (متوقع لبطاقة معمل)",
    6: "اكتمال كامل — جهاز سليم 100%",
    9: "جلسة قارئ FlossPay (مغلق)",
}


def load_records():
    if not os.path.exists(DATA_FILE):
        return []
    out = []
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


def append_record(rec):
    with open(DATA_FILE, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    # قص السجل عند تجاوز الحد
    recs = load_records()
    if len(recs) > MAX_RECORDS:
        with open(DATA_FILE, "w", encoding="utf-8") as f:
            for r in recs[-MAX_RECORDS:]:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")


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
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    break
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def log_message(self, fmt, *args):
        print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), fmt % args), flush=True)

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            recs = load_records()
            stages = {}
            for r in recs:
                stages[r.get("stageNum")] = stages.get(r.get("stageNum"), 0) + 1
            self._send(200, json.dumps({
                "status": "ok", "service": "pos_diag", "sessions": len(recs),
                "stages": stages,
            }, ensure_ascii=False))
        elif path == "/" :
            self._send(200, self.render_html(), "text/html; charset=utf-8")
        elif path == "/api/sessions":
            limit = 50
            if "?" in self.path:
                for kv in self.path.split("?")[1].split("&"):
                    if kv.startswith("limit="):
                        try:
                            limit = int(kv[6:])
                        except ValueError:
                            pass
            recs = load_records()[-limit:]
            self._send(200, json.dumps(recs, ensure_ascii=False))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    # ---------- POST ----------
    def do_POST(self):
        path = self.path.split("?")[0]
        if path != "/api/sessions":
            self._send(404, json.dumps({"error": "not found"}))
            return
        try:
            body = self._read_body().decode("utf-8")
            rec = json.loads(body)
            if not isinstance(rec, dict):
                self._send(400, json.dumps({"error": "bad payload — expected JSON object"}))
                return
            rec["received_at"] = time.time()
            append_record(rec)
            self._send(200, json.dumps({"status": "saved", "sessions": len(load_records())}, ensure_ascii=False))
        except Exception as e:
            self._send(400, json.dumps({"error": str(e)}))

    # ---------- لوحة HTML عربية ----------
    def render_html(self):
        recs = load_records()
        rows = []
        for r in reversed(recs[-100:]):
            if r.get("type") == "sadad":
                ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r.get("received_at", r.get("ts", 0) / 1000)))
                approved = r.get("approved")
                color = "#0D3B0D" if approved else "#7A4A00"
                rows.append(
                    '<div style="background:%s;color:#fff;padding:12px;margin:10px 0;border-radius:8px">'
                    '<b>%s</b> — ⚡ سداد فاتورة: %s | حساب %s | %.2f ريال | بطاقة •••• %s | %s%s'
                    "</div>" % (color, ts, r.get("company", "-"), r.get("account", "-"),
                                (r.get("amountMinor", 0) or 0) / 100.0, r.get("cardLast4", "-"),
                                "✔ " if approved else "✘ ",
                                ("كود " + r["code"]) if r.get("code") else "")
                )
                continue
            if r.get("type") == "gateway":
                ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r.get("received_at", r.get("ts", 0) / 1000)))
                amt = r.get("amountMinor", -1)
                amt_html = " — المبلغ: %.2f ريال" % (amt / 100.0) if amt is not None and amt >= 0 else ""
                code = (" — كود: %s" % r["code"]) if r.get("code") else ""
                rows.append(
                    '<div style="background:#14406B;color:#fff;padding:12px;margin:10px 0;border-radius:8px">'
                    '<b>%s</b> — 🌐 بوابة/متجر: %s | %s | %s%s%s<br><small>%s</small>'
                    "</div>" % (ts, r.get("store", "-"), r.get("network", "-"), r.get("outcome", "-"),
                                amt_html, code, (r.get("notes", "") or "")[:220])
                )
                continue
            stage_num = r.get("stageNum", -1)
            label = STAGE_LABELS.get(stage_num, r.get("stage", "-"))
            color = "#0D3B0D" if stage_num >= 5 else ("#7A4A00" if stage_num >= 2 else "#6A1010")
            ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r.get("received_at", r.get("ts", 0) / 1000)))
            amt = r.get("amountMinor", -1)
            amt_html = ""
            if amt is not None and amt >= 0:
                amt_html = " — المبلغ: %.2f ريال" % (amt / 100.0)
                exp = r.get("expectedAmount", -1)
                if exp is not None and exp >= 0:
                    amt_html += " (اختبار: %.2f%s)" % (exp / 100.0, " ✔" if r.get("amountMatch") else " ⚠️ مختلف")
                if r.get("amountMismatch"):
                    amt_html += ' <b style="color:#ffcc00">⚠️ تضارب مبالغ GPO/GEN AC</b>'
            prof = r.get("networkProfile", "")
            prof_html = ""
            if prof and prof != "-":
                sup = r.get("networkSupported")
                prof_html = " | 🛰 شبكة %s: %s" % (
                    prof, "✔ مدعومة" if sup else ("✘ غير مدعومة" if sup is not None else "غير محسومة"))
            rows.append(
                '<div style="background:%s;color:#fff;padding:12px;margin:10px 0;border-radius:8px">'
                '<b>%s</b> — مرحلة %s: %s%s%s<br><small>%s</small>'
                "</div>" % (color, ts, stage_num, label, amt_html, prof_html, (r.get("verdict", "") or "")[:220])
            )
        html = (
            "<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='utf-8'>"
            "<title>🩺 بوت تشخيص POS</title>"
            "<style>body{font-family:Tahoma,Arial,sans-serif;background:#111;color:#eee;"
            "max-width:760px;margin:24px auto;padding:0 16px}h1{font-size:22px}"
            "small{color:#ccc;line-height:1.6}</style></head><body>"
            "<h1>🩺 بوت تشخيص POS — سجل الجلسات</h1>"
            "<p>إجمالي الجلسات: <b>%d</b> (يعرض آخر 100)</p>%s"
            "</body></html>" % (len(recs), "".join(rows) or "<p>لا جلسات بعد.</p>")
        )
        return html


if __name__ == "__main__":
    os.makedirs(os.path.dirname(DATA_FILE), exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("pos_diag: listening on 0.0.0.0:%d — data: %s" % (PORT, DATA_FILE), flush=True)
    server.serve_forever()
