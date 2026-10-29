"""Slack incoming webhook 모의 수신기. Alertmanager 경보 경로를 실제 Slack 없이 시험할 때 쓴다.

MOCK_MODE로 응답을 정한다. 실행 중에는 POST /mode/<mode>로 바꿀 수 있다.
  ok    200 "ok" (Slack 정상 응답)
  429   429 Retry-After: 1 (Slack 전송 제한)
  500   500 (Slack 장애)
  slow  MOCK_SLOW_SECONDS(기본 30초) 뒤 200
받은 본문은 표준 출력에 남기고 GET /messages로 최근 MOCK_KEEP(기본 200)개를 돌려준다. DELETE /messages로 비운다.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODES = {"ok", "429", "500", "slow"}
state = {"mode": os.environ.get("MOCK_MODE", "ok")}
messages = []
lock = threading.Lock()
SLOW_SECONDS = float(os.environ.get("MOCK_SLOW_SECONDS", "30"))
KEEP = int(os.environ.get("MOCK_KEEP", "200"))


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="text/plain", headers=None):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/messages":
            with lock:
                body = json.dumps({"mode": state["mode"], "count": len(messages), "messages": messages}, ensure_ascii=False)
            self._send(200, body, "application/json; charset=utf-8")
        elif self.path == "/health":
            self._send(200, "ok")
        else:
            self._send(404, "not found")

    def do_DELETE(self):
        if self.path == "/messages":
            with lock:
                messages.clear()
            self._send(200, "cleared")
        else:
            self._send(404, "not found")

    def do_POST(self):
        if self.path.startswith("/mode/"):
            mode = self.path.removeprefix("/mode/")
            if mode not in MODES:
                self._send(400, "mode: " + ", ".join(sorted(MODES)))
                return
            state["mode"] = mode
            self._send(200, mode)
            return

        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = {"raw": raw.decode("utf-8", "replace")}
        mode = state["mode"]
        with lock:
            messages.append({"receivedAt": time.time(), "mode": mode, "payload": payload})
            del messages[:-KEEP]
        print(json.dumps({"mode": mode, "payload": payload}, ensure_ascii=False), flush=True)

        if mode == "429":
            self._send(429, "rate_limited", headers={"Retry-After": "1"})
        elif mode == "500":
            self._send(500, "internal_error")
        else:
            if mode == "slow":
                time.sleep(SLOW_SECONDS)
            self._send(200, "ok")

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("MOCK_PORT", "8080"))
    print(f"alert-mock listening on {port}, mode={state['mode']}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
