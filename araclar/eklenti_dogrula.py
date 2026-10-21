"""eklenti_dogrula.py — SAGLIK.py'nin "site ayakta" kontrolune EK olarak
CloudStream'in gercek saglayici testini yapar.

NEDEN VAR:
    1 aylik otomasyon yalnizca "mainUrl HTTP 200 donuyor mu" diye bakiyordu.
    Bu, calisan bir eklenti icin GEREKLI ama YETERLI DEGILDIR. Ornekler:
      - DDizi:  301'de POST govdesi dusuyordu, arama bos donuyordu -> 200 OK
      - DiziBox: okhttp UA'ya 403, mobil UA'ya KARTSIZ 1682 KB -> 200 OK
      - Sinewix: API kaldirilmis, sadece tanitim sayfasi -> 200 OK
    Ucu de "SORUNSUZ" gorunuyordu ama eklenti olarak calismiyorlardi.

NE YAPAR:
    CloudStream TestingUtils.kt ile ayni kurallari uygular:
      1. getMainPage(): en az bir kategori DOLU olmali
      2. search("over"/"iron"/"guy"): en az biri sonuc dondurmeli
    Sonuc: BASARILI / UYARI / BASARISIZ + kanit (kart sayisi, sure, hata).
"""

import re
import ssl
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

BASLIKLAR = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Upgrade-Insecure-Requests": "1",
}

TEST_SORGULARI = ("over", "iron", "guy")
ZAMAN_ASIMI = 25

_ctx = ssl.create_default_context()
_ctx.check_hostname = False
_ctx.verify_mode = ssl.CERT_NONE


def _al(url, veri=None, zaman_asimi=ZAMAN_ASIMI):
    """(durum, govde, sure). Hata -> (None, hata_metni, sure)."""
    basliklar = dict(BASLIKLAR)
    govde = None
    if veri is not None:
        govde = urlencode(veri).encode()
        basliklar["Content-Type"] = "application/x-www-form-urlencoded"
    istek = urllib.request.Request(url, data=govde, headers=basliklar)
    bas = time.time()
    try:
        with urllib.request.urlopen(istek, timeout=zaman_asimi, context=_ctx) as y:
            ham = y.read()
            try:
                return y.status, ham.decode("utf-8"), time.time() - bas
            except UnicodeDecodeError:
                return y.status, ham.decode("iso-8859-9", "replace"), time.time() - bas
    except urllib.error.HTTPError as e:
        return e.code, "", time.time() - bas
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, str(e)[:120]), time.time() - bas


def _kart(html, desen):
    try:
        return len(re.findall(desen, html, re.I | re.S))
    except re.error:
        return 0


# eklenti -> tanim. Seçiciler CANLI olcumle dogrulanmistir (2026-10-22).
TANIMLAR = {
    "DDizi": {
        "main": "https://www.ddizi.tel",
        "kategoriler": [("Yeni Eklenenler", "/yeni-eklenenler8")],
        "kart": r'dizi-boxpost-cat',
        "arama": ("post", "https://www.ddizi.tel/arama/", r'dizi-boxpost-cat'),
    },
    "DiziBox": {
        "main": "https://www.dizibox.live",
        "kategoriler": [("Son Bölümler", "/tum-bolumler/"),
                        ("Dizi Arşivi", "/dizi-arsivi/")],
        "kart": r'article-episode-card|detailed-article',
        "arama": ("get", "https://www.dizibox.live/", r'article-episode-card|detailed-article'),
    },
    "DiziPal": {
        "main": "https://dizipal2142.com",
        "kategoriler": [("Son Bölümler", "/bolumler"),
                        ("Yeni Diziler", "/diziler")],
        "kart": r'episode-list-item|content-card',
        "arama": ("get", "https://dizipal2142.com/arama?q=%s", r'episode-list-item|content-card'),
    },
    "DiziPalOriginal": {
        "main": "https://dizipal1822.com",
        "kategoriler": [("Son Bölümler", "/yeni-eklenen-bolumler"),
                        ("Tüm Diziler", "/diziler")],
        "kart": r'href="(?:https://dizipal1822\.com)?/dizi/[^"?]+"',
        "arama": ("get", "https://dizipal1822.com/diziler?q=%s",
                  r'href="(?:https://dizipal1822\.com)?/dizi/[^"?]+"'),
    },
    "DiziPod": {
        "main": "https://dizipod.com",
        "kategoriler": [("Son Eklenen Diziler", "/diziler/"),
                        ("Son Eklenen Filmler", "/filmler/")],
        "kart": r'href="https://dizipod\.com/(?:diziler|film)/[^/"]+/"',
        "arama": ("get", "https://dizipod.com/?s=%s",
                  r'href="https://dizipod\.com/(?:diziler|film)/[^/"]+/"'),
    },
}


def test_et(ad, tanim=None):
    """Bir eklentiyi CloudStream kurallariyla test eder."""
    t = tanim or TANIMLAR.get(ad)
    if not t:
        return {"eklenti": ad, "durum": "TEST_YOK",
                "not": "bu eklenti icin seçici tanimi yok"}

    kok = t["main"].rstrip("/")
    mainpage = []
    for kat, yol in t["kategoriler"]:
        url = yol if yol.startswith("http") else kok + yol
        d, h, s = _al(url)
        if d != 200:
            mainpage.append((kat, 0, round(s, 2), "HTTP %s %s" % (d, h[:60] if d is None else "")))
        else:
            mainpage.append((kat, _kart(h, t["kart"]), round(s, 2), None))

    arama = []
    yontem, kalip, kart_desen = t["arama"]
    for q in TEST_SORGULARI:
        if yontem == "post":
            d, h, s = _al(kalip, {"arama": q})
        else:
            url = kalip % q if "%s" in kalip else kalip
            d, h, s = _al(url)
        if d != 200:
            arama.append((q, 0, round(s, 2), "HTTP %s" % d))
        else:
            arama.append((q, _kart(h, kart_desen), round(s, 2), None))

    mp_ok = any(k > 0 for _, k, _, _ in mainpage)
    ar_ok = any(k > 0 for _, k, _, _ in arama)

    if mp_ok and ar_ok:
        durum = "BASARILI"
    elif mp_ok:
        durum = "BASARISIZ (arama sonuc vermiyor)"
    elif ar_ok:
        durum = "UYARI (ana sayfa bos, arama calisiyor)"
    else:
        durum = "BASARISIZ (ana sayfa ve arama bos)"

    return {
        "eklenti": ad, "durum": durum,
        "mainpage": mainpage, "arama": arama,
        "mainpage_toplam": sum(k for _, k, _, _ in mainpage),
        "arama_toplam": sum(k for _, k, _, _ in arama),
    }


def test_edilebilir():
    return sorted(TANIMLAR.keys())


if __name__ == "__main__":
    import sys
    hedef = sys.argv[1:] or test_edilebilir()
    for ad in hedef:
        r = test_et(ad)
        print("%-18s %-42s mainpage=%-4d arama=%d" % (
            r["eklenti"], r["durum"], r.get("mainpage_toplam", 0),
            r.get("arama_toplam", 0)))
        for kat, k, s, hata in r.get("mainpage", []):
            print("    %-24s %5.1fs %s" % (kat[:24], s, hata or "%d kart" % k))
        for q, k, s, hata in r.get("arama", []):
            print("    \"%-5s\" %15.1fs %s" % (q, s, hata or "%d sonuc" % k))
