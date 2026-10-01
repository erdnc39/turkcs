"""Test sunucusu: statik dosyalar + Termux icin komut/sonuc kanali.

GET  /            -> test sayfasi
GET  /<dosya>     -> statik dosya (APK dahil)
GET  /komut       -> kuyruktaki komutu verir ve kuyrugu temizler (tek seferlik)
POST /sonuc       -> gelen govdeyi _sonuc.txt icine yazar (ben okuyorum)
"""
import os
import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

D = os.path.dirname(os.path.abspath(__file__))
KOMUT = os.path.join(D, "_komut.txt")
SONUC = os.path.join(D, "_sonuc.txt")


class El(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        print("%s - %s" % (self.address_string(), fmt % a), flush=True)

    def _gonder(self, kod, govde, tip="text/plain; charset=utf-8"):
        b = govde if isinstance(govde, bytes) else govde.encode("utf-8")
        self.send_response(kod)
        self.send_header("Content-Type", tip)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        yol = self.path.split("?")[0]
        if yol == "/komut":
            if os.path.exists(KOMUT):
                with open(KOMUT, encoding="utf-8") as f:
                    metin = f.read().strip()
                os.remove(KOMUT)  # tek seferlik teslim
                self._gonder(200, metin or "bos")
            else:
                self._gonder(200, "bos")
            return
        if yol in ("/", ""):
            yol = "/index.html"
        rel = yol.lstrip("/")
        fp = os.path.normpath(os.path.join(D, rel))
        if not fp.startswith(D):
            self._gonder(403, "yok")
            return
        if not os.path.isfile(fp):
            self._gonder(404, "yok")
            return
        tip = "application/octet-stream"
        if fp.endswith(".html"):
            tip = "text/html; charset=utf-8"
        elif fp.endswith(".apk"):
            tip = "application/vnd.android.package-archive"
        elif fp.endswith(".css"):
            tip = "text/css"
        elif fp.endswith(".js"):
            tip = "application/javascript"
        with open(fp, "rb") as f:
            veri = f.read()

        # TV indiricileri bolumlu (Range) istek atar; desteklenmezse dosya yarim kalir
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            try:
                p = rng[6:].split(",")[0].split("-")
                bas = int(p[0]) if p[0] else 0
                son = int(p[1]) if len(p) > 1 and p[1] else len(veri) - 1
                son = min(son, len(veri) - 1)
                if bas <= son:
                    parca = veri[bas:son + 1]
                    self.send_response(206)
                    self.send_header("Content-Type", tip)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Range", "bytes %d-%d/%d" % (bas, son, len(veri)))
                    self.send_header("Content-Length", str(len(parca)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(parca)
                    return
            except Exception:
                pass
        self._gonder(200, veri, tip)

    def do_HEAD(self):
        # indiriciler dosyayi once HEAD ile sorar
        yol = self.path.split("?")[0]
        if yol in ("/", ""):
            yol = "/index.html"
        fp = os.path.normpath(os.path.join(D, yol.lstrip("/")))
        if fp.startswith(D) and os.path.isfile(fp):
            boyut = os.path.getsize(fp)
            self.send_response(200)
            self.send_header("Content-Length", str(boyut))
            self.send_header("Content-Type", "application/octet-stream")
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        govde = self.rfile.read(n) if n else b""
        yol = self.path.split("?")[0]
        if yol in ("/sonuc", "/rapor"):
            damga = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            with open(os.path.join(D, "_sonuc_%s.txt" % damga), "wb") as f:
                f.write(govde)
            with open(SONUC, "wb") as f:
                f.write(govde)
            print("== SONUC ALINDI (%d bayt) ==" % len(govde), flush=True)
            self._gonder(200, "tamam")
            return
        self._gonder(404, "yok")


if __name__ == "__main__":
    print("Test sunucusu 0.0.0.0:8000 ->", D, flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8000), El).serve_forever()
