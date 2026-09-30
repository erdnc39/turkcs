"""EKLENTI CANLI TESTI (araclar/CANLI_TEST.py)

Derlenmis .cs3'lerin calisip calismadigini KOD TABANINDAN dogrular:
her eklentinin Kotlin'deki search() fonksiyonundan istek adresini ve CSS
secicisini okur, canli siteye gider, kac sonuc dondugunu sayar.

Gereksinim:  pip install lxml cssselect
Kullanim :   python araclar/CANLI_TEST.py
"""
import os
import re
import ssl
import sys
import urllib.request
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

for _aday in (os.path.join(os.environ.get("TEMP", ""), "pylibs"), ""):
    if _aday:
        sys.path.insert(0, _aday)
import lxml.html  # noqa: E402
from lxml.cssselect import CSSSelector  # noqa: E402

# betik araclar/ icinde oldugu icin depo kokunu bir ust dizinden al
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = {"__Temel", "YeniSite", "CanliTV", "RecTV", "gradle"}
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()
SORGU = "a"


def kt_dosyasi(dizin):
    kok = os.path.join(BASE, dizin, "src", "main", "kotlin")
    hedef = None
    for k, _, ds in os.walk(kok):
        for d in ds:
            if d.lower() == dizin.lower() + ".kt":
                hedef = os.path.join(k, d)
    if hedef:
        return hedef
    for k, _, ds in os.walk(kok):
        for d in sorted(ds):
            y = os.path.join(k, d)
            try:
                c = open(y, encoding="utf-8").read()
            except Exception:
                continue
            if "override suspend fun search" in c:
                return y
    return None


def search_blok(c):
    i = c.find("override suspend fun search")
    if i < 0:
        return ""
    kalan = c[i:]
    # fonksiyonu bir sonraki top-level tanimda bitir
    m = re.search(r"\n(    (?:override|private|fun|internal|public)\b)", kalan[10:])
    if m:
        kalan = kalan[: m.start() + 10]
    return kalan[:4000]


def ilk_alan(blok, desen):
    m = re.search(desen, blok, re.S)
    if not m:
        return None
    return m.group(1)


