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

## ⌨️ Kısa kod (shortcode) ile ekleme

Uzun adres yazmak yerine **tek kelimelik bir kod** yazarak da depo eklenebilir — özellikle televizyon kumandasıyla yazımda kolaylık sağlar.

**Nasıl kullanılır:** **Ayarlar → Eklentiler → Depo ekle** kutusuna adres yerine sadece kodu yazıp **Depo ekle**'ye basın. `http://`, `.json` vb. hiçbir uzantı gerekmez.

**Uygulamanın arkasındaki mantık** (`RepositoryManager.parseRepoUrl`):

1. Yazılan metin değerlendirilir. `http://…`, `cloudstreamrepo://…` veya `https://cs.repo/…` kalıplarından birine uymuyorsa ve metin **yalnızca `a–z A–Z 0–9 _ - !` karakterlerinden** oluşuyorsa **kısa kod** olarak kabul edilir.
2. Kod, kısaltıcı servisin adresine **yönlendirmeleri takip etmeden** (`allowRedirects = false`) gönderilir ve HTTP yanıtındaki **`Location` başlığı** okunur → çıkan gerçek `repo.json` adresidir.
3. Kod **`!` işaretiyle** başlıyorsa bu kez alternatif kısaltıcı denenir (kısa kodun engellendiği ağlar için yedek yol).
4. Çözümlenen adres `repo.json` olarak indirilir; `name`, `description` ve `pluginLists` alanları okunur, depo adı uygulamaya bu dosyadan yazılır.
5. **Kayda alınan şey çözümlenmiş gerçek adrestir**; kısa kod yalnızca *ekleme anında* kullanılır. Sonraki açılışlarda ve güncellemelerde uygulama doğrudan `plugins.json`'ı çeker.
6. Ardından `pluginLists` içindeki her adres `plugins.json` olarak indirilir, listelenen `.cs3` dosyaları indirilir ve **SHA-256 hash** ile doğrulanır. `version` değeri arttığında eklenti otomatik güncellenir.

**Kendi kısa kodunuzu oluşturma:** [cutt.ly](https://cutt.ly) hesabında bu deponun `repo.json` adresini kısaltın, ardından kısaltma için istediğiniz özel takma adı (alias) verin. Ücretsiz planda **ayda 3 özel takma ad** hakkınız vardır; aynı depoya birden fazla kod tanımlanabilir.

> **Not:** Bu depo için henüz kısa kod tanımlanmamıştır. Kod oluşturulana kadar yukarıdaki adres ile ekleyebilirsiniz.

---

## 🧩 Eklentiler

| Eklenti | Tür | Kaynak |
|---|---|---|
| AsyaAnimeleri | Anime | asyaanimeleri.top |
| BelgeselX | Documentary | belgeselx.com |
| CanliTV | Live | m3u oynatma listesi |
| Ddizi | TvSeries | www.ddizi.im |
| DiziBox | TvSeries | www.dizibox.live |
| Dizilla | TvSeries | dizilla.now |
| DiziMom | TvSeries | www.dizimom.help |
| DiziPal | TvSeries, Movie | dizipal1584.com |
| DiziPalOriginal | TvSeries, Movie | dizipal2134.com |
| DiziPod | TvSeries, Movie | dizipod.com |
| DiziYou | TvSeries | www.diziyou.one |
| FilmMakinesi | Movie | filmmakinesi.to |
| FilmModu | Movie | www.filmmodu.one |
| FullHDFilm | Movie, TvSeries | hdfilm.us |
| FullHDFilmizlesene | Movie | www.fullhdfilmizlesene.now |
| HDFilmCehennemi | Movie, TvSeries | www.hdfilmcehennemi.nl |
| HDFilmDelisi | Movie, TvSeries | dinamik (uygulama ayarı) |
| InatBox | Movie, TvSeries, Live | dinamik (uygulama ayarı) |
| JetFilmizle | Movie | jetfilmizle.now |
| RareFilmm | Movie | rarefilmm.com |
| RecTV | Movie, Live, TvSeries | harici depo (patr0nq) |
| SelcukFlix | Movie, TvSeries | selcukflix.com |
| SetFilmIzle | Movie, TvSeries | www.setfilmizle.ltd |
| SezonlukDizi | TvSeries | sezonlukdizi.cc |
| SinemaCX | Movie | sinemacc.com |
| Sinewix | Movie, TvSeries, Anime | ydfvfdizipanel.ru |
| WebdramaTurkey2 | AsianDrama, Movie, Anime, Others | webdramaturkey2.com |
| WebteIzle | Movie | webteizle.info |
| YeniSite | Movie | **iskelet — test amaçlı** |

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

Tüm eklentilerin `mainUrl` adreslerini canlı olarak denetler (eklenti kaynak kodunda alan adı değişmişse raporlar):

```bash
python3 SAGLIK.py          # rapor
python3 SAGLIK.py --apply  # mainUrl güncelle + version artır
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
