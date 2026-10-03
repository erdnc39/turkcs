"""Kekik deposundaki tum eklentilerin mainUrl'lerini kontrol eder.

KONTROL.py ile ayni isi yapar, cloudscraper/Kekik gerektirmez.
Varsayilan mod: raporla (--apply ile mainUrl guncelleyip version artirir).
"""
import os
import re
import sys
import urllib.request
import urllib.error
import json
import socket
import ssl
from urllib.parse import urlparse
from concurrent.futures import ThreadPoolExecutor

# betik artik araclar/ icinde, depo kokunu bir ust dizinden al
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPLY = "--apply" in sys.argv

SKIP = {"gradle", "CanliTV", "OxAx", "__Temel", "SineWix", "YouTube",
        "NetflixMirror", "HQPorner", "YeniSite", "build", ".github"}

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# Tarayici benzeri basliklar: eksik baslikla 403/baganti-kesimi alinir,
# siteyi "olu" gosterir. Basliklari tamamlayinca cogu 403 kaybolur.
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "identity",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
}


def dns_dogrula(host):
    """Host cozuluyor mu? 'yerel', 'bulut' ya da None dondurur."""
    try:
        socket.gethostbyname(host)
        return "yerel"
    except Exception:
        pass
    try:
        j = json.loads(urllib.request.urlopen(
            "https://dns.google/resolve?name=%s&type=A" % host, timeout=15).read())
        if [a for a in j.get("Answer", []) if a.get("type") == 1]:
            return "bulut"
    except Exception:
        return None
    return None


def kt_dosyasi(dizin):
    # 1) tam adayla eslesen dosya: <Dizin>/<Dizin>.kt (herhangi bir derinlikte)
    klasor = os.path.join(BASE, dizin, "src", "main", "kotlin")
    aranan = dizin.lower() + ".kt"
    for kok, _, dosyalar in os.walk(klasor):
        for d in dosyalar:
            if d.lower() == aranan:
                return os.path.join(kok, d)
    # 2) yedek: mainUrl tanimi iceren ilk dosya
    for kok, _, dosyalar in os.walk(klasor):
        for d in sorted(dosyalar):
            if not d.endswith(".kt"):
                continue
            yol = os.path.join(kok, d)
            try:
                icerik = open(yol, encoding="utf-8").read()
            except Exception:
                continue
            if re.search(r'override\s+var\s+mainUrl\s*=\s*"', icerik):
                return yol
    return None


def mainurl_bul(yol):
    icerik = open(yol, encoding="utf-8").read()
    m = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', icerik)
    return m.group(1) if m else None


def _ad_oner(host):
    """Kapali/adresi tasinmis bir site icin muhtemel yeni adaylari dener.

    - dizipal1584.com -> dizipal1585/1583/1586... (sayi takibi)
    - www.dizimom.help -> www.dizimom.com/.fit/.best... (zarf takibi)
    """
    temiz = host.lower().removeprefix("www.")
    adaylar = []

    m = re.match(r"^([a-z0-9-]+?)(\d{3,4})\.([a-z]{2,6})$", temiz)
    if m:
        on, sayi, zarf = m.group(1), int(m.group(2)), m.group(3)
        for fark in (1, -1, 2, -2, 3, -3, 4, -4, 5, -5):
            adaylar.append("%s%d.%s" % (on, sayi + fark, zarf))
    else:
        m2 = re.match(r"^([a-z0-9-]+)\.([a-z]{2,6})$", temiz)
        if m2:
            on, zarf = m2.group(1), m2.group(2)
            for z in ("com", "fit", "best", "lol", "plus", "club", "biz", "top", "xyz", "live", "tv"):
                if z != zarf:
                    adaylar.append("%s.%s" % (on, z))

    if host.startswith("www."):
        adaylar = ["www." + a for a in adaylar]
    if not adaylar:
        return None

    ctx = ssl.create_default_context()

    def dene(aday):
        try:
            istek = urllib.request.Request("https://%s/" % aday, headers=HEADERS)
            with urllib.request.urlopen(istek, timeout=6, context=ctx) as r:
                gov = r.read(40000).decode("utf-8", "ignore")
            if "<title" not in gov.lower():
                return None
            # * park/bos sayfa kontrolu: gercek sitede yeterince ic link olur
            ic_link = len(set(re.findall(r'href="(https?://[^"]+|/[^"]+)"', gov)))
            if ic_link < 5:
                return None
            return aday
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=8) as havuz:
        for sonuc in havuz.map(dene, adaylar):
            if sonuc:
                return sonuc
    return None


