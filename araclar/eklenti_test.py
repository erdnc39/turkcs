#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
eklenti_test.py — CloudStream "Saglayici testi" ekraninin masaustu karsiligi.

CloudStream'in TestingUtils.kt dosyasi bir saglayiciyi soyle test eder:
  1. getMainPage()  -> donen kategori listelerinden EN AZ BIRI dolu olmali
                       (bos ise: "does not have any items in a homepage row!")
  2. search("over"), search("iron"), search("guy")
                    -> EN AZ BIRI sonuc dondurmeli
                       (hepsi bossa: "did not return any search response")

Bu arac her eklentinin KENDI mantigini (URL semasi + CSS secicileri)
Python'da taklit eder ve ayni iki kurali uygular. Boylece:
  - TV'ye baglanmaya, ekran tiklamaya, 2 dakika beklemeye gerek yok
  - Sonuc metin olarak, saniyeler icinde gelir
  - Hangi kategorinin/seçicinin bozuldugu isim isim gorunur

Kullanim:
    python araclar/eklenti_test.py              # hepsi
    python araclar/eklenti_test.py DiziBox DDizi
    python araclar/eklenti_test.py --detay      # kategori bazli dokum
"""

import re
import sys
import time
import json
import argparse
import urllib.request
import urllib.error
import ssl
from concurrent.futures import ThreadPoolExecutor, as_completed

# ---------------------------------------------------------------- sabitler

# CloudStream TestingUtils.kt ile birebir ayni arama sorgulari
TEST_SORGULARI = ["over", "iron", "guy"]

# Site engellemesin diye masaustu UA. (Mobil UA bazi sitelerde bos sayfa
# donduruyor, okhttp UA dogrudan 403 yiyor - DiziBox'ta olculdu.)
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

BASLIKLAR = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
}

ZAMAN_ASIMI = 25
_ssl = ssl.create_default_context()
_ssl.check_hostname = False
_ssl.verify_mode = ssl.CERT_NONE


# ---------------------------------------------------------------- HTTP

def http_get(url, ek_baslik=None, zaman_asimi=ZAMAN_ASIMI):
    """(durum, html, sure) dondurur. Hata durumunda (None, hata_metni, sure)."""
    basliklar = dict(BASLIKLAR)
    if ek_baslik:
        basliklar.update(ek_baslik)
    istek = urllib.request.Request(url, headers=basliklar)
    bas = time.time()
    try:
        with urllib.request.urlopen(istek, timeout=zaman_asimi,
                                    context=_ssl) as y:
            ham = y.read()
            try:
                html = ham.decode("utf-8")
            except UnicodeDecodeError:
                html = ham.decode("iso-8859-9", errors="replace")
            return y.status, html, time.time() - bas
    except urllib.error.HTTPError as e:
        return e.code, "", time.time() - bas
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e), time.time() - bas


def say(html, desen):
    """re.findall ile sayim (Jsoup select yerine)."""
    try:
        return re.findall(desen, html, re.I | re.S)
    except re.error:
        return []


# ---------------------------------------------------------------- test cekirdegi

class Sonuc:
    def __init__(self, ad):
        self.ad = ad
        self.mainpage = []      # [(kategori_adi, kart_sayisi, sure, hata)]
        self.arama = []         # [(sorgu, sonuc_sayisi, sure, hata)]
        self.not_ = ""

    @property
    def verdict(self):
        """CloudStream kurallari."""
        if not self.mainpage and not self.arama:
            return "ATLANDI"
        mp_ok = any(k > 0 for _, k, _, _ in self.mainpage)
        ar_ok = any(k > 0 for _, k, _, _ in self.arama)
        if mp_ok and ar_ok:
            return "BASARILI"
        if mp_ok and not self.arama:
            return "KISMI"          # mainpage var, arama testi yok
        if mp_ok and not ar_ok:
            return "BASARISIZ"      # arama donmuyor
        if not mp_ok and ar_ok:
            return "UYARI"          # mainpage bos ama arama calisiyor
        return "BASARISIZ"


# ---------------------------------------------------------------- eklenti tanimlari
#
# Her eklenti icin: ana sayfa kategorileri (CloudStream'deki mainPage ile
# ayni), kart seçicisi, arama URL semasi ve arama sonuc seçicisi.
# Seçiciler Python re'sine cevrilmistir; Jsoup ile ayni mantik.

def _kart_say(html, secici):
    """secici: (regex, aciklama). Kart sayisini dondurur."""
    return len(say(html, secici))


EKLENTILER = {}


def kaydet(ad, main_url, kategoriler, kart_regex, arama_fn):
    EKLENTILER[ad] = {
        "mainUrl": main_url,
        "kategoriler": kategoriler,
        "kart": kart_regex,
        "arama": arama_fn,
    }


# ---- DDizi -------------------------------------------------------------
kaydet(
    ad="DDizi",
    main_url="https://www.ddizi.tel",
    kategoriler=[
        ("Yeni Eklenenler", "/yeni-eklenenler8"),
    ],
    kart_regex=r'class="[^"]*dizi-boxpost-cat[^"]*"',
    arama_fn=lambda q: (
        "post", "https://www.ddizi.tel/arama/", {"arama": q},
        r'class="[^"]*dizi-boxpost-cat[^"]*"'
    ),
)

# ---- DiziBox -----------------------------------------------------------
kaydet(
    ad="DiziBox",
    main_url="https://www.dizibox.live",
    kategoriler=[
        ("Son Bölümler", "/tum-bolumler/"),
        ("Dizi Arşivi", "/dizi-arsivi/"),
    ],
    # * IKI FARKLI SABLON: /tum-bolumler/ -> article.article-episode-card,
    # * /dizi-arsivi/ -> article.detailed-article. Site gecersiz HTML uretiyor
    # * ("<article" yalniz 15 kez geciyor ama 30 kart var), bu yuzden class
    # * adi uzerinden sayilir, etiket uzerinden degil.
    kart_regex=r'article-episode-card|detailed-article',
    arama_fn=lambda q: (
        "get", "https://www.dizibox.live/", None,
        r'article-episode-card|detailed-article'
    ),
)

# ---- DiziPal -----------------------------------------------------------
kaydet(
    ad="DiziPal",
    main_url="https://dizipal2142.com",
    # * Gercek yapilar (canli olculdu):
    # *   /bolumler -> div.episode-list-item  (24 kart), detay /bolum/<slug>
    # *   /diziler  -> div.content-card       (24 kart), detay /dizi/<slug>
    kategoriler=[
        ("Son Bölümler", "/bolumler"),
        ("Yeni Diziler", "/diziler"),
    ],
    kart_regex=r'episode-list-item|content-card',
    arama_fn=lambda q: (
        "get", "https://dizipal2142.com/arama?q=" + q, None,
        r'episode-list-item|content-card'
    ),
)

# ---- DiziPalOriginal ---------------------------------------------------
kaydet(
    ad="DiziPalOriginal",
    main_url="https://dizipal1822.com",
    kategoriler=[
        ("Son Bölümler", "/yeni-eklenen-bolumler"),
        ("Tüm Diziler", "/diziler"),
    ],
    # * Detay linkleri /dizi/<slug> ve /dizi/<slug>/1-sezon/1-bolum
    kart_regex=r'href="(?:https://dizipal1822\.com)?/dizi/[^"?]+"',
    arama_fn=lambda q: (
        "get", "https://dizipal1822.com/diziler?q=" + q, None,
        r'href="(?:https://dizipal1822\.com)?/dizi/[^"?]+"'
    ),
)

# ---- FilmMakinesi ------------------------------------------------------
kaydet(
    ad="FilmMakinesi",
    main_url="https://www.filmmakinesi.pw",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:movie|film)[-_]?(?:box|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://www.filmmakinesi.pw/?s=" + q, None,
                        r'class="[^"]*(?:movie|film)[-_]?(?:box|card|item)[^"]*"'),
)

# ---- FullHDFilmizlesene ------------------------------------------------
kaydet(
    ad="FullHDFilmizlesene",
    main_url="https://www.fullhdfilmizlesene.life",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:film|movie)[-_]?(?:box|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://www.fullhdfilmizlesene.life/arama?q=" + q, None,
                        r'class="[^"]*(?:film|movie)[-_]?(?:box|card|item)[^"]*"'),
)

# ---- HDFFilmCehennemi --------------------------------------------------
kaydet(
    ad="HDFilmCehennemi",
    main_url="https://hdfilmcehennemi.sh",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:film|movie|poster)[-_]?(?:box|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://hdfilmcehennemi.sh/?s=" + q, None,
                        r'class="[^"]*(?:film|movie|poster)[-_]?(?:box|card|item)[^"]*"'),
)

# ---- WebteIzle ---------------------------------------------------------
kaydet(
    ad="WebteIzle",
    main_url="https://webteizle.pw",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:film|movie|video)[-_]?(?:box|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://webteizle.pw/arama/" + q, None,
                        r'class="[^"]*(?:film|movie|video)[-_]?(?:box|card|item)[^"]*"'),
)

# ---- Sinewix -----------------------------------------------------------
kaydet(
    ad="Sinewix",
    main_url="https://sinewix.com",
    kategoriler=[("Son Diziler", "/public/api/genres/latestseries/all/9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA")],
    kart_regex=r'"(?:id|name|title)"\s*:',
    arama_fn=lambda q: ("get", "https://sinewix.com/public/api/search/" + q, None,
                        r'"(?:id|name|title)"\s*:'),
)

# ---- SelcukFlix --------------------------------------------------------
kaydet(
    ad="SelcukFlix",
    main_url="https://selcukflix.com",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:movie|film|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://selcukflix.com/?s=" + q, None,
                        r'class="[^"]*(?:movie|film|card|item)[^"]*"'),
)

# ---- CanliTV -----------------------------------------------------------
kaydet(
    ad="CanliTV",
    main_url="https://canlitv.com",
    kategoriler=[("Kanallar", "/")],
    kart_regex=r'class="[^"]*(?:channel|kanal|card)[^"]*"',
    arama_fn=lambda q: ("get", "https://canlitv.com/arama?q=" + q, None,
                        r'class="[^"]*(?:channel|kanal|card)[^"]*"'),
)

# ---- DiziPod -----------------------------------------------------------
kaydet(
    ad="DiziPod",
    main_url="https://dizipod.com",
    # * DIKKAT: "/" ana sayfasi zaman asimina ugruyor. Eklenti page=1 icin
    # * "/diziler/page/SAYFA/" -> "/diziler/" yapiyor; gercek kategori yolu bu.
    kategoriler=[
        ("Son Eklenen Diziler", "/diziler/"),
        ("Son Eklenen Filmler", "/filmler/"),
    ],
    kart_regex=r'href="https://dizipod\.com/(?:diziler|film)/[^/"]+/"',
    arama_fn=lambda q: (
        "get", "https://dizipod.com/?s=" + q, None,
        r'href="https://dizipod\.com/(?:diziler|film)/[^/"]+/"'
    ),
)

# ---- RecTV -------------------------------------------------------------
kaydet(
    ad="RecTV",
    main_url="https://rectv.live",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:post|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://rectv.live/?s=" + q, None,
                        r'class="[^"]*(?:post|card|item)[^"]*"'),
)

# ---- SinemaCX ----------------------------------------------------------
kaydet(
    ad="SinemaCX",
    main_url="https://sinemacx.com",
    kategoriler=[("Son Eklenenler", "/")],
    kart_regex=r'class="[^"]*(?:movie|film|card|item)[^"]*"',
    arama_fn=lambda q: ("get", "https://sinemacx.com/?s=" + q, None,
                        r'class="[^"]*(?:movie|film|card|item)[^"]*"'),
)


# ---------------------------------------------------------------- calistirici

def _posta(url, veri, zaman_asimi=ZAMAN_ASIMI):
    """application/x-www-form-urlencoded POST."""
    from urllib.parse import urlencode
    govde = urlencode(veri).encode()
    basliklar = dict(BASLIKLAR)
    basliklar["Content-Type"] = "application/x-www-form-urlencoded"
    istek = urllib.request.Request(url, data=govde, headers=basliklar)
    bas = time.time()
    try:
        with urllib.request.urlopen(istek, timeout=zaman_asimi, context=_ssl) as y:
            ham = y.read()
            try:
                return y.status, ham.decode("utf-8"), time.time() - bas
            except UnicodeDecodeError:
                return y.status, ham.decode("iso-8859-9", errors="replace"), time.time() - bas
    except urllib.error.HTTPError as e:
        return e.code, "", time.time() - bas
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e), time.time() - bas


def test_et(ad):
    tanim = EKLENTILER[ad]
    s = Sonuc(ad)
    kok = tanim["mainUrl"]

    # 1) MAIN PAGE
    for kat_adi, yol in tanim["kategoriler"]:
        url = kok.rstrip("/") + yol if yol.startswith("/") else yol
        durum, html, sure = http_get(url)
        if durum != 200:
            s.mainpage.append((kat_adi, 0, sure, "HTTP %s %s" % (durum, html[:80] if durum is None else "")))
            continue
        kart = _kart_say(html, tanim["kart"])
        s.mainpage.append((kat_adi, kart, sure, None))

    # 2) ARAMA
    for q in TEST_SORGULARI:
        yontem, url, veri, kart_regex = tanim["arama"](q)
        if yontem == "post":
            durum, html, sure = _posta(url, veri)
        else:
            durum, html, sure = http_get(url)
        if durum != 200:
            s.arama.append((q, 0, sure, "HTTP %s" % durum))
            continue
        # DiziBox gibi yerel dizin aramalari: ana sayfadan link cikar
        kart = _kart_say(html, kart_regex)
        if kart == 0 and "diziler/" in html:
            linkler = set(re.findall(r'href="[^"]*(/diziler/[^"]+)"', html))
            kart = len([l for l in linkler if q.lower() in l.lower()])
        s.arama.append((q, kart, sure, None))

    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("isimler", nargs="*", help="test edilecek eklentiler")
    ap.add_argument("--detay", action="store_true", help="kategori bazli dokum")
    ap.add_argument("--json", metavar="DOSYA", help="sonucu JSON yaz")
    a = ap.parse_args()

    hedefler = a.isimler or sorted(EKLENTILER.keys())
    bilinmeyen = [h for h in hedefler if h not in EKLENTILER]
    for h in bilinmeyen:
        print("! bilinmeyen eklenti: %s" % h)
    hedefler = [h for h in hedefler if h in EKLENTILER]

    print("CloudStream saglayici testi karsiligi — %d eklenti" % len(hedefler))
    print("=" * 78)

    sonuclar = []
    with ThreadPoolExecutor(max_workers=6) as havuz:
        gorevler = {havuz.submit(test_et, h): h for h in hedefler}
        for g in as_completed(gorevler):
            try:
                sonuclar.append(g.result())
            except Exception as e:
                s = Sonuc(gorevler[g]); s.not_ = "HATA: %s" % e
                sonuclar.append(s)

    sira = {h: i for i, h in enumerate(hedefler)}
    sonuclar.sort(key=lambda s: sira.get(s.ad, 99))

    sayac = {}
    for s in sonuclar:
        v = s.verdict
        sayac[v] = sayac.get(v, 0) + 1
        if a.detay:
            print("\n### %s  ->  %s" % (s.ad, v))
            print("  ANA SAYFA:")
            for kat, kart, sure, hata in s.mainpage:
                im = "HATA %s" % hata if hata else "%d kart" % kart
                print("    %-22s %6.2fs  %s" % (kat[:22], sure, im))
            print("  ARAMA:")
            for q, kart, sure, hata in s.arama:
                im = "HATA %s" % hata if hata else "%d sonuc" % kart
                print("    \"%-6s\" %6.2fs  %s" % (q, sure, im))
        else:
            mp = sum(k for _, k, _, _ in s.mainpage)
            ar = sum(k for _, k, _, _ in s.arama)
            print("%-22s %-11s mainpage=%-5d arama=%d" % (s.ad, v, mp, ar))

    print("=" * 78)
    for v in ["BASARILI", "UYARI", "KISMI", "BASARISIZ", "ATLANDI"]:
        if v in sayac:
            print("  %-11s %d" % (v, sayac[v]))

    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump([{
                "ad": s.ad, "verdict": s.verdict,
                "mainpage": s.mainpage, "arama": s.arama
            } for s in sonuclar], f, ensure_ascii=False, indent=1)
        print("JSON: %s" % a.json)


if __name__ == "__main__":
    main()
