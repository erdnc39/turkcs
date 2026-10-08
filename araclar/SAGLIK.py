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


def sni_engelli_mi(host, ip=None):
    """Baglanti TCP'de kuruluyor ama TLS anonsumunde RST yiyorsa True.

    Bu bir bot engeli DEGIL. Ag katmani (ISP/DPI) SNI alanini okuyup
    hedef alan adini engelliyor; tarayici ayni anda acilabiliyor cunku
    onun TLS yigini farkli. Belirti: SNI'li TLS RST.

    Boyle bir durumda alan adini 'olu' sanmak yanlistir; adres degismedi.
    NOT: SNI'siz kontrol grubu Cloudflare'da basarisiz olabilir (Cloudflare
    SNI'siz istegi reddeder), bu yuzden SNI'li ConnectionResetError tek
    basina yeterli kanittir.
    """
    import socket as _s
    import ssl as _ssl

    try:
        ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = _ssl.CERT_NONE
        with _s.create_connection((host, 443), timeout=10) as c:
            c.settimeout(10)
            with ctx.wrap_socket(c, server_hostname=host):
                return False          # SNI'li TLS basarili -> engel yok
    except Exception as e:
        if type(e).__name__ != "ConnectionResetError":
            return False              # sertifika/timeout gibi seyler -> engel degil

    # * SNI'li baglanti RST yedi. Kontrol grubu: SNI gondermeden dene.
    # * SNI'siz basari = kesin kanit. SNI'siz basarisizlik belirsizdir
    # * (Cloudflare SNI'siz istegi reddediyor olabilir) ama SNI'li RST
    # * gordugumuz icin yine de engel VAR sayiyoruz. Aksi halde calisan
    # * bir alan adi 'oldu' sanilir ve eklentiye olen adres yazilir.
    try:
        ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = _ssl.CERT_NONE
        with _s.create_connection((host, 443), timeout=10) as c:
            c.settimeout(10)
            with ctx.wrap_socket(c):   # server_hostname yok -> SNI gonderilmez
                return True
    except Exception:
        return True


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


def _tek_istek(url, timeout=15):
    """Tek HTTP istegi. (kod, son_adres, govde) doner; hata yerine kod doner."""
    try:
        with urllib.request.urlopen(
                urllib.request.Request(url, headers=HEADERS),
                timeout=timeout, context=ssl.create_default_context()) as r:
            return r.status, r.geturl(), ""
    except urllib.error.HTTPError as e:
        return e.code, (e.geturl() or url), ""
    except Exception as e:
        return type(e).__name__, url, ""


def _dizin_engeli_mi(aday):
    """Dizin URL'si 200 donerken gercek API yollari 403 donuyor mu?

    Cloudflare bazen dizin sayfasina izin verir ama API uc noktasini
    engeller. Bu durumda 'OK' demek yanlis olur: eklenti calismaz.
    Ornek: diziboxen.help/CDN/001/002/dizibox/v2  -> 200
           diziboxen.help/CDN/001/002/dizibox/v2/tv/list1.php -> 403
    """
    kod, _, _ = _tek_istek(aday + "/")
    if kod != 200:
        return False, None
    # * Dizin aciliyor; altindaki gercek bir uc noktayi yokla.
    for alt in ("tv/list1.php", "index.php", "api.php"):
        kod2, _, _ = _tek_istek(aday.rstrip("/") + "/" + alt)
        if kod2 in (401, 403, 429, 503):
            return True, "%s -> HTTP %s" % (alt, kod2)
    return False, None