def kontrol(eklenti, mainurl):
    sonuc = {"eklenti": eklenti, "mainurl": mainurl}
    if not mainurl:
        sonuc["durum"] = "ATLANDI (dinamik mainUrl)"
        return sonuc
    if not mainurl.startswith("http"):
        sonuc["durum"] = "ATLANDI (non-http)"
        return sonuc

    host = urlparse(mainurl).netloc
    istek = urllib.request.Request(mainurl, headers=HEADERS)
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(istek, timeout=20, context=ctx) as r:
            sonuc["kod"] = r.status
            sonuc["final"] = r.geturl()
    except urllib.error.HTTPError as e:
        sonuc["kod"] = e.code
        sonuc["final"] = e.geturl() or mainurl
        # 401/403/429/503 = site ayakta ama bizi engelliyor -> alan adi GUNCEL
        if e.code in (401, 403, 429, 503):
            sonuc["durum"] = f"ERISIM ENGELI (HTTP {e.code}) -> alan adi GUNCEL, bot/koruma"
            sonuc["engel"] = True
            return sonuc
        sonuc["durum"] = f"HTTP {e.code}"
        return sonuc
    except Exception as e:
        kanit = dns_dogrula(host)
        if kanit:
            # * DNS cozuluyor ama cevap gelmiyor => bu "ag engeli" DEGILDIR;
            # * adres tasinmis ya da kapanmis olabilir -> yeni adayi kendimiz arariz
            oneri = _ad_oner(host)
            sonuc["durum"] = (
                f"SITE YANIT VERMIYOR ({type(e).__name__}) -> DNS {kanit} ile cozuluyor; "
                f"adres tasinmis/olmus olabilir"
                + (f" | ONERI: https://{oneri}" if oneri else " | aday bulunamadi"))
            sonuc["tasinmis"] = True
            if oneri:
                sonuc["onerilen_adres"] = f"https://{oneri}"
        else:
            sonuc["durum"] = f"ALAN ADI OLU: DNS hic cozulmuyor ({type(e).__name__})"
            sonuc["olu"] = True
        return sonuc

    son = sonuc["final"].rstrip("/")
    # Cloudflare Access / login kapisina yonlendirme = domain degisikligi DEGILDIR
    if "cloudflareaccess.com" in sonuc["final"] or "/cdn-cgi/access/login" in sonuc["final"]:
        sonuc["durum"] = "KAPI (Cloudflare Access login) -> alan adi GUNCEL, guncelleme YAPMA"
        sonuc["engel"] = True
        return sonuc
    if son == mainurl.rstrip("/"):
        sonuc.setdefault("durum", "OK")
        sonuc["degisti"] = False
    else:
        sonuc.setdefault("durum", "OK (yönlendirildi)")
        sonuc["degisti"] = True
    return sonuc


def versiyonu_artir(gradle_yolu):
    with open(gradle_yolu, "r+", encoding="utf-8") as f:
        icerik = f.read()
        m = re.search(r"version\s*=\s*(\d+)", icerik)
        if not m:
            return None
        eski = int(m[1])
        yeni = eski + 1
        f.seek(0)
        f.write(icerik.replace(f"version = {eski}", f"version = {yeni}", 1))
        f.truncate()
        return yeni


