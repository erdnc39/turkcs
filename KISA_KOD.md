# ⌨️ Kısa kod (shortcode) ile depo ekleme

> Bu belge, README'nin dışında tutulur. Kısa kodu **sonra** oluşturacağız; burası hem mantık açıklaması hem de oluşturulacak adımların notudur.

## Kullanım

**Ayarlar → Eklentiler → Depo ekle** kutusuna adres yerine **sadece kodu** yazıp **Depo ekle**'ye basın.

`http://`, `https://`, `.json` vb. hiçbir uzantı gerekmez; tek kelime yeterlidir.

Örnek: `turkcs` (henüz tanımlı değil — aşağıdaki adımlarla oluşturulacak)

---

## Uygulamanın arkasındaki mantık

Kaynak: [`RepositoryManager.parseRepoUrl`](https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt) + [`ExtensionsFragment`](https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsFragment.kt)

```
Sen "turkcs" yazarsın
      │
      ▼
parseRepoUrl("turkcs")
      │  metin http://… değil, cloudstreamrepo://… değil
      │  ve tamamı [a-zA-Z0-9_!-] karakterlerinden mi?  → EVET = kısa kod
      ▼
HTTP isteği → https://cutt.ly/turkcs
      │  allowRedirects = FALSE  (sayfa indirilmez, yönlendirme takip edilmez)
      ▼
Yanıttaki "Location" başlığı = GERÇEK repo.json adresi
      │
      ▼
repo.json indirilir → name / description / pluginLists okunur
      │
      ▼
plugins.json → .cs3 indirilir → SHA-256 doğrulanır → kurulur
```

1. **Kalıp kontrolü.** Metin `http://…`, `cloudstreamrepo://…` veya `https://cs.repo/…` kalıplarından birine uymuyorsa ve tamamı `a–z A–Z 0–9 _ - !` karakterlerinden oluşuyorsa **kısa kod** kabul edilir.
2. **Yönlendirme takip edilmez.** Kısaltıcı servise `allowRedirects = false` ile gidilir; sayfa değil, yanıtın **`Location` başlığı** okunur. Çıkan değer gerçek `repo.json` adresidir.
3. **`!` öneki = yedek servis.** Kod `!` ile başlıyorsa alternatif kısaltıcı denenir (kısa kodun engellendiği ağlar için, örn. TR).
4. **Depo adı `repo.json`'dan gelir.** `name`, `description`, `pluginLists` okunur; uygulamaya görünen depo adı bu dosyadadır.
5. **Kaydedilen şey çözümlenmiş gerçek adrestir.** Kısa kod yalnızca *ekleme anında* kullanılır; sonraki açılışlarda ve güncellemelerde uygulama doğrudan `plugins.json`'ı çeker. Yani kod kopsa bile kurulu depo çalışmaya devam eder.
6. **Sonrası standart zincir:** `pluginLists` → `plugins.json` → `.cs3` indirme → **SHA-256 hash doğrulama** (uyuşmazsa kurulum iptal) → `version` artınca otomatik güncelleme.

### Ek bilgiler

- **Panodan otomatik doldurma:** Uygulamada bir depoyu paylaştığında panoya `Depo Adı : Adres` biçiminde metin düşer; "Depo ekle" dialogu bunu okuyup ad ve adres kutularını kendiliğinden doldurur.
- **`cloudstreamrepo://` deep-link:** OS'e kayıtlı protokol olduğu için tarayıcıdan tek tıkla uygulama açılır ve depo ekleme ekranı gelir.
- **jsdelivr proxy:** Ayarda açıksa `raw.githubusercontent.com/…` adresleri `cdn.jsdelivr.net/gh/…` biçimine çevrilir (raw engeline karşı).

---

## Kısa kod oluşturma adımları

1. [cutt.ly](https://cutt.ly) hesabında oturum açın (e-posta doğrulaması gerekir).
2. Bu deponun doğrudan kurulum adresini yapıştırın:
   `https://raw.githubusercontent.com/erdnc39/turkcs/builds/repo.json`
3. Kısaltın.
4. Parmak izi ikonundan (**change url alias**) takma adı seçin — örn. `turkcs`.
   - Ücretsiz planda **ayda 3 özel takma ad** hakkınız vardır.
   - Aynı depoya birden fazla kod tanımlanabilir.
5. Oluşan kodu (sadece `cutt.ly/` sonrası kısım) **Ayarlar → Eklentiler → Depo ekle** kutusuna yazarak deneyin.
6. Çalıştığını doğrulayınca bu belgeye ve README'ye kodu ekleriz.

**İyi bir kısa kod:** kısa, tek kelime, TV kumandasıyla yazması kolay (`cspr`, `turkcs`). Rakamlı kodlar istenirse [Major System](https://en.wikipedia.org/wiki/Major_system) ile türetilir (ör. `cspr` → `0094`).

---

## Durum

- [x] Kısa kodun çalışma mantığı incelendi
- [ ] cutt.ly hesabı açılacak
- [ ] `turkcs` (veya seçilecek kod) tanımlanacak
- [ ] Kod test edilecek
- [ ] README'ye eklenecek