def analiz_et(dizin):
    son = {"eklenti": dizin}
    kt_yol = kt_dosyasi(dizin)
    if not kt_yol:
        son["durum"] = "kt yok"; return son
    c = open(kt_yol, encoding="utf-8", errors="ignore").read()
    m_main = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', c)
    if not m_main:
        son["durum"] = "statik mainUrl yok"; return son
    main = m_main.group(1)
    son["main"] = main

    blok = search_blok(c)
    if not blok:
        son["durum"] = "search yok"; return son

    yontem = "GET" if re.search(r"app\.get\(", blok) else ("POST" if re.search(r"app\.post\(", blok) else "?")
    son["yontem"] = yontem

    # istek adresi: app.get( / app.post( 的 ilk string literal
    adres = None
    m_ad = re.search(r'app\.(?:get|post)\(\s*(f?)(["\'])(.*?)\2', blok, re.S)
    if m_ad:
        adres = m_ad.group(3)
    if not adres:
        # adres bir degiskene verilmis olabilir: app.get(url)
        m_deg = re.search(r'app\.(?:get|post)\(\s*([A-Za-z_]\w*)', blok)
        if m_deg:
            ident = m_deg.group(1)
            for aday in (
                r'val\s+%s\s*=\s*(f?)(["\'])(.*?)\2' % ident,
                r'var\s+%s\s*=\s*(f?)(["\'])(.*?)\2' % ident,
            ):
                m_d = re.search(aday, c, re.S)
                if m_d:
                    adres = m_d.group(3)
                    break
    if not adres:
        son["durum"] = "adres okunamadi"; return son

    adres = adres.replace("${mainUrl}", main).replace("$mainUrl", main)
    adres = adres.replace("${query}", SORGU).replace("$query", SORGU)
    adres = adres.replace("${page}", "1").replace("$page", "1")
    if adres.startswith("/") or not adres.startswith("http"):
        adres = main.rstrip("/") + "/" + adres.lstrip("/")
    if adres.rstrip("/") == main.rstrip("/"):
        son["durum"] = "arama adresi okunamadi"; return son
    son["adres"] = adres

    # CSS secici (yoksa regex'e dus)
    sec = ilk_alan(blok, r'\.select\(\s*["\']([^"\']+)["\']')
    desenler = []
    if not sec:
        for m_d in re.finditer(r'Regex\(\s*"""(.*?)"""', blok, re.S):
            if len(m_d.group(1)) > 8:
                desenler.append(m_d.group(1))
        for m_d in re.finditer(r'Regex\(\s*"((?:[^"\\]|\\.){10,})"', blok):
            desenler.append(m_d.group(1))
        desenler = desenler[:4]
        if not desenler:
            son["durum"] = "secici/desen yok"; return son
    son["secici"] = sec or ("regex: " + desenler[0][:34])

    # POST verisi
    veri = {}
    if yontem == "POST":
        m_data = re.search(r"data\s*=\s*mapOf\(([^)]*)\)", blok, re.S)
        if m_data:
            for k, d1, d2 in re.findall(r'"([^"]+)"\s*to\s*(?:"([^"]*)"|([^,\)]+))', m_data.group(1)):
                deger = d1 if d1 else d2
                deger = deger.replace("${query}", SORGU).replace("$query", SORGU).strip()
                veri[k] = deger

    # istek
    try:
        if yontem == "POST" and veri:
            istek = urllib.request.Request(
                adres, data=urllib.parse.urlencode(veri).encode(),
                headers={"User-Agent": UA, "X-Requested-With": "XMLHttpRequest",
                         "Referer": main + "/", "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
        else:
            istek = urllib.request.Request(adres, headers={"User-Agent": UA, "Referer": main + "/"})
        with urllib.request.urlopen(istek, timeout=25, context=CTX) as r:
            html = r.read()
            son["kod"] = r.status
    except urllib.error.HTTPError as e:
        son["durum"] = f"HTTP {e.code}"; return son
    except Exception as e:
        son["durum"] = f"HATA: {type(e).__name__}"; return son

    try:
        metin = html.decode("utf-8", "ignore")
        if sec:
            agac = lxml.html.fromstring(html)
            sonuc = CSSSelector(sec)(agac)
            son["adet"] = len(sonuc)
            if sonuc:
                son["ornek"] = " ".join(sonuc[0].text_content().split())[:50]
        else:
            toplam = 0
            ilk = None
            for d in desenler:
                try:
                    eslesen = re.findall(d, metin, re.I | re.S)
                except re.error:
                    continue
                toplam += len(eslesen)
                if eslesen and ilk is None:
                    ilk = str(eslesen[0])[:50]
            son["adet"] = toplam
            if ilk:
                son["ornek"] = ilk
        son["durum"] = "OK" if son.get("adet") else "BULAMADI"
    except Exception as e:
        son["durum"] = f"secici hatasi: {type(e).__name__}"
    return son


def main():
    dizinler = sorted(
        d for d in os.listdir(BASE)
        if os.path.isdir(os.path.join(BASE, d)) and not d.startswith(".")
        and d not in SKIP and os.path.exists(os.path.join(BASE, d, "build.gradle.kts"))
    )
    print(f"{len(dizinler)} eklenti canli test ediliyor...\n")
    print("%-20s %-6s %-6s %s" % ("EKLENTI", "HTTP", "SONUC", "DURUM / DETAY"))
    print("-" * 108)

    with ThreadPoolExecutor(max_workers=8) as h:
        sonuclar = list(h.map(analiz_et, dizinler))

    ok = farkli = 0
    for s in sonuclar:
        detay = s.get("durum", "?")
        if s.get("adet") is not None:
            detay = f"{s['adet']:>4} sonuc  |  {s.get('secici','')[:34]}"
            if s["adet"] > 0:
                ok += 1
            else:
                farkli += 1
                detay += f"  |  istek: {s.get('adres','')[:60]}"
        elif s.get("durum") == "OK":
            ok += 1
        print("%-20s %-6s %-6s %s" % (
            s["eklenti"], s.get("kod", "-"),
            s.get("adet", "-"), detay))
        if s.get("ornek"):
            print("%-20s %-6s %-6s   ↳ ilk: %s" % ("", "", "", s["ornek"]))

    print("-" * 108)
    print(f"CALISAN: {ok}   |   CALISMAAYAN: {farkli}   |   diger: {len(sonuclar)-ok-farkli}")


if __name__ == "__main__":
    import urllib.parse
    main()
