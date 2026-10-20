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
import time
import threading
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
    """TLS el sikismasi RST yedi mi? 'engel' | 'tasinmis' | 'belirsiz' dondurur.

    2026-10-20 DUZELTMESI: Bu fonksiyon eskiden True/False donuyordu ve
    SNI'siz kontrol de basarisiz olursa YINE True (engel var) diyordu. Bu
    yuzden TASINMIS bir site ile ISP tarafindan engellenmis bir site
    ayirt edilemiyordu; her ikisi de "adres GUNCEL, dokunma" oluyordu.

    Artik uc durum var:
      'engel'    -> SNI'siz baglanti BASARILI  => kesin ISP/DPI engeli,
                    alan adi guncel, dokunma.
      'tasinmis' -> SNI'siz de RST VE alan adi Cloudflare hata sayfasi
                    donduruyor => site bu adreste degil, ARA.
      'belirsiz' -> SNI'siz de RST ama baslik okunamiyor => ARAMA YAP,
                    sonuc bulunursa tasinmis say.
    """
    import socket as _s
    import ssl as _ssl

    try:
        _istek_bekle(host)
        ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = _ssl.CERT_NONE
        with _s.create_connection((host, 443), timeout=10) as c:
            c.settimeout(10)
            with ctx.wrap_socket(c, server_hostname=host):
                return None           # SNI'li TLS basarili -> engel yok
    except Exception as e:
        if type(e).__name__ != "ConnectionResetError":
            return None           # sertifika/timeout gibi seyler -> engel degil

    # * SNI'li baglanti RST yedi.
    # * !!! 2026-10-20 KRITIK DERS: SNI'siz kontrol grubuna GUVENME.
    # * Bu agda SNI'siz TLS rastgele basarili/basarisiz oluyor (ayni host
    # * 5 denemede 5 kez OK donebiliyor, sonra SSLError). Bu yuzden
    # * "SNI'siz basarili = kesin ISP engeli" cikarimi YANLIS; dizipal2140
    # * boylece iki gun 'GUNCEL' sanildi.
    # * Tek guvenilir sinyal: KOMSU/LARDES adreste canli site olmasi.
    # * O karar _ad_oner() ile kontrol() icinde verilir. Burada sadece
    # * "kesin engel degil, arastir" diyoruz.
    return "belirsiz"


def cf_park_mi(host):
    """Alan adi yayinda mi, yoksa Cloudflare hata sayfasi mi donuyor?

    2026-10-20 DERSI (dizipal2140/1820): site baska numaraya tasindiginda
    ESKI alan adi DNS'te kalir ve Cloudflare uzerinden yalnizca "Attention
    Required!" hata sayfasi doner. Bu, TLS'te RST olarak gorunur ve eski
    `sni_engelli_mi` bunu "SNI engeli -> adres GUNCEL" diye etiketliyordu.
    Sonuc: tasinmis site sessizce olu kaliyordu.

    Ayirt edici sinyal BASLIKTIR:
      - "Attention Required! | Cloudflare" -> site burada DEGIL (park/hata)
      - gercek Turkce baslik               -> site burada, engelleniyor
    """
    try:
        _istek_bekle(host)
        istek = urllib.request.Request("https://%s/" % host, headers=HEADERS)
        with urllib.request.urlopen(istek, timeout=15) as r:
            baslik = _baslik_oku(r.read(200000).decode("utf-8", "ignore"))
        if baslik is None:
            return None            # karar verilemedi
        al = baslik.lower().strip()
        # * Cloudflare hata/park sayfalari
        if "attention required" in al or "just a moment" in al:
            return True
        if "cloudflare" in al and "error" in al:
            return True
        if al in ("", "403 forbidden", "access denied"):
            return True
        return False
    except urllib.error.HTTPError as e:
        # * 403 + Cloudflare = site burada degil; 403 + bot korumasi = baslik okunamaz
        try:
            gov = e.read(200000).decode("utf-8", "ignore")
            baslik = _baslik_oku(gov)
            if baslik and "attention required" in baslik.lower():
                return True
        except Exception:
            pass
        return None                # belirsiz
    except Exception:
        return None


def _baslik_oku(gov):
    m = re.search(r"<title[^>]*>(.*?)</title>", gov, re.S | re.I)
    return m.group(1).strip() if m else None


def dns_dogrula(host):
    """Host cozuluyor mu? 'yerel', 'bulut' ya da None dondurur."""
    try:
        socket.gethostbyname(host)
        return "yerel"
    except Exception:
        pass
    try:
        _istek_bekle("dns.google")
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


