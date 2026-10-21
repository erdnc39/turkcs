# -*- coding: utf-8 -*-
"""tarayici_test.py — 403 / Cloudflare / login duvari olan eklentiler icin
GERCEK tarayici ile test.

NEDEN:
    requests/urllib ile bu siteler 403 donuyor ya da login kapisina
    yonlendiriyor. Gercek Chrome ise geciyor; cunku TLS parmak izi, cerez,
    JS calistirma ve HTTP/2 davranisi gercek tarayici gibi.

    Kurallar CloudStream ile ayni:
      1. ana sayfada kart VAR MI
      2. arama sonuc VERIYOR MU

KULLANIM:
    python araclar\\tarayici_test.py                 # tum duvarli eklentiler
    python araclar\\tarayici_test.py FilmMakinesi
    python araclar\\tarayici_test.py --gorunur        # tarayiciyi GOR (varsayilan)
    python araclar\\tarayici_test.py --gizli          # headless

    Tarayici GORUNUR acilir. Cloudflare "insan misin" derse sen cozersin,
    betik 120 sn bekler, sonra devam eder.
"""

import asyncio
import json
import os
import re
import sys
import time

# * Windows konsolu (cp1254) bazi sitelerin basligindaki ozel karakterleri
# * basamiyor -> UnicodeEncodeError ile betik cokuyordu. UTF-8'e zorla.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from playwright.async_api import async_playwright

# DSH'nin kullandigi gercek Chrome profili. Cerezler/oturum burada durur.
DSH_PROFIL = os.path.join(
    os.environ.get("LOCALAPPDATA", os.path.expanduser("~\\AppData\\Local")),
    "ms-playwright", "mcp-chrome-4a3fd07")

TEST_SORGULARI = ("over", "iron", "guy")
MAKSA_BEKLEME = 120_000     # dogrulama icin toplam bekleme (ms)

# 403 / login duvari olan eklentiler. Seçiciler canli olcum yerine
# GENEL tutuldu: once kart adayi ara, yoksa link say.
DUVARLILAR = {
    "FilmMakinesi": {
        "main": "https://filmmakinesi.to",
        "arama": "https://filmmakinesi.to/arama/?s=%s",
        "kart": r'class="[^"]*(?:movie|film|poster|card)[^"]*"',
        "detay": r'href="[^"]*/(?:film|movie|izle)/[^"]+"',
    },
    "FullHDFilmizlesene": {
        "main": "https://www.fullhdfilmizlesene.now",
        "arama": "https://www.fullhdfilmizlesene.now/arama?q=%s",
        "kart": r'class="[^"]*(?:film|movie|poster|card)[^"]*"',
        "detay": r'href="[^"]*/[^"]*film[^"]*"',
    },
    "HDFilmCehennemi": {
        "main": "https://www.hdfilmcehennemi.nl",
        "arama": "https://www.hdfilmcehennemi.nl/?s=%s",
        "kart": r'class="[^"]*(?:film|movie|poster|card)[^"]*"',
        "detay": r'href="[^"]*/(?:film|movie)[^"]*"',
    },
    "WebteIzle": {
        "main": "https://webteizle.info",
        "arama": "https://webteizle.info/arama/%s",
        "kart": r'class="[^"]*(?:film|movie|video|card)[^"]*"',
        "detay": r'href="[^"]*/(?:film|video|izle)/[^"]+"',
    },
    "RecTV": {
        "main": "https://m.prectv72.lol",
        "arama": "https://m.prectv72.lol/?s=%s",
        "kart": r'class="[^"]*(?:post|card|item|channel)[^"]*"',
        "detay": r'href="[^"]*/(?:canli|channel|izle)[^"]*"',
    },
    "FullHDFilm": {
        "main": "https://hdfilm.us",
        "arama": "https://hdfilm.us/?s=%s",
        "kart": r'class="[^"]*(?:film|movie|poster|card)[^"]*"',
        "detay": r'href="[^"]*/(?:film|movie)[^"]*"',
    },
    "Sinewix": {
        "main": "https://sinewix.com",
        "arama": None,
        "kart": r'class="[^"]*(?:card|item|post)[^"]*"',
        "detay": r'href="[^"]*/(?:dizi|film|movie|serie)[^"]*"',
    },
}


async def _kart_say(html, desen):
    try:
        return len(re.findall(desen, html, re.I | re.S))
    except re.error:
        return 0


async def test_et(sayfa, ad, t, gorunur=True):
    sonuc = {"eklenti": ad, "mainpage": [], "arama": []}

    # ---- ANA SAYFA
    url = t["main"]
    bas = time.time()
    try:
        yanit = await sayfa.goto(url, wait_until="domcontentloaded",
                                 timeout=MAKSA_BEKLEME)
        await sayfa.wait_for_timeout(3000)
        html = await sayfa.content()
        kod = yanit.status if yanit else None
        baslik = await sayfa.title()
        kart = await _kart_say(html, t["kart"])
        detay = await _kart_say(html, t["detay"])
        sonuc["mainpage"].append({
            "url": url, "kod": kod, "baslik": baslik[:70],
            "kart": kart, "detay": detay,
            "sure": round(time.time() - bas, 1), "len": len(html),
        })
    except Exception as e:
        sonuc["mainpage"].append({
            "url": url, "kod": None, "hata": "%s: %s" % (type(e).__name__, str(e)[:100]),
            "kart": 0, "detay": 0, "sure": round(time.time() - bas, 1), "len": 0,
        })

    # ---- ARAMA
    if t.get("arama"):
        for q in TEST_SORGULARI:
            aurl = t["arama"] % q
            bas = time.time()
            try:
                yanit = await sayfa.goto(aurl, wait_until="domcontentloaded",
                                         timeout=MAKSA_BEKLEME)
                await sayfa.wait_for_timeout(2500)
                html = await sayfa.content()
                kart = await _kart_say(html, t["kart"])
                detay = await _kart_say(html, t["detay"])
                sonuc["arama"].append({
                    "sorgu": q, "kod": yanit.status if yanit else None,
                    "kart": kart, "detay": detay,
                    "sure": round(time.time() - bas, 1),
                })
            except Exception as e:
                sonuc["arama"].append({
                    "sorgu": q, "kod": None,
                    "hata": "%s: %s" % (type(e).__name__, str(e)[:80]),
                    "kart": 0, "detay": 0, "sure": round(time.time() - bas, 1),
                })
    else:
        sonuc["arama_yok"] = "arama URL'i bilinmiyor (API tabanli site)"

    return sonuc