def main():
    dizinler = sorted(
        d for d in os.listdir(BASE)
        if os.path.isdir(os.path.join(BASE, d))
        and not d.startswith(".")
        and d not in SKIP
        and os.path.exists(os.path.join(BASE, d, "build.gradle.kts"))
    )

    isler = []
    for d in dizinler:
        kt = kt_dosyasi(d)
        if not kt:
            print(f"[?] {d}: mainUrl'li .kt dosyasi bulunamadi")
            continue
        isler.append((d, kt, mainurl_bul(kt)))

    print(f"{len(isler)} eklenti kontrol ediliyor...\n")
    with ThreadPoolExecutor(max_workers=10) as havuz:
        sonuclar = list(havuz.map(lambda x: kontrol(x[0], x[2]), isler))

    degisenler, hatalilar, atlanan, engelli, oluler, tasinmis, sorunsuz = [], [], [], [], [], [], 0
    for s, (d, kt, _) in zip(sonuclar, isler):
        if s.get("degisti"):
            degisenler.append((d, s))
        elif s.get("tasinmis"):
            tasinmis.append((d, s))
        elif s.get("engel"):
            engelli.append((d, s))
        elif s.get("olu"):
            oluler.append((d, s))
        elif s["durum"].startswith("OK"):
            sorunsuz += 1
        elif s["durum"].startswith("ATLANDI"):
            atlanan.append((d, s))
        else:
            hatalilar.append((d, s))

    print("=" * 78)
    print(f"SORUNSUZ: {sorunsuz}   |   YONLENDIRMEYLE GUNCELLENEBILEN: {len(degisenler)}   |   "
          f"TAŞINMIŞ/OLMUŞ: {len(tasinmis)}   |   ERISIM ENGELI: {len(engelli)}   |   "
          f"OLU DNS: {len(oluler)}   |   ATLANAN: {len(atlanan)}")
    print("=" * 78)

    if atlanan:
        print("\n### ATLANDI (statik mainUrl yok) ###")
        for d, s in atlanan:
            print(f"  {d:20} {s['durum']}")

    if degisenler:
        print("\n### YONLENDIRME ILE GUNCELLENEBILIR (son adresi 302 ile baska adrese gidiyor) ###")
        for d, s in degisenler:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} -> {s['final']}   [{s.get('kod', '?')}]")

    if tasinmis:
        print("\n### TAŞINMIŞ / OLMUŞ - ADRES GUNCELLENMELI (DNS var ama cevap yok; ADAY bulundu) ###")
        for d, s in tasinmis:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")
            if s.get("onerilen_adres"):
                print(f"  {'':20} >>> mainUrl = {s['onerilen_adres']}  (--apply ile otomatik yazilir)")

    if engelli:
        print("\n### ERISIM ENGELI - GUNCELLEME GEREKMEZ (alan adi dogru, site bizi engelliyor) ###")
        for d, s in engelli:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")

    if oluler:
        print("\n### OLU ALAN ADI - GERCEK SORUN, INCELEME GEREKIR ###")
        for d, s in oluler:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")

    if hatalilar:
        print("\n### ERISILEMEYEN ###")
        for d, s in hatalilar:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")

    if APPLY and (degisenler or tasinmis):
        print("\n### UYGULANIYOR (--apply) ###")
        kuyruk = [(d, s["mainurl"], s["final"]) for d, s in degisenler]
        kuyruk += [(d, s["mainurl"], s["onerilen_adres"]) for d, s in tasinmis if s.get("onerilen_adres")]
        ktHarita = dict((x[0], x[1]) for x in isler)
        for d, eski, yeni in kuyruk:
            if not yeni or yeni == eski:
                continue
            kt = ktHarita[d]
            with open(kt, "r+", encoding="utf-8") as f:
                icerik = f.read()
                f.seek(0)
                f.write(icerik.replace(eski, yeni, 1))
                f.truncate()
            v = versiyonu_artir(os.path.join(BASE, d, "build.gradle.kts"))
            print(f"  {d}: {eski} -> {yeni} (version={v})")


if __name__ == "__main__":
    main()
