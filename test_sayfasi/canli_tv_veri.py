"""Canli TV veri toplayici - 3 kaynak tek JSON'da.

Kaynaklar:
  1. RecTV     -> imzali REST API (m.prectv72.lol)      -> 83 canli kanal
  2. InatBox   -> AES + HMAC imzali API (diziboxen.help) -> spor/kanal listeleri
  3. CanliTV   -> m3u oynatma listesi (github raw)

60 dakikalik yerel onbellek: canli_cache.json (sunucu istek yogunlugunu onler)
"""
import base64
import hashlib
import hmac
import json
import os
import ssl
import sys
import time
import uuid
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.environ.get("TEMP", ""), "pylibs"))

DOSYA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "canli_cache.json")
TTL = 60 * 60
CTX = ssl.create_default_context()
UA_HIZLI = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

# ---------------------------------------------------------------- RecTV
RTV_B = "https://m.prectv72.lol"
RTV_SW = "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"
RTV_KEY = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"


def _rtv_get(path, timeout=25):
    """RecTV: nonce + HMAC imzali GET."""
    r0 = urllib.request.Request(RTV_B + "/api/attest/nonce", headers={"User-Agent": "googleusercontent"})
    with urllib.request.urlopen(r0, timeout=20, context=CTX) as resp:
        nonce_api = json.loads(resp.read())["nonce"]
    ts = str(int(time.time()))
    nonce = str(uuid.uuid4())
    body_hash = hashlib.sha256(b"").hexdigest()
    msg = "GET\n%s\n%s\n%s\n%s" % (path, ts, nonce, body_hash)
    sig = hmac.new(RTV_KEY.encode(), msg.encode(), hashlib.sha256).hexdigest()
    headers = {
        "User-Agent": "googleusercontent",
        "Referer": "https://twitter.com/",
        "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig,
        "X-App-Version": "141", "X-Client-Id": "rectv-android",
        "Authorization": "Bearer %s" % nonce_api,
    }
    r = urllib.request.Request(RTV_B + path, headers=headers)
    with urllib.request.urlopen(r, timeout=timeout, context=CTX) as resp:
        return resp.read().decode("utf-8", "ignore")


def rec_tv_kanallari(max_sayfa=10):
    """Tum canli kanallari topla (sayfa basina 30)."""
    sw = urllib.parse.quote(RTV_SW, safe="/")
    kanal = []
    for sayfa in range(max_sayfa):
        yol = "/api/channel/by/filtres/0/0/%d/%s/" % (sayfa, sw)
        try:
            veri = json.loads(_rtv_get(yol))
        except Exception:
            break
        if not isinstance(veri, list) or not veri:
            break
        for x in veri:
            # kaynak url liste seviyesinde degil, sources[0].url icinde (m3u8 + token)
            kaynak_url = ""
            kaynaklar = x.get("sources") or []
            if isinstance(kaynaklar, list) and kaynaklar:
                kaynak_url = (kaynaklar[0] or {}).get("url") or ""
            if not kaynak_url:
                kaynak_url = x.get("url") or x.get("enc_url") or ""
            if not kaynak_url:
                continue
            kanal.append({
                "ad": x.get("title") or "Bilinmiyor",
                "url": kaynak_url,
                "gorsel": x.get("image") or "",
                "etiket": x.get("label") or "CANLI",
                "aciklama": (x.get("description") or "")[:160],
                "kaynak": "RecTV",
            })
        if len(veri) < 30:
            break
        time.sleep(0.4)
    return kanal


# ---------------------------------------------------------------- InatBox
INAT_B = "https://diziboxen.help/CDN/001/002/dizibox/v2"
INAT_KEY = "ywevqtjrurkwtqgz"
INAT_SIGN = b"x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"


