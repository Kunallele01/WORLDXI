"""
Local receiver for the sofifa scrape.

The scrape runs inside the user's signed-in browser (sofifa blocks scripts
with Cloudflare), and its results are too large to read back through tool
output. The page POSTs them here instead. Listens on 127.0.0.1 only.

Chrome's Private Network Access rules require a public https page asking a
loopback address to get an explicit preflight grant, which is what the
Access-Control-Allow-Private-Network header is for.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

OUT = Path(__file__).parent / "sofifa"
OUT.mkdir(exist_ok=True)
ALLOWED_ORIGIN = "https://sofifa.com"


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Private-Network", "true")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        name = Path(self.path.strip("/")).name or "dump"
        if not name.replace("_", "").replace("-", "").isalnum():
            self.send_response(400); self._cors(); self.end_headers(); return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        data = json.loads(body)
        path = OUT / f"{name}.json"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        print(f"saved {path.name}: {len(data) if isinstance(data, list) else 'object'}", flush=True)
        self.send_response(200); self._cors(); self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    print("listening on 127.0.0.1:8765", flush=True)
    HTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