# * ISTEK GECIKMESI: SADECE diziboxen.help (InatBox API) icin 30 saniye.
# * O sunucu Cloudflare banina cok hassas: ardisik istek atinca 1006 (kalici IP
# * bani) yiyoruz. Diger siteler boyle bir kural istemez -> onlar serbest,
# * yoksa 23 eklentilik tarama 12+ dakikaya cikar (gereksiz).
ISTEK_ARASI_GECIKME = 30.0
GECIKMELI_HOSTLAR = ("diziboxen.help",)
_son_istek_zamani = [0.0]
_istek_kilidi = threading.Lock()


def _gecikme_gerekli_mi(url):
    return any(h in (url or "").lower() for h in GECIKMELI_HOSTLAR)


def _istek_bekle(url=None):
    """SADECE gecikmeli hostlar icin: son istekten 30 sn gecmesini bekler.

    url None ise (host bilinmiyorsa) gecikme UYGULANMAZ.
    """
    if not _gecikme_gerekli_mi(url):
        return
    with _istek_kilidi:
        gecen = time.time() - _son_istek_zamani[0]
        if gecen < ISTEK_ARASI_GECIKME:
            time.sleep(ISTEK_ARASI_GECIKME - gecen)
        _son_istek_zamani[0] = time.time()


def _tek_istek(url, timeout=15):
    """Tek HTTP istegi. (kod, son_adres, govde) doner; hata yerine kod doner.

    * Yalnizca diziboxen.help icin 30 sn gecikme uygulanir.
    """
    _istek_bekle(url)
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


def _host_of(mainurl):
    """mainUrl -> host (www. temizlenmis)."""
    return urlparse(mainurl).netloc.lower().removeprefix("www.")


def _sayi_rotasyonlu(mainurl):
    """Host dizipalNNNN gibi sayi ile bitiyor mu?

    Bu sitelerde kardes domain YOKTUR; tek tasinma sinyali numara takibidir.
    """
    return bool(re.match(r"^[a-z0-9-]+?\d{3,4}\.[a-z]{2,6}$", _host_of(mainurl)))


def _kardes_hedef(mainurl):
    """Ayni ismin .com/.net/.org'u nereye yonlendiriyor?

    Site MARKA DEGISTIREREK tasindiginda (dizimom.cafe -> dizidad.com) isim
    rotasyonu adayi bulamaz; ama eski ismin populer zarflari genelde yeni
    adrese yonlendirir. Bu, tasinmanin EN GUCLU sinyalidir.
    """
    host = urlparse(mainurl).netloc.lower().removeprefix("www.")
    m = re.match(r"^([a-z0-9-]+)\.[a-z]{2,6}$", host)
    if not m:
        return None
    isim = m.group(1)
    if re.search(r"\d{3,}$", isim):
        return None            # sayi rotasyonlu siteler (dizipalNNNN) kardes tutmaz
    ctx = ssl.create_default_context()
    for z in ("com", "net", "org"):
        if host == ("%s.%s" % (isim, z)):
            continue
        try:
            istek = urllib.request.Request("https://%s/" % ("%s.%s" % (isim, z)),
                                          headers=HEADERS)
            _istek_bekle("https://%s/" % ("%s.%s" % (isim, z)))
            with urllib.request.urlopen(istek, timeout=12, context=ctx) as r:
                son = r.geturl()
            son_host = urlparse(son).netloc.lower().removeprefix("www.")
            if son_host and son_host != ("%s.%s" % (isim, z)):
                return "https://" + son_host
        except Exception:
            continue
    return None


def _kt_yollari(dizin):
    """Eklentinin .kt dosyasindan kategori yollarini cikarir.

    DiziMom gibi siteler MARKA DEGISTIREREK tasinabiliyor (dizimom.cafe ->
    dizidad.com). Bu durumda isim/zarf rotasyonu adayi BULAMAZ; dogru sinyal
    .kt'deki yollarin yeni sitede de calismasidir.
    """
    klasor = os.path.join(BASE, dizin, "src", "main", "kotlin")
    yollar = []
    for kok, _, dosyalar in os.walk(klasor):
        for d in sorted(dosyalar):
            if not d.endswith(".kt"):
                continue
            try:
                icerik = open(os.path.join(kok, d), encoding="utf-8").read()
            except Exception:
                continue
            # * "${mainUrl}/yol" seklindeki mainPageOf girdileri
            for m in re.finditer(r'"\$\{mainUrl\}(/[^"?]+)"', icerik):
                y = m.group(1)
                if y != "/" and y not in yollar and "SAYFA" not in y:
                    yollar.append(y)
    return yollar[:6]