def _karar(s):
    """CloudStream'i ilgilendiren karar: eklenti VERI cikarabiliyor mu?

    * DIKKAT: sadece 'kart>0' demek yetmez. Cloudflare Access / login
    * sayfalari da seciciyle eslesen HTML uretir ("Sign in" kutusu) ve
    * yanlis pozitif verir. RecTV tam olarak boyle yanlis 'CALISIYOR'
    * gorunmustu: baslik "Sign in ・ Cloudflare Access".
    * Bu yuzden once KAPI TESPITI yapilir.
    """
    KAPI_IZLERI = ("cloudflare access", "sign in", "giriş yap", "login",
                   "just a moment", "checking your browser", "attention required",
                   "access denied", "captcha")

    def kapi_mi(k):
        b = (k.get("baslik") or "").lower()
        return any(iz in b for iz in KAPI_IZLERI)

    mp_k = s["mainpage"][0] if s["mainpage"] else {}
    if kapi_mi(mp_k):
        return "KAPI (login/Cloudflare Access) — eklenti veri cikaramaz"

    mp = max([k.get("kart", 0) + k.get("detay", 0) for k in s["mainpage"]] or [0])
    ar = max([k.get("kart", 0) + k.get("detay", 0) for k in s["arama"]] or [0])
    if mp > 0 and (ar > 0 or not s["arama"]):
        if not s["arama"]:
            return "SITE ACILDI (arama testi yok — API tabanli)"
        return "CALISIYOR"
    if mp > 0:
        return "ANA SAYFA VAR, ARAMA YOK"
    if ar > 0:
        return "ARAMA VAR, ANA SAYFA YOK"
    return "ACILMADI / ICERIK YOK"


async def main():
    isimler = [a for a in sys.argv[1:] if not a.startswith("-")]
    gizli = "--gizli" in sys.argv
    gorunur = not gizli
    hedefler = isimler or list(DUVARLILAR.keys())

    print("Tarayici testi — %d eklenti (profil: DSH mcp-chrome)" % len(hedefler))
    if gorunur:
        print("Tarayici GORUNUR acilacak. Dogrulama takilirsa elle gec; 120 sn beklenir.")
    print("=" * 78)

    async with async_playwright() as p:
        kwargs = {
            "channel": "chrome",
            "headless": gizli,
            "args": ["--disable-blink-features=AutomationControlled"],
            "viewport": {"width": 1280, "height": 800},
        }
        if os.path.isdir(DSH_PROFIL):
            kwargs["user_data_dir"] = DSH_PROFIL
            print("Profil kullaniliyor: %s" % DSH_PROFIL)
            print("(Chrome zaten aciksa kilit hatasi verebilir — once kapatin)")
        else:
            print("! DSH profili bulunamadi, gecici profil kullanilacak")

        ctx = await p.chromium.launch_persistent_context(**kwargs)
        sayfa = ctx.pages[0] if ctx.pages else await ctx.new_page()

        sonuclar = []
        try:
            for ad in hedefler:
                t = DUVARLILAR.get(ad)
                if not t:
                    print("! bilinmeyen: %s" % ad)
                    continue
                print("\n>>> %s" % ad)
                s = await test_et(sayfa, ad, t, gorunur)
                s["karar"] = _karar(s)
                sonuclar.append(s)

                for k in s["mainpage"]:
                    if k.get("hata"):
                        print("   ANA SAYFA: HATA %s" % k["hata"])
                    else:
                        print("   ANA SAYFA: HTTP %s  %ss  %d KB  kart=%d detay=%d  \"%s\"" % (
                            k["kod"], k["sure"], round(k["len"] / 1024),
                            k["kart"], k["detay"], k["baslik"]))
                for k in s["arama"]:
                    if k.get("hata"):
                        print("   ARAMA \"%s\": HATA %s" % (k["sorgu"], k["hata"]))
                    else:
                        print("   ARAMA \"%-5s\": HTTP %s  %ss  kart=%d detay=%d" % (
                            k["sorgu"], k["kod"], k["sure"], k["kart"], k["detay"]))
                if s.get("arama_yok"):
                    print("   ARAMA: %s" % s["arama_yok"])
                print("   >>> %s" % s["karar"])
        finally:
            await ctx.close()

    print("\n" + "=" * 78)
    print("%-20s %s" % ("EKLENTI", "KARAR"))
    print("-" * 78)
    for s in sonuclar:
        print("%-20s %s" % (s["eklenti"], s["karar"]))

    yol = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "tarayici_test_sonuc.json")
    with open(yol, "w", encoding="utf-8") as f:
        json.dump(sonuclar, f, ensure_ascii=False, indent=1)
    print("\nJSON: %s" % yol)


if __name__ == "__main__":
    asyncio.run(main())