def _inat_post(path, timeout=25):
    body = ("1=%s&0=%s" % (INAT_KEY, INAT_KEY)).encode()
    ts = str(int(time.time()))
    nonce = secrets_token_hex()
    msg = "POST\n%s\n%s\n%s\n%s" % (path, ts, nonce, hashlib.sha256(body).hexdigest())
    sig = hmac.new(INAT_SIGN, msg.encode(), hashlib.sha256).hexdigest()
    headers = {
        "User-Agent": "speedrestapi",
        "Referer": "https://speedrestapi.com/",
        "X-Requested-With": "com.bp.box",
        "Cache-Control": "no-cache",
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Ts": ts, "X-Nc": nonce, "X-Sg": sig,
    }
    r = urllib.request.Request(INAT_B + path, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(r, timeout=timeout, context=CTX) as resp:
        return resp.read().decode("utf-8", "ignore")


def secrets_token_hex():
    return uuid.uuid4().hex


def _inat_coz(metin):
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad
    data = metin.strip()
    for _ in range(4):
        if data[:1] in "[{":
            if len(data) > 64 and all(c in "0123456789abcdef" for c in data[-64:]):
                data = data[:-64]
            return json.loads(data)
        if ":" in data:
            enc, keytxt = data.split(":", 1)
        else:
            enc, keytxt = data, INAT_KEY
        enc, keytxt = enc.strip(), keytxt.strip()
        try:
            k = base64.b64decode(keytxt + "=" * (-len(keytxt) % 4))
            if len(k) not in (16, 24, 32):
                k = keytxt.encode()
        except Exception:
            k = keytxt.encode()
        if len(k) not in (16, 24, 32):
            return None
        cipher = AES.new(k, AES.MODE_CBC, k[:16])
        data = unpad(cipher.decrypt(base64.b64decode(enc)), 16).decode("utf-8", "ignore").strip()
    return json.loads(data) if data[:1] in "[{" else None


def inat_kanallari():
    """Spor + kanal listelerinden canli icerikleri topla."""
    kanal = []
    basari = 0
    for yol, grup in [("/tv/list1.php", "Spor & Kanallar"),
                      ("/tv/list2.php", "Kanallar"),
                      ("/tv/sinema.php", "Sinema"),
                      ("/tv/haber.php", "Haber")]:
        try:
            veri = _inat_coz(_inat_post(yol))
        except Exception:
            continue
        if not isinstance(veri, list):
            continue
        basari += 1
        for x in veri:
            t = (x.get("chType") or "").lower()
            if not (t.startswith("live") or t.startswith("tekli") or t == "tv"):
                continue          # sadece canli akislar
            url = x.get("chUrl") or ""
            if not url.startswith("http"):
                continue
            kanal.append({
                "ad": x.get("chName") or "Bilinmiyor",
                "url": url,
                "gorsel": x.get("chImg") or "",
                "etiket": grup,
                "aciklama": "",
                "kaynak": "InatBox",
            })
        time.sleep(1.4)           # hiz limitine takilmamak icin
    if basari == 0:
        raise RuntimeError("tum kategoriler basarisiz (agengeli/403 olabilir)")
    return kanal


# ---------------------------------------------------------------- CanliTV (m3u)
M3U_URL = "https://raw.githubusercontent.com/feroxx/test/refs/heads/main/Kanallar/canlitv.m3u"


def canli_tv_kanallari():
    r = urllib.request.Request(M3U_URL, headers={"User-Agent": UA_HIZLI})
    with urllib.request.urlopen(r, timeout=40, context=CTX) as resp:
        metin = resp.read().decode("utf-8", "ignore")
    kanal, ad, logo, grup = [], None, "", ""
    for satir in metin.splitlines():
        s = satir.strip()
        if s.startswith("#EXTINF"):
            m = re.search(r'tvg-logo="([^"]*)"', s)
            logo = m.group(1) if m else ""
            ad = s.split(",")[-1].strip()
            g = re.search(r'group-title="([^"]*)"', s)
            grup = g.group(1) if g else "TV"
        elif s and not s.startswith("#") and ad:
            kanal.append({"ad": ad, "url": s, "gorsel": logo,
                          "etiket": grup, "aciklama": "", "kaynak": "CanliTV"})
            ad, logo, grup = None, "", ""
    return kanal


import re


def topla(guncelle=False):
    """Tum kaynaklari topla; 60 dk onbellek."""
    if not guncelle and os.path.exists(DOSYA):
        try:
            veri = json.load(open(DOSYA, encoding="utf-8"))
            if time.time() - veri.get("zaman", 0) < TTL:
                return veri
        except Exception:
            pass

    sonuc = {"zaman": time.time(), "kaynaklar": {}, "kanallar": []}

    for ad, fonk in [("RecTV", rec_tv_kanallari), ("InatBox", inat_kanallari), ("CanliTV", canli_tv_kanallari)]:
        bas = time.time()
        try:
            liste = fonk()
            sonuc["kaynaklar"][ad] = {"adet": len(liste), "sure": round(time.time() - bas, 1), "durum": "ok"}
            sonuc["kanallar"].extend(liste)
        except Exception as e:
            sonuc["kaynaklar"][ad] = {"adet": 0, "sure": round(time.time() - bas, 1),
                                      "durum": "hata", "mesaj": "%s: %s" % (type(e).__name__, str(e)[:80])}

    try:
        json.dump(sonuc, open(DOSYA, "w", encoding="utf-8"), ensure_ascii=False)
    except Exception:
        pass
    return sonuc


if __name__ == "__main__":
    veri = topla(guncelle=True)
    print("kaynaklar:", json.dumps(veri["kaynaklar"], ensure_ascii=False, indent=2))
    print("toplam kanal:", len(veri["kanallar"]))