def _yapi_uyuyor(ana, yollar):
    """Aday sitede eklentinin yollari calisiyor mu? (marka degisiminde tek kanit)"""
    if not yollar:
        return False, "yol yok"
    ctx = ssl.create_default_context()
    basarili = 0
    for y in yollar:
        # * mainPageOf girdileri cogunlukla ".../page/" ile biter; eklenti sona
        # * sayfa numarasi ekler (".../page/1/"). Gercek istegi taklit et.
        aday_yol = y if not y.endswith("/") else y + "1/"
        try:
            istek = urllib.request.Request(ana.rstrip("/") + aday_yol, headers=HEADERS)
            _istek_bekle(ana)
            with urllib.request.urlopen(istek, timeout=10, context=ctx) as r:
                gov = r.read(150000).decode("utf-8", "ignore")
            m = re.search(r"<title[^>]*>(.*?)</title>", gov, re.S | re.I)
            if r.status == 200 and (not m or "404" not in m.group(1).lower()):
                basarili += 1
        except Exception:
            continue
    return (basarili >= max(2, len(yollar) // 2)), "%d/%d yol" % (basarili, len(yollar))


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
            _istek_bekle(aday)
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
        _istek_bekle(mainurl)
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
        # * TCP kuruluyor ama TLS anonsumunde RST yiyorsa bu ya AG
        # * KATMANINDA SNI engellemesidir (adres guncel) ya da site
        # * baska bir numaraya TASINMISTIR (eski alan adi Cloudflare
        # * hata sayfasi donduruyor).
        # * 2026-10-20: eskiden burada kosulsuz "dokunma" deniyordu ve
        # * tasinmis siteler sessizce olu kaliyordu. Artik teşhis uc
        # * durumlu; 'tasinmis'/'belirsiz' ise ADAY ARAMA devreye girer.
        sni = sni_engelli_mi(host)
        if sni is None:
            # * TLS el sikismasi aslinda basarili -> engel yok; HTTP katmani
            # * oxucu bir sebeple patladi. Eski davranisa don: DNS'e bak.
            kanit = dns_dogrula(host)
            if kanit:
                oneriler = _ad_oner(host) or []
                sonuc["durum"] = (
                    f"SITE YANIT VERMIYOR ({type(e).__name__}) -> DNS {kanit} ile "
                    f"cozuluyor; adres tasinmis/olmus olabilir"
                    + (f" | ADAYLAR: {', '.join(oneriler)}" if oneriler else
                       " | aday bulunamadi"))
                if oneriler:
                    sonuc["onerilen_adres"] = "https://" + oneriler[0]
                    sonuc["adaylar"] = oneriler
                    sonuc["tasinmis"] = True
                else:
                    sonuc["engel"] = True
            else:
                sonuc["durum"] = f"ALAN ADI OLU: DNS hic cozulmuyor ({type(e).__name__})"
                sonuc["olu"] = True
            return sonuc

        # * RST aliyoruz. Karar TLS'e DEGIL, komsu aday sonucuna dayanir:
        # *   komsu/lardes adayda CANLI site var  -> TASINMIS (yaz)
        # *   hicbir aday canli degil             -> GERCEK ENGEL (dokunma)
        # * Bu ayrim hdfilm.us gibi gercekten engellenen ama SAPLAM olan
        # * sitelerde yanlis pozitif uretmez, cunku onun komsu numarasi yok.
        oneriler = _ad_oner(host) or []
        if oneriler:
            sonuc["durum"] = (
                "SITE TASINMIS OLABILIR -> eski adres yanit vermiyor ama "
                "komsu/lardes adreste CANLI site bulundu | ADAYLAR: "
                + ", ".join(oneriler))
            sonuc["onerilen_adres"] = "https://" + oneriler[0]
            sonuc["adaylar"] = oneriler
            sonuc["tasinmis"] = True
        else:
            sonuc["durum"] = (
                "AG ENGELI: TLS el sikismasinda RST; hicbir komsu/lardes adayda "
                "canli site yok -> alan adi GUNCEL kabul edildi, guncelleme "
                "YAPMA. Tarayici/diger aglardan acilabilir.")
            sonuc["engel"] = True
            sonuc["sni_engelli"] = True
        return sonuc

    son = sonuc["final"].rstrip("/")
    # Cloudflare Access / login kapisina yonlendirme = domain degisikligi DEGILDIR
    if "cloudflareaccess.com" in sonuc["final"] or "/cdn-cgi/access/login" in sonuc["final"]:
        sonuc["durum"] = "KAPI (Cloudflare Access login) -> alan adi GUNCEL, guncelleme YAPMA"
        sonuc["engel"] = True
        return sonuc

    # * 2026-10-14: GENEL login/kapi yollari. Sinewix ornegi: site
    # * ydvfvdizipanel.ru -> /login'e yonlendiriyordu ve SAGLIK bunu "yeni adres"
    # * sanip mainUrl'ye YAZIYORDU -> eklenti login sayfasina bakardi.
    # * Login/kayit/sifre yollari ASLA mainUrl olmaz.
    KAPI_YOLLARI = ("/login", "/giris", "/signin", "/sign-in", "/register",
                    "/kayit", "/kayit-ol", "/auth", "/account/login",
                    "/wp-login.php", "/panel", "/admin")
    alt_yol = sonuc["final"].rstrip("/")
    if any(alt_yol.endswith(y) or (y + "/") in (alt_yol + "/") or
           (y + "?") in alt_yol for y in KAPI_YOLLARI):
        sonuc["durum"] = ("KAPI (login/giris sayfasi: %s) -> alan adi GUNCEL, "
                          "mainUrl DEGISTIRILMEZ" % alt_yol.split("/")[-1])
        sonuc["engel"] = True
        return sonuc

    # * 2026-10-14: sadece "www." / sonundaki "/" farki adres degisikligi DEGILDIR.
    # * Site zaten yonlendiriyor; mainUrl'yi kozmetik olarak degistirmek bosuna
    # * version artirip derleme tetikler. Host ayniysa dokunma.
    def _host(u):
        return urlparse(u).netloc.lower().removeprefix("www.")

    if son == mainurl.rstrip("/"):
        sonuc["durum"] = sonuc.get("durum", "OK")
        sonuc["degisti"] = False
    elif _host(sonuc["final"]) == _host(mainurl):
        sonuc["durum"] = "OK (www/slash farki, host ayni) -> dokunma"
        sonuc["degisti"] = False
    else:
        sonuc["durum"] = sonuc.get("durum", "OK (yönlendirildi)")
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
        # * SNI testi ek istek atar -> baglantiyi tuketmemek icin sayac.
        sni_test_sayaci = [0]
        for d, s in engelli:
            print(f"  {d:20} {s['mainurl']}")
            print(f"  {'':20} {s['durum']}")
            # * SNI/logIN kapisi: site MARKA DEGISTIREREK tasinmis olabilir
            # * (ornek: dizimom.cafe -> dizidad.com). Kardes zarf adlari nereye
            # * gidiyor diye bakmak en guclu sinyaldir.
            # * ONEMLI: bu test ek istek atar; baglantiyi tuketmemek icin
            # * en fazla 2 eklenti icin ve araliklI yapilir (yoksa diger
            # * eklentiler URLError alip yanlislikla 'tasinmis' gorunur).
            if s.get("sni_engelli") and sni_test_sayaci[0] < 2:
                sni_test_sayaci[0] += 1
                try:
                    time.sleep(2)
                    ana = _kardes_hedef(s["mainurl"])
                    if ana:
                        yollar = _kt_yollari(d)
                        ok, detay = _yapi_uyuyor(ana, yollar)
                        print(f"  {'':20} KARDES ADRES: {ana}  yapi={'UYUYOR' if ok else 'uymuyor'} ({detay})")
                        if ok:
                            print(f"  {'':20} >>> {d} BU ADRESE TASINMIS OLABILIR (elle dogrula + yaz)")
                except Exception:
                    pass
            # * 2026-10-20: SAYI ROTASYONU eklentisi. Eskiden yalnizca kardes
            # * zarf adlari deneniyordu; oysa dizipalNNNN gibi sayi rotasyonlu
            # * sitelerde kardes domain YOKTUR (_kardes_hedef zaten None doner)
            # * ve tek sinyal numara takibidir. Bu eksiklik yuzunden
            # * dizipal2140/1820 tasinmasi iki gun boyunca gorulmedi.
            if s.get("sni_engelli") and _sayi_rotasyonlu(s["mainurl"]):
                sni_test_sayaci[0] += 1
                try:
                    time.sleep(2)
                    yollar = _kt_yollari(d)
                    for ana in (_ad_oner(_host_of(s["mainurl"])) or []):
                        ok, detay = _yapi_uyuyor("https://" + ana, yollar)
                        if ok:
                            print(f"  {'':20} ROTASYON ADAYI: https://{ana}  "
                                  f"yapi=UYUYOR ({detay})")
                            print(f"  {'':20} >>> {d} BU ADRESE TASINMIS "
                                  f"(elle dogrula + yaz)")
                            break
                        else:
                            print(f"  {'':20} rotasyon adayi https://{ana} "
                                  f"uyumsuz ({detay})")
                except Exception:
                    pass

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