def gizli_url_bul(dizin):
    """mainUrl tanimi olmayan eklentilerde saklanan sunucu adresini bulur.

    Bazi eklentiler (InatBox gibi) mainUrl yazmak yerine icerigi
    `contentUrl` / `apiUrl` gibi bir degiskende HMAC imzali isteklere
    kullanir. Boyle eklentiler "ATLANDI" diye geciliyordu; oysa sunucu
    adresi degismis olabilir. Bu yardimci, o degiskeni tarar.
    """
    klasor = os.path.join(BASE, dizin, "src", "main", "kotlin")
    desen = re.compile(
        r'(?:val|var)\s+(?:contentUrl|apiUrl|baseUrl|siteUrl|hostUrl)\s*=\s*"([^"]+)"')
    for kok, _, dosyalar in os.walk(klasor):
        for d in sorted(dosyalar):
            if not d.endswith(".kt"):
                continue
            yol = os.path.join(kok, d)
            try:
                icerik = open(yol, encoding="utf-8").read()
            except Exception:
                continue
            m = desen.search(icerik)
            if m:
                adres = m.group(1)
                if adres.startswith("http"):
                    return adres, yol
    return None, None


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
            for z in ("com", "fit", "best", "lol", "plus", "club", "biz", "top", "xyz",
                      "live", "tv", "wiki", "online"):
                if z != zarf:
                    adaylar.append("%s.%s" % (on, z))

    if host.startswith("www."):
        adaylar = ["www." + a for a in adaylar]
    if not adaylar:
        return None

    ctx = ssl.create_default_context()

    # * GoDaddy/Namecheap park sayfalari 200 doner, <title> ve hatta 5+ ic link
    # * icerir; yalnizca bunlari saymak "yeni adres bulundu" saniyordu ve
    # * eklentiye olen bir alan adini yaziyordu. Asagidaki imzalar gercek
    # * icerik sitelerinde gorulmez, park sayfalarinda gorulur.
    PARK_IMZALARI = (
        "godaddy.com/websites/website-builder",
        "wsimg.com",
        "afternic.com",
        "dan.com/buy-domain",
        "sedoparking.com",
        "parklogic.com",
        "domainmarket.com",
        "hugedomains.com",
        "dan.com",
        "cashparking.com",
        "above.com/park",
        "brandbucket.com",
        "dan.com/lander",
        "futurehomepage.com",
        "comingsoon.com",
        "islands.la",
    )

    # * Turkce izleme sitelerinin neredeyse tamaminda bulunan izler.
    IZLEME_IZI = re.compile(
        r"izle|watch|\bfilm\b|\bdizi\b|bolum|sezon|yeni\s+film", re.I)

    def dene(aday):
        try:
            istek = urllib.request.Request("https://%s/" % aday, headers=HEADERS)
            with urllib.request.urlopen(istek, timeout=8, context=ctx) as r:
                gov = r.read(120000).decode("utf-8", "ignore")
            if "<title" not in gov.lower():
                return None

            al = gov.lower()
            # * 1) park/ satis sayfasi imzasi -> kesin red
            if any(i in al for i in PARK_IMZALARI):
                return None

            # * 2) bos sayfa: gercek sitede yeterince cok ic link olur
            ic_link = set(re.findall(r'href="([^"]+)"', gov))
            if len(ic_link) < 10:
                return None

            # * 3) gercek icerik izi: en az biri taniliyor olmali
            if not any(IZLEME_IZI.search(x) for x in ic_link):
                return None

            return aday
        except Exception:
            return None

    with ThreadPoolExecutor(max_workers=8) as havuz:
        bulunan = [s for s in havuz.map(dene, adaylar) if s]
    return bulunan[:3]        # * tek tahmin degil, TUM adaylari goster


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
        # * TCP kuruluyor ama TLS anonsumunde RST yiyorsa bu AG KATMANINDA
        # * SNI engellemesidir; site saglamdir ve adres dogrudur. Once bunu
        # * dogruluyoruz, yoksa calisan bir alan adini 'tasinmis' sanip
        # * olen bir adresle degistirmis olurduk.
        if sni_engelli_mi(host):
            sonuc["durum"] = (
                "AG ENGELI: SNI engellemesi (TLS el sikismasinda RST; alan adi "
                "TLS icinde okunup engelleniyor) -> alan adi GUNCEL, site sag; "
                "guncelleme YAPMA. Tarayici/diger aglardan acilabilir.")
            sonuc["engel"] = True
            sonuc["sni_engelli"] = True
            return sonuc

        kanit = dns_dogrula(host)
        if kanit:
            # * DNS cozuluyor ama cevap gelmiyor => bu "ag engeli" DEGILDIR;
            # * adres tasinmis ya da kapanmis olabilir -> yeni adaylari kendimiz arariz
            oneriler = _ad_oner(host)
            sonuc["durum"] = (
                f"SITE YANIT VERMIYOR ({type(e).__name__}) -> DNS {kanit} ile cozuluyor; "
                f"adres tasinmis/olmus olabilir"
                + (f" | ADAYLAR: {', '.join(oneriler)}" if oneriler else " | aday bulunamadi"))
            sonuc["tasinmis"] = True
            if oneriler:
                # * ilk adayi oneri olarak isaretle; gerisi raporda listelenir
                sonuc["onerilen_adres"] = f"https://{oneriler[0]}"
                sonuc["adaylar"] = oneriler
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
    gizliler = []
    for d in dizinler:
        kt = kt_dosyasi(d)
        if not kt:
            print(f"[?] {d}: mainUrl'li .kt dosyasi bulunamadi")
            continue
        adres = mainurl_bul(kt)
        if adres is None:
            # * mainUrl yok: icerik gizli bir degiskende mi? Sunucu adresi
            # * degismis olabilir, kontrol edelim (AMA ASLA YAZMAYALIM:
            # * imzali API'lerin adresi elle degistirilince eklenti bozulur).
            gizli, gizli_kt = gizli_url_bul(d)
            if gizli:
                gizliler.append((d, gizli, gizli_kt))
        isler.append((d, kt, adres))

    print(f"{len(isler)} eklenti kontrol ediliyor...\n")
    with ThreadPoolExecutor(max_workers=10) as havuz:
        sonuclar = list(havuz.map(lambda x: kontrol(x[0], x[2]), isler))

    # * Gizli adresleri AYRI isleyerek kontrol et; sonuclari ayri raporla.
    if gizliler:
        print(f"{len(gizliler)} eklentide gizli sunucu adresi bulundu, kontrol ediliyor...\n")
        with ThreadPoolExecutor(max_workers=4) as havuz2:
            gizli_sonuclar = list(
                havuz2.map(lambda x: kontrol(x[0], x[1]), gizliler))

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
        print("\n### TAŞINMIŞ / OLMUŞ - ADRES GUNCELLENMELI (DNS var ama cevap yok) ###")
        for d, s in tasinmis:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")
            if s.get("adaylar"):
                print(f"  {'':20} tum adaylar: {', '.join(s['adaylar'])}")
            if s.get("onerilen_adres"):
                print(f"  {'':20} >>> oncelikli oneri = {s['onerilen_adres']}  (--apply ile yazilir)")

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

    if gizliler:
        # * BU KISIM SADECE BILDIRIMDIR -- hicbir sey yazilmaz.
        print("\n### GIZLI SUNUCU ADRESI olan eklentiler (mainUrl tanimi yok) ###")
        print("    Bu adresler contentUrl/apiUrl gibi imzali isteklerde kullanilir;")
        print("    OTOMATIK OLARAK DEGISTIRILMEZ, sadece durum bildirilir.")
        for (d, adres, _), s in zip(gizliler, gizli_sonuclar):
            # * Dizin 200 donse bile gercek API ucu 403 ise 'OK' yaniltir.
            dizin_engel, kanit = _dizin_engeli_mi(adres)
            print(f"  {d:20} {adres}")
            if dizin_engel:
                print(f"  {'':20} ENGELLI: dizin 200 donuyor ama API ucu {kanit}")
                print(f"  {'':20} -> eklenti calismaz; protokol/IP engeli, adres yazilmadi")
            else:
                print(f"  {'':20} {s['durum']}")
            if s.get("adaylar"):
                print(f"  {'':20} adaylar: {', '.join(s['adaylar'])}")
                print(f"  {'':20} >>> bunlar SADECE BILDIRIM; eklentiye yazilmadi")

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
