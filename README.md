# ☁️ TurkCS — Cloudstream Türkçe Eklenti Deposu

**Cloudstream** için hazırlanmış, tamamen otomatik derlenen ve güncellenen Türkçe eklenti havuzu.

- 🧩 **29 eklenti** — film, dizi, anime, belgesel, canlı TV
- 🤖 **Otomatik derleme** — her `master` push'u GitHub Actions tarafından derlenip `builds` dalına `.cs3` + `plugins.json` olarak yazılır
- 🔒 **Hash doğrulamalı** — uygulama her eklentiyi `plugins.json`'daki SHA-256 ile doğrular
- 📦 **Tek adresle kurulum** — depoyu bir kez eklemeniz yeterli, güncellemeler kendiliğinden gelir

---

## 📦 Kurulum

1. **Cloudstream APK**'sini [recloudstream/cloudstream/releases](https://github.com/recloudstream/cloudstream/releases) adresinden kurun.
2. Uygulamada **Ayarlar → Eklentiler → Depo ekle** alanına şu adresi yazın:

   ```
   https://raw.githubusercontent.com/erdnc39/turkcs/builds/repo.json
   ```

   Veya cihazdaki tarayıcıdan tek tıkla açmak için deep-link:

   ```
   cloudstreamrepo://raw.githubusercontent.com/erdnc39/turkcs/refs/heads/builds/repo.json
   ```

3. **Depo ekle** deyin → 29 eklenti listelenir → istediğinizleri indirin.

> Uygulama `plugins.json`'ı **5 dakika** önbelleğe alır. Yeni eklenti ekledikten sonra listeyi görmek için depoyu yenileyin veya uygulamayı kapatıp açın.

---

## 🧩 Eklentiler

| Eklenti | Tür |
|---|---|
| AsyaAnimeleri | Anime |
| BelgeselX | Documentary |
| CanliTV | Live |
| Ddizi | TvSeries |
| DiziBox | TvSeries |
| Dizilla | TvSeries |
| DiziMom | TvSeries |
| DiziPal | TvSeries, Movie |
| DiziPalOriginal | TvSeries, Movie |
| DiziPod | TvSeries, Movie |
| DiziYou | TvSeries |
| FilmMakinesi | Movie |
| FilmModu | Movie |
| FullHDFilm | Movie, TvSeries |
| FullHDFilmizlesene | Movie |
| HDFilmCehennemi | Movie, TvSeries |
| HDFilmDelisi | Movie, TvSeries |
| InatBox | Movie, TvSeries, Live |
| JetFilmizle | Movie |
| RareFilmm | Movie |
| RecTV | Movie, Live, TvSeries |
| SelcukFlix | Movie, TvSeries |
| SetFilmIzle | Movie, TvSeries |
| SezonlukDizi | TvSeries |
| SinemaCX | Movie |
| Sinewix | Movie, TvSeries, Anime |
| WebdramaTurkey2 | AsianDrama, Movie, Anime, Others |
| WebteIzle | Movie |
| YeniSite | Movie (iskelet) |

---

## 🛠 Yerel derleme

Gereksinimler: **JDK 17** + **Android SDK** (`platforms;android-36`, `build-tools;36.0.0`).

```powershell
# tek bir eklentiyi derle → <Eklenti>/build/<Eklenti>.cs3
.\gradlew.bat :FilmModu:make

# tümünü derle + plugins.json üret
.\gradlew.bat make makePluginsJson -x :RecTV:make -x :RecTV:writeCacheEntry
```

**Yeni eklenti ekleme**

1. `__Temel/` klasörünü kopyalayın, adını eklenti adıyla değiştirin (klasör adı = eklenti adı).
2. `settings.gradle.kts` klasörde `build.gradle.kts` varsa projeyi **otomatik** dahil eder; `__Temel` hariç tutulmuştur.
3. `<Ad>.build.gradle.kts` içindeki `description`, `tvTypes`, `iconUrl` alanlarını doldurun.
4. `<Ad>Plugin.kt` → `registerMainAPI(...)` zaten bağlıdır; `<Ad>.kt` içindeki CSS seçicileri doldurulur.
5. `.\gradlew.bat :<Ad>:make` ile derleyip telefona kopyalayarak test edin.

---

## 🔄 Güncelleme akışı

```
master'a push  →  GitHub Actions "CloudStream Derleyici"
                     ├─ tüm eklentileri derler
                     ├─ eski .cs3 dosyalarını temizler
                     ├─ plugins.json üretir (+ RecTV kaydı)
                     └─ builds dalına zorla push eder
```

- Eklenti `build.gradle.kts` içindeki `version` değeri değiştiğinde uygulama kendiliğinden günceller.
- `status` alanı: `0` kapalı · `1` sorunsuz · `2` yavaş · `3` yalnızca beta

---

## 🩺 Sağlam kontrolü

Tüm yardımcı betikler `araclar/` klasöründedir:

| Betik | Ne işe yarar |
|---|---|
| `araclar/SAGLIK.py` | Bütün eklentilerin `mainUrl` adreslerini canlı denetler — ek bağımlılık gerektirmez |
| `araclar/CANLI_TEST.py` | Eklentilerin `search()` katmanını canlı test eder: adresi ve seçiciyi **Kotlin kodundan okuyup** siteye gider, dönen sonuç sayısını verir (`pip install lxml cssselect`) |
| `araclar/KONTROL.py` | CI'ın kullandığı otomatik alan adı güncelleyici → `domain-degisikligi` branch'i + PR açar |
| `araclar/*_bakalim.py` | Eklenti geliştirirken yazılmış video-linki çözümleme notları (derlemeye dahil değildir) |

`SAGLIK.py` kullanım:

```bash
python3 araclar/SAGLIK.py          # rapor
python3 araclar/SAGLIK.py --apply  # mainUrl güncelle + version artır
```

---

## ⚠️ Bilinmesi gerekenler

- Bazı kaynak siteler **Cloudflare** arkasındadır; uygulama dışındaki düz HTTP istekleri `403` alabilir. Uygulama içinde `CloudflareKiller` devreye girer.
- Alan adı değişikliklerinde eklenti, adres düzeltilene kadar çalışmaz — `SAGLIK.py` bunu tespit eder.
- Bu depo **resmi Cloudstream projesi değildir**; eklentiler topluluk tarafından yazılır ve moderasyon yapılmaz.

---

## ⚖️ Yasal

Bu depo yalnızca **herkese açık ve ücretsiz** yayın yapan kaynaklara erişim sağlar. Telif hakkı içeren içeriği barındırmaz veya dağıtmaz. Yasadışı eklenti kullanımına izin verilmez.

---

## 🙏 Kaynak

Bu depo, eklentilerin ve derleme altyapısının orijinal kaynağı olan **[feroxx/Kekik-cloudstream](https://github.com/feroxx/Kekik-cloudstream)** deposunun çatallanmasıdır.
