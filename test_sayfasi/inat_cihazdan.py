"""InatBox verisini CIHAZDAN al ve TV sayfasinin yedegine cevir.

Neden? diziboxen.help, PC'nin TLS parmak izini engelliyor (403/1006) ama
Cloudstream eklentisi ayni agdan sorunsuz cekiyor. Eklenti ham JSON'lari
dis diske yazar; bu betik adb ile cekip inat_cache.json'a cevirir.

Kullanim:
  python inat_cihazdan.py            # adb ile cek + donustur
"""
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HEDEF = os.path.join(HERE, "inat_cache.json")
CIHAZ_KLASOR = ("/sdcard/Android/data/com.lagradost.cloudstream3.prerelease/"
                "files/inatbox")


def adb_bin():
    sdk = os.path.join(os.environ.get("TEMP", ""), "android-sdk", "platform-tools", "adb.exe")
    return sdk if os.path.exists(sdk) else "adb"


def adb(*args, timeout=60):
    r = subprocess.run([adb_bin(), *args], capture_output=True, text=True,
                       errors="ignore", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def cihaz_bul():
    adb("start-server")
    cikti = adb("devices")
    for satir in cikti.splitlines()[1:]:
        parca = satir.split()
        if len(parca) >= 2 and parca[1] == "device":
            return parca[0]
    # tcp denemesi
    adb("connect", "192.168.1.115:5555")
    cikti = adb("devices")
    for satir in cikti.splitlines()[1:]:
        parca = satir.split()
        if len(parca) >= 2 and parca[1] == "device":
            return parca[0]
    return None


def kanal_yap(x, grup):
    tur = (x.get("chType") or "").lower()
    if not (tur.startswith("live") or tur.startswith("tekli") or tur == "tv"):
        return None
    url = x.get("chUrl") or ""
    if not url.startswith("http"):
        return None
    return {
        "ad": x.get("chName") or "Bilinmiyor",
        "url": url,
        "gorsel": x.get("chImg") or "",
        "etiket": grup,
        "aciklama": "",
        "kaynak": "InatBox",
    }


def main():
    serial = cihaz_bul()
    if not serial:
        print("cihaz bulunamadi (adb devices'a bak)")
        sys.exit(1)
    print("cihaz:", serial)

    with tempfile.TemporaryDirectory() as tmp:
        cikti = adb("-s", serial, "pull", CIHAZ_KLASOR, tmp)
        kaynak = os.path.join(tmp, "inatbox")
        if not os.path.isdir(kaynak):
            # pull hedefi tmp ise dosyalar tmp/icine duser
            kaynak = tmp
        dosyalar = [f for f in os.listdir(kaynak) if f.endswith(".json")]
        if not dosyalar:
            print("cekilecek json yok -> eklenti henuz veri cekmedi (InatBox acilip yenile basilmali)")
            sys.exit(2)

        kanallar = []
        for f in sorted(dosyalar):
            try:
                veri = json.load(open(os.path.join(kaynak, f), encoding="utf-8"))
            except Exception as e:
                print("  okunamadi:", f, e)
                continue
            grup = f.replace(".json", "")
            if isinstance(veri, list):
                for x in veri:
                    k = kanal_yap(x, grup)
                    if k:
                        kanallar.append(k)
            print("  %-28s %s kayit" % (f, len(veri) if isinstance(veri, list) else "?"))

    json.dump({"zaman": time.time(), "kanallar": kanallar},
              open(HEDEF, "w", encoding="utf-8"), ensure_ascii=False)
    print("yazildi:", HEDEF, "| toplam canli kanal:", len(kanallar))


if __name__ == "__main__":
    main()
