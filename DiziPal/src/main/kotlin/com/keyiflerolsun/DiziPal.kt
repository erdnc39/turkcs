// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import kotlinx.coroutines.delay

// * 2026-11-13: formationfeed.net CDN'i (s8.* alan adlari) YALNIZCA Googlebot UA'sina 200 donuyor.
// * Chrome/tarayici UA'si ile master ve segment istekleri HTTP 403 aliyor (ayni oturumda olculdu).
// * Player'a verilen ExtractorLink basliklarinda bu UA tasinmali, yoksa video acilmaz.
private const val CDN_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

class DiziPal : MainAPI() {
    // ! Eski Dizipal sablonu (content-grid / episodes-list-grid / ajax-search) sadece bu adreste calisiyor
    // * 2026-10-15: 2139 SNI engelli -> site 2140'a tasti (5 yol 200, TR baslik dogrulandi)
    // * 2026-10-20: 2140 oldu -> 2141 (14 yol 200, baslik "Dizipal | yabanci dizi izle, film izle",
    // *             content-grid 1 + episode-list-item 24 secici dogrulandi)
    // * 2026-10-21: 2141 -> 2142 yonlendirmesi (SAGLIK.py 302 yakaladi). 2142: 5 yol 200,
    // *             /bolumler episode-list-item 24 + /diziler content-grid 1 dogrulandi.
    // * 2026-10-24: 2142 RST/olu -> 2143. 2143: TLS OK (2142 RST), 6 yol 200 (bolumler/diziler/
    // *             filmler/netflix/exxen), /bolumler episode-list-item 24, /dizi h1.series-title 1
    // *             + detail-episode-item-wrap 6 + info-row 4, ajax-search {"success":true}.
    // * 2026-10-30: 2143 -> 2145 (SAGLIK.py 302 yakaladi). 2145: 4 yol HTTP 200, /bolumler
    // *             episode-list-item 24, /diziler + /filmler content-grid 24, /platform/netflix 20.
    // * 2026-11-03: 2145 -> 2146 (SAGLIK.py 302 yakaladi). 2146: 5 yol HTTP 200 (bolumler/diziler/
    // *             filmler/platform/netflix/kategori), /bolumler episode-list-item 24,
    // *             /diziler content-card 24, detay h1.series-title + detail-episode-item-wrap 4,
    // *             videoContainer data-cfg + ajax-player-config {"success":true} dogrulandi.
    // * 2026-11-08: 2146 olu (WinError 10054). 2147: baslik "Dizipal | yabanci dizi izle, film izle",
    // *             5 yol HTTP 200 (bolumler/diziler/filmler/platform/netflix/kategori), /bolumler
    // *             episode-list-item 24, /diziler + /filmler ul.content-grid 1 + content-card 24,
    // *             /platform/netflix + /kategori content-card 20. Tasarim AYNI.
    // * 2026-11-13: 2148 OLU (TLS el sikismasinda RST, WinError 10054; google.com kontrolu 200
    // *             -> ag saglam, alan adi gercekten olmus). 2149: 4 yol HTTP 200, baslik
    // *             "Dizipal | yabanci dizi izle, film izle", /bolumler episodes-list-grid 1 +
    // *             a.episode-list-item 24 + .ep-title 24, /diziler ul.content-grid 1 + div.card-info 24,
    // *             /ajax-search {"success":true} (over -> Tokyo Override, iron -> Ironheart).
    // *             Tasarim AYNI, sadece adres degisti.
    // * 2026-11-22 KOK NEDEN: dizipal2150.com ORIGIN'de 502 (hem Chrome hem Googlebot UA,
    // * / /bolumler /diziler /filmler hepsi "error code: 502"; CF-RAY var). Site ayakta degil.
    // * Yeni canli adres dizipal2152.com (200) — ama TASARIM DA DEGISTI:
    // *   /bolumler  -> kartlar a.episode-row-home (div.episodes-grid-home), 24 adet
    // *   /diziler   -> article.grid-card > a.grid-card-link > h3.grid-card-title, 24 adet
    // *   bolum URL'si artik /dizi/<slug>-<id>/<N>-sezon/<N>-bolum (eski /bolum/ semasi yok)
    // *   sayfa icindeki linkler dizipal1.rest'e gidiyor (sibling domain yonlendirmesi).
    // *   bolumde #videoContainer/data-cfg YOK -> yeni zincir: data-iframe-url=/embed/?token=...
    // *   -> base64 JS -> dop.palv2.cfd player -> var videoSrc="/hls/<b64>" -> CDN m3u8
    // *   -> media playlist, segmentler gomplayer-N.jpeg (ICERIK MPEG-TS).
    // * TAM OYNATMA DOGRULANDI (2026-11-22): gomplayer-1.jpeg 2340600 B = 188x12450,
    // * ilk bayt 0x47, sync 20/20 = GERCEK VIDEO. Ikinci yol (mntplayer) fMP4 moof+mdat verdi.
    // * Eski seciciler/zincir SILINMEDI, hepsi fallback olarak korunuyor.
    // ! 2152 ESKIDEN reddedilmisti (302 -> baska site). 2026-11-22'de 2152 artik GERCEK site:
    // ! bolumler/diziler sayfalari dolu, tam oynatma kanitli. O eski not artik gecerli degil.
    override var mainUrl              = "https://dizipal2152.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                                      to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/platform/netflix"                              to "Netflix",
        "${mainUrl}/platform/exxen"                                to "Exxen",
        "${mainUrl}/platform/blutv"                                to "BluTV",
        "${mainUrl}/platform/disney-plus"                          to "Disney+",
        "${mainUrl}/platform/prime-video"                          to "Amazon Prime",
        "${mainUrl}/platform/tabii"                                to "Tabii",
        "${mainUrl}/platform/gain"                                 to "Gain",
        "${mainUrl}/platform/max"                                  to "Max",
        //"${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        //"${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/kategori/bilim-kurgu"                                to "Bilimkurgu Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/kategori/komedi"                                    to "Komedi Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/kategori/belgesel"                                  to "Belgesel Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        //"${mainUrl}/kategori/erotik"                                    to "Erotik Filmler",
        // "${mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="  to "Aile",            // ! Fazla kategori olduğu için geç yükleniyor..
        // "${mainUrl}/diziler?kelime=&durum=&tur=2&type=&siralama="  to "Aksiyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=3&type=&siralama="  to "Animasyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel",
        // "${mainUrl}/diziler?kelime=&durum=&tur=6&type=&siralama="  to "Biyografi",
        // "${mainUrl}/diziler?kelime=&durum=&tur=7&type=&siralama="  to "Dram",
        // "${mainUrl}/diziler?kelime=&durum=&tur=8&type=&siralama="  to "Fantastik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=9&type=&siralama="  to "Gerilim",
        // "${mainUrl}/diziler?kelime=&durum=&tur=10&type=&siralama=" to "Gizem",
        // "${mainUrl}/diziler?kelime=&durum=&tur=12&type=&siralama=" to "Korku",
        // "${mainUrl}/diziler?kelime=&durum=&tur=13&type=&siralama=" to "Macera",
        // "${mainUrl}/diziler?kelime=&durum=&tur=14&type=&siralama=" to "Müzik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=16&type=&siralama=" to "Romantik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=17&type=&siralama=" to "Savaş",
        // "${mainUrl}/diziler?kelime=&durum=&tur=24&type=&siralama=" to "Yerli",
        // "${mainUrl}/diziler?kelime=&durum=&tur=18&type=&siralama=" to "Spor",
        // "${mainUrl}/diziler?kelime=&durum=&tur=19&type=&siralama=" to "Suç",
        // "${mainUrl}/diziler?kelime=&durum=&tur=20&type=&siralama=" to "Tarih",
        // "${mainUrl}/diziler?kelime=&durum=&tur=21&type=&siralama=" to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
        ).document
        val home     = if (request.data.contains("/bolumler")) {
            // * 2026-11-22 YENI TASARIM: kartlar a.episode-row-home (div.episodes-grid-home icinde).
            // * Eski a.episode-list-item hala deneniyor (eski adresler icin geriye uyumlu).
            val yeniKartlar = document.select("div.episodes-grid-home > a.episode-row-home")
            if (yeniKartlar.isNotEmpty()) {
                yeniKartlar.mapNotNull { it.sonBolumlerYeni() }
            } else {
                document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
            }
        } else {
            val yeniKartlar = document.select("article.grid-card > a.grid-card-link")
            if (yeniKartlar.isNotEmpty()) {
                yeniKartlar.mapNotNull { it.dizilerYeni() }
            } else {
                document.select("ul.content-grid > li").mapNotNull { it.diziler() }
            }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    // * 2026-11-22 YENI TASARIM (dizipal1.rest): <a class="episode-row-home" href=".../dizi/<slug>/<N>-sezon/<N>-bolum">
    // *   <div class="episode-row-info"><span class="episode-row-title">Dexter: Resurrection</span>
    // *   <span class="episode-row-detail">2. Sezon 3. Bölüm</span></div></a>
    private fun Element.sonBolumlerYeni(): SearchResponse? {
        val name    = this.selectFirst(".episode-row-title")?.text()?.trim() ?: return null
        val detay   = this.selectFirst(".episode-row-detail")?.text()?.trim().orEmpty()
        val episode = detay.replace(". Sezon ", "x").replace(". Bölüm", "").takeIf { it.isNotBlank() }
        val title   = if (episode != null) "$name $episode" else name

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        // * Yeni sema: /dizi/<slug>-<id>/<N>-sezon/<N>-bolum  ->  seri kokune in
        // * Eski sema : /bolum/<slug>-<N>-sezon-<N>-bolum     ->  /dizi/ + sezon-bolum eki atilir
        val seriesUrl = if (href.contains("/-sezon/")) {
            href.replace(Regex("/\\d+-sezon/.*$"), "")
        } else {
            href.replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
                .replace("/bolum/", "/dizi/")
        }

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // * 2026-11-22 YENI TASARIM: dizi kartlari <article class="grid-card"><a class="grid-card-link" href=".../dizi/<slug>">
    // *   <img alt="..."><h3 class="grid-card-title">The Blue Road</h3></a></article>
    private fun Element.dizilerYeni(): SearchResponse? {
        val title = this.selectFirst(".grid-card-title")?.text()?.trim()
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst(".ep-title")?.text() ?: return null
        val episode   = this.selectFirst(".ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "") // Sonundaki sezon-bölüm tagini at
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun DizipalSearchResult.toPostSearchResult(): SearchResponse? {
        // Zorunlu alanların kontrolü (Early return)
        val title = this.title ?: return null
        val href  = this.url ?: return null
        // * 2026-11-23: reyting artik "imdb" alaninda ve SAYI olarak geliyor; eski String "rating"
        // * alani da okunmaya devam ediyor (site eski bicime donerse diye).
        val puan  = this.imdb
            ?: this.rating?.replace(",", ".")?.toDoubleOrNull()

        // * 2026-11-23: "type" artik kucuk harf ("dizi"/"film"). Eski "Dizi" de kabul ediliyor.
        val diziMi = this.type?.trim()?.lowercase() == "dizi"

        return if (diziMi) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year      = this@toPostSearchResult.year
                if (puan != null && puan > 0.0) this.score = Score.from10(puan)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year      = this@toPostSearchResult.year
                if (puan != null && puan > 0.0) this.score = Score.from10(puan)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // * 2026-11-23 KOK NEDEN: arama ucu DEGISTI. Eski "$mainUrl/ajax-search?q=.." artik
        // * HTTP 404 donuyor (olculdu: over/iron/guy hepsi 404, HTML hata sayfasi) -> eklentinin
        // * aramasi tamamen olu idi. Yeni canli uc: "$mainUrl/search?q=.." -> HTTP 200
        // * application/json, {"results":[{title,slug,year,poster,imdb,type:"dizi"|"film",url}]}.
        // * ESKI UC SILINMEDI: yeni uc bos/hatali donerse eski uca dusuluyor (geriye uyumlu).
        val basliklar = mapOf(
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val yeniUc = app.get("$mainUrl/search?q=$query", headers = basliklar, referer = "$mainUrl/")
        var jsonResponse: DizipalSearchData? = yeniUc.text
            .takeIf { it.trimStart().startsWith("{") }
            ?.let {
                runCatching { AppUtils.parseJson<DizipalSearchData>(it) }.getOrNull()
            }

        if (jsonResponse?.results.isNullOrEmpty()) {
            Log.w("DZP", "Yeni arama ucu ($mainUrl/search) sonuc vermedi (HTTP ${yeniUc.code}), eski ajax-search deneniyor")
            val eskiUc = app.get("$mainUrl/ajax-search?q=$query", headers = basliklar, referer = "$mainUrl/")
            jsonResponse = jsonResponse
                ?: eskiUc.text.takeIf { it.trimStart().startsWith("{") }
                    ?.let { runCatching { AppUtils.parseJson<DizipalSearchData>(it) }.getOrNull() }
                ?.takeIf { !it.results.isNullOrEmpty() }
                ?: jsonResponse
        }

        val searchResponses = mutableListOf<SearchResponse>()
        jsonResponse?.results?.forEach { item ->
            item.toPostSearchResult()?.let { searchResponses.add(it) }
        }

        Log.d("DZP", "Arama \"$query\" -> ${searchResponses.size} sonuc")
        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
    // 1. BÖLÜM LİNKİ YÖNLENDİRMESİ
    if (url.contains("/bolum/")) {
        val seriesUrl = url.replace("/bolum/", "/dizi/")
            .replace(Regex("-\\d+-sezon.*"), "")
        return load(seriesUrl)
    }
    // * 2026-11-22 YENI SEMA: /dizi/<slug>-<id>/<N>-sezon/<N>-bolum -> seri kokune in
    if (Regex("/\\d+-sezon/").containsMatchIn(url)) {
        return load(url.replace(Regex("/\\d+-sezon/.*$"), ""))
    }

    val document = app.get(url).document

    // Genel Meta Bilgileri
    val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

    // * 2026-11-22 YENI TASARIM (dizipal1.rest): aside.series-data-panel > div.series-data-item
    // *   <span class="label">IMDB Puanı</span><span class="value">★ 8.5</span>
    // *   <span class="label">Yapım Yılı</span><time class="value" datetime="2025">2025</time>
    // * Eski div.info-row secicileri KORUNUYOR (eski adresler icin geriye uyumlu).
    fun veriSatiri(etiket: String): String? =
        document.select("div.series-data-item").firstOrNull {
            it.selectFirst("span.label")?.text()?.contains(etiket, ignoreCase = true) == true
        }?.selectFirst("span.value, time.value")?.text()?.trim()

    // * 2026-11-23 OLCUM (dizipal1.rest): "Yapım Yılı" diye bir series-data-item YOK.
    // * Gercek label'lar: IMDB Puanı / İzlenme Sayısı / Tür / Durum / Platform / Eklenme Tarihi.
    // * Yil bilgisi yalnizca <meta property="og:image:alt" content="Jack Hunter izle (2008) | dizipal">
    // * alaninda duruyor; oradan cikariyoruz. Eski yollar KORUNUYOR.
    val year = veriSatiri("Yapım Yılı")?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }
        ?: document.selectFirst("meta[property=og:image:alt]")?.attr("content")
            ?.let { Regex("\\((\\d{4})\\)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.let { Regex("\\((\\d{4})\\)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
    // * 2026-11-23: aciklama artik "p.series-summary" icinde (series-description/series-plot YOK).
    val description = document.selectFirst("p.series-summary")?.text()?.trim()?.takeIf { it.isNotBlank() }
        ?: document.selectFirst("p.series-description")?.text()?.trim()
        ?: document.selectFirst("p.series-plot, div.series-plot, section.series-plot")?.text()?.trim()
    // * IMDB puani: <div class="info-row"><span class="info-label">IMDB</span><span class="info-value">8.7</span>
    val score = (veriSatiri("IMDB") ?: document.selectFirst("div.info-row:contains(IMDB) span.info-value")?.text()?.trim())
        ?.let { Score.from10(Regex("([0-9]+(?:[.,][0-9]+)?)").find(it)?.value?.replace(',', '.')) }
    
    // "Kategoriler" altındaki tüm <a> tag'lerini çekip listeye çeviriyoruz
    val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }
        .ifEmpty {
            veriSatiri("Tür")?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
    
    // HTML'de süre bilgisi mevcut değil, gelirse diye hazırlıklı bırakıyorum:
    // val durationText = document.selectFirst("div.info-row:contains(Süre) span.info-value")?.text()
    // val duration = Regex("(\\d+)").find(durationText ?: "")?.value?.toIntOrNull()
    val duration: Int? = null 

    if (url.contains("/dizi/")) {
        // Yeni DOM yapısında başlık h1 tag'inde class ile tutuluyor
        val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

        // * 2026-11-22 YENI TASARIM (dizipal1.rest):
        // *   div.episodes-grid#episodes-carousel > div.episode-card > a.episode-card-link
        // *   <a href=".../1-sezon/2-bolum" class="episode-card-link" data-iframe-url="/embed/?token=..."
        // *      data-title="Camera Shy" data-season="1" data-episode="2">
        // *        <div class="episode-title">Camera Shy</div>
        // *        <div class="episode-season-info">1. Sezon 2. Bölüm</div></a>
        // * Eski div.detail-episode-item-wrap yapisi KORUNUYOR (geriye uyumlu).
        val yeniBolumler = document.select("div.episode-card > a.episode-card-link")
        val episodes = if (yeniBolumler.isNotEmpty()) {
            yeniBolumler.mapNotNull { anchor ->
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.episode-title")?.text()?.trim()
                    ?: anchor.attr("data-title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                // Once data-* alanlari, yoksa "1. Sezon 2. Bölüm" metnini parse et
                val subtitle = anchor.selectFirst("div.episode-season-info")?.text()?.trim() ?: ""
                val match    = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason  = anchor.attr("data-season").toIntOrNull()
                    ?: match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = anchor.attr("data-episode").toIntOrNull()
                    ?: match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }
        } else {
            document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null

                // Format: "1. Sezon 1. Bölüm" -> Regex ile güvenli parse işlemi
                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.score     = score   // * afisteki IMDB puani
        }
    } else {
        // Film detay sayfası HTML'i elimizde olmadığı için en olası selector'ları fallback ile yazdım.
        // Gerekirse og:title meta tag'inden de çekebilirsin.
        val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim() 
            ?: ""

        if (title.isEmpty()) return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.score     = score   // * afisteki IMDB puani
        }
    }
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        // 1. AŞAMA: GET isteği atıp hem Token'ı hem de ÇEREZLERİ alıyoruz
        // * 2026-11-01 KRITIK: site Bootcap bot korumasi ekledi (_bcs challenge).
        // * Tarayici disi istekler 3 KB'lik "Dogrulama" sayfasi aliyor, videoContainer gelmiyor.
        // * Olcum: bu 4 baslik olmadan 3078 bayt (challenge); bunlarla 61555 bayt (gercek).
        // * Zorunlu olanlar: User-Agent + Accept + Accept-Language + Sec-Fetch-Dest + Sec-Fetch-Mode.
        // * Ayrica ayni oturum (PHPSESSID + _ct cerezleri) korunmali -> ajax-player-config
        // * yalnizca o oturumda success:true donuyor.
        //
        // * 2026-11-08 OLCUM (dizipal2147.com): Bootcap zorlasti. Yukaridaki basliklar artik
        // * YETMIYOR; 3096 baytlik "Erisim dogrulaniyor" sayfasi donuyor (_bk=3 proof-of-work).
        // * Olculen (ayni oturum, ayni cerez):
        // *   Chrome UA + Sec-Fetch seti -> 3096 B, data-cfg YOK
        // *   Googlebot UA                -> 59305 B, videoContainer VAR, ajax success:true
        // * Bu yuzden once Googlebot UA ile denenir; olmazsa eski tarayici setiyle tekrar denenir.
        val chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val botUa    = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        val tarayiciBasliklari = mapOf(
            "User-Agent"      to chromeUa,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest"  to "document",
            "Sec-Fetch-Mode"  to "navigate",
            "Cache-Control"   to "no-cache",
            "Pragma"          to "no-cache"
        )
        val botBasliklari = mapOf(
            "User-Agent"      to botUa,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

        var getResponse = app.get(url = data, headers = botBasliklari)

        // * 2026-11-22 YENI TASARIM ONCE DENENIR (dizipal1.rest / dizipal2152.com):
        // *   Bolum sayfasinda #videoContainer / data-cfg YOK. Zincir tamamen farkli:
        // *   1) Bolum sayfasi / dizi sayfasi -> a.episode-card-link[data-iframe-url="/embed/?token=..."]
        // *   2) GET {kök}/embed/?token=...  -> SADECE base64 parcali JS. Parcalari birlestirince
        // *      player URL'si cikar (or. https://dop.palv2.cfd/<slug>-1-sezon-2-bolum?site=dizipal2)
        // *   3) Player sayfasinda: var videoSrc = "/hls/<base64>"  (bazi bolumlerde isDirectFile=true -> duz mp4)
        // *   4) base64 coz -> https://<cdn>/storage/media/<id>-720.mp4/gomindex.m3u8
        // *      NOT: bu bir MEDIA playlist olabilir (STREAM-INF yok) ya da master (STREAM-INF var).
        // *      Segmentler "gomplayer-1.jpeg" gibi gorunur ama ICERIK MPEG-TS'tir (188+0x47).
        // * 2026-11-22 OLCUM (kanitli): 2340600 B = 188x12450, ilk bayt 0x47, sync 20/20.
        // * Basarili olursa dogrudan doneriz; olmazsa ESKI Bootcap yolu aynen calismaya devam eder.
        if (yeniTasarimLinkler(data, subtitleCallback, callback)) {
            Log.d("DZP", "Yeni tasarim zinciri basarili")
            return true
        }
        Log.w("DZP", "Yeni tasarim zinciri sonuc vermedi, eski (Bootcap/data-cfg) yol deneniyor")

        var configToken = getResponse.document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.w("DZP", "Googlebot UA ile data-cfg yok (${getResponse.text.length} B), tarayici setiyle deneniyor")
            getResponse  = app.get(url = data, headers = tarayiciBasliklari)
            configToken  = getResponse.document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
        }

        val userAgent = if (configToken != null) botUa else chromeUa

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı! (Bootcap challenge asilamadi)")
            return false
        }

        val cookies = getResponse.headers.values("Set-Cookie")
            .mapNotNull { it.split(";").firstOrNull()?.trim()?.takeIf { p -> p.contains("=") } }
            .distinct()
            .joinToString("; ")
            .ifEmpty {
                getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

        Log.d("DZP", "Bulunan Token » $configToken")
        Log.d("DZP", "Yakalanan Çerezler » $cookies")

        // 2. AŞAMA: data-cfg'yi çöz
        val configJson: JSONObject = if (configToken.trimStart().startsWith("{")) {
            // Nadir durum: direkt JSON gömülü
            try {
                JSONObject(configToken)
            } catch (e: Exception) {
                Log.e("DZP", "data-cfg JSON parse edilemedi: ${e.message}")
                return false
            }
        } else {
            // Normal durum: POST /ajax-player-config { cfg: token }
            val cfgResponse = app.post(
                url = "$mainUrl/ajax-player-config",
                data = mapOf("cfg" to configToken),
                referer = data,
                headers = mapOf(
                    "User-Agent"       to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "Cookie"           to cookies
                )
            )
            Log.d("DZP", "ajax-player-config yanıtı » ${cfgResponse.text}")
            try {
                JSONObject(cfgResponse.text)
            } catch (e: Exception) {
                Log.e("DZP", "ajax-player-config yanıtı JSON değil: ${e.message}")
                return false
            }
        }

        // * 2026-11-10 OLCUM: site artik iki farkli config bicimi donduruyor:
        // *   A) eski sifreli  : {"success":true,"enc":{k1,k2,iv,c}} -> decryptEnc
        // *   B) yeni duz      : {"success":true,"config":{"v":"<embed-url>","t":"embed"}}
        // * Eski kod yalnizca "enc" aradigi icin B biciminde "enc nesnesi bulunamadı"
        // * deyip TUM bolumleri sessizce atiyordu (logcat 10.11.2026).
        // * Ayrica bazi yanitlarda kok dogrudan {"v":..,"t":..} olabiliyor.
        val cfgGovde = configJson.optJSONObject("config") ?: configJson

        val encObj = configJson.optJSONObject("enc") ?: cfgGovde.optJSONObject("enc")
        val duzVideo = cfgGovde.optString("v").takeIf { it.isNotBlank() }

        val embedUrlRaw = when {
            encObj != null -> decryptEnc(encObj)
            duzVideo != null -> duzVideo
            else -> null
        }
        if (encObj == null && duzVideo == null) {
            Log.e("DZP", "config içinde ne 'enc' ne de düz 'v' var! config: $configJson")
        }
        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL çözülemedi (enc null veya decrypt başarısız)! config: $configJson")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // * 2026-11-03: Bazi bolumlerde sunucu config.v = "" ve t = "none" donuyor
        // * (o bolumde kaynak yok / kaldirilmis). Bu durumda cokmek yerine
        // * temiz bir "kaynak yok" donusu yap; aksi halde Request.Builder("")
        // * IllegalArgumentException firlatip TUM eklentiyi hatali gosteriyor.
        if (embedUrl.isBlank() || embedUrl == "https://" || embedUrl == "http://") {
            Log.w("DZP", "Bu bölüm için kaynak yok (config.v boş, t=none)")
            return false
        }


        if (embedUrl.contains("imagestoo")) {
            // * 2026-11-08 OLCUM: site artik "https://imagestoo.com/i?v=<TOKEN>" veriyor.
            // * Eski kod trimEnd('/').substringAfterLast("/") yapiyordu -> bu TUM yolu
            // * ("i?v=<TOKEN>") video ID saniyor ve API bos govde donuyordu.
            // * Dogru ID: "?v=" sonrasi. Ayrica base "/i" ve "/player/index.php" uclari
            // * 404; calisan uc "/player/index.php?data=<ID>&do=getVideo" (POST) idi.
            val videoId = embedUrl
                .substringAfterLast("?v=", embedUrl.trimEnd('/').substringAfterLast("/"))
                .substringBefore("&")
                .trim()
            Log.d("DZP", "Imagestoo videoId » $videoId")

            
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

            
            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                )
            )

            var sessionCookie = ""


            val playerToken = apiResponse.cookies["fireplayer_player"]

            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                // 2. Eğer orada yoksa, Headers içinden manuel okuyalım.
                // Büyük/küçük harf duyarlılığından kaçınmak için ikisini de kontrol ediyoruz.
                val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]

                // rawSetCookie bir String olarak döndü, artık String metodlarını güvenle kullanabiliriz
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    // substringBefore yerine split kullanmak tip çıkarımı açısından her zaman daha garantilidir
                    val cleanCookie = rawSetCookie.split(";").firstOrNull()
                    if (cleanCookie != null) {
                        sessionCookie = "$cleanCookie;"
                    }
                }
            }

            Log.d("DZP", "Yakalanan Cookie » $sessionCookie")

            val responseText = apiResponse.text

            
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                
                val cleanUrl = videoSourceRaw.replace("\\/", "/")
                val finalM3u8Url = fixUrl(cleanUrl)

                Log.d("DZP", "Imagestoo Çözülen Video Kaynağı » $finalM3u8Url")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mapOf("Referer" to embedUrl).toString()
                        headers= mapOf("Cookie" to sessionCookie)
                        quality = Qualities.Unknown.value
                    }
                )


                return true

            } else {
                Log.e("DZP", "Imagestoo API yanıtından videoSource çıkarılamadı! Yanıt: $apiResponse")
                return false
            }
        }

        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text


        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }


        // * 2026-11-03 OLCUM: embed'in verdigi "master.m3u8" adresi CDN'de HTTP 403
        // * donuyor (v parametresi master'da kabul edilmiyor). AYNI dizindeki varyant
        // * manifest oynatilabilir:
        // *   embed : .../ujgwfx4lhhpt_,n,h,.urlset/master.m3u8?v=...
        // *   calisan: .../ujgwfx4lhhpt_n/index-v1-a1.m3u8      -> HTTP 200, 32 KB
        // *   calisan: .../ujgwfx4lhhpt_n/index-a1.m3u8         -> ses grubu
        // * Bu yuzden master yerine varyant manifesti turetiyoruz.
        //
        // * 2026-11-09 OLCUM (dizipal2148.com / formationfeed.net):
        // *   embed canli -> sources.file = "https://s4.superadjacentsoddenly.xyz/hls2/01/00007/smir00c6bag9_,n,h,.urlset/master.m3u8?v=..."
        // *   master (v ile veya v'siz, her referer'da) -> 403
        // *   .../smir00c6bag9_n/index-v1-a1.m3u8 -> 200, 8246 B, 92 segment
        // *   seg-1-v1-a1.jpg -> 200, 2415800 B, ilk 4 bayt 47 40 00 10 (MPEG-TS sync 0x47, PID 0x100)
        // *   Segmentler .jpg uzantili ama gercek icerik MPEG-TS; farkli .pics alan adlarindan
        // *   geliyorlar (s4.whimpered.pics, s4.marmorated.pics, ...) ama oynatma calisiyor.
        //
        // * KRITIK: "kok" = master URL'in TAM yol kismi ("/hls2/01/00007/smir00c6bag9_,n,h,.urlset").
        // * Eski kod substringBefore("/") kullaniyordu; bu TUM yolu ilk "/" karakterinde kesip
        // * sonucu "" yapiyordu -> uretilen adres "/smir00c6bag9_n/index-v1-a1.m3u8" gibi
        // * semasiz/host'suz cikiyordu. substringBeforeLast("/") ile host korunuyor.
        val finalM3u8Url = if (extractedUrl.contains("master.m3u8")) {
            val kok = extractedUrl.substringBefore("/master.m3u8")
            val yol = kok.substringBeforeLast("/")
            val dosya = kok.substringAfterLast("/")          // ujgwfx4lhhpt_,n,h,.urlset
            val anahtar = dosya.substringBefore("_,")        // ujgwfx4lhhpt
            "$yol/${anahtar}_n/index-v1-a1.m3u8"
        } else if (extractedUrl.contains(".html")) {
            // URL'den sadece ID'yi (x6sctfgmyfws) güvenli bir şekilde ayıklıyoruz
            // Örn: .../embed-x6sctfgmyfws.html -> x6sctfgmyfws
            val idRegex = Regex("""embed-([^.]+)\.html""")
            val idMatch = idRegex.find(extractedUrl)?.groupValues?.getOrNull(1)

            if (idMatch != null) {
                // İstenen formata göre string interpolation ile yeni URL'yi inşa ediyoruz
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                Log.e("DZP", "HTML linkinden ID ayıklanamadı: $extractedUrl")
                null
            }
        } else {
            // Eğer ilk regex'ten doğrudan m3u8 geldiyse olduğu gibi kullanıyoruz
            extractedUrl
        }

// 3. Son kontrol ve validation
        if (finalM3u8Url == null) {
            return false
        }

// Artık elimizde işlenmiş nihai m3u8 URL'si var
        Log.d("DZP", "Başarıyla üretilen M3U8 URL: $finalM3u8Url")

// Bundan sonraki stream ekleme veya return işlemlerini finalM3u8Url ile yapabilirsin.

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // * 2026-11-03 OLCUM: CDN yalnizca formationfeed.net referer'ini kabul
                // * ediyor. dizipal2146.com referer'i ve referersiz istekler HTTP 403.
                // * (olculen: referer=formationfeed.net/ -> 200, 32430 B)
                // * 2026-11-11 OLCUM (dizipal2148.com, s8.antiantibody.pics):
                // *   SADECE referer YETMIYOR. Segment (.jpg/.ts) istekleri referer
                // *   verilse bile HTTP 403 donuyor; "Origin: https://formationfeed.net"
                // *   eklendiginde HTTP 200 + 499704 B, ilk bayt 0x47, 188'in kati.
                // *   Olculen varyant: dizipal2148.com / kusatma-altinda -> seg-1-v1-a1.jpg
                // *   Origin YOK: 403 (0 B) | Origin VAR: 200 (499704 B, 0x47).
                // *   Bu yuzden medya isteklerine Origin basligi da ekleniyor.
                // * 2026-11-13 OLCUM (dizipal2149.com, s8.superadjacentsoddenly.xyz + s8.antiantibody.pics):
                // *   ASIL KOK NEDEN USER-AGENT. Ayni oturumda, ayni saniyede olculdu:
                // *     Chrome UA + cerez + Referer + Origin -> HTTP 403
                // *     Chrome UA + sadece Referer            -> HTTP 403
                // *     Googlebot UA + sadece Referer         -> HTTP 200, 761 B, #EXTM3U
                // *   Yani v130'da eklenen Origin basligi 403'u COZMEDI (hala 403). Referer/Origin
                // *   sart degil ama zarari yok; bu yuzden geriye uyumlu olarak KORUNUYOR ve
                // *   uzerine Googlebot User-Agent EKLENIYOR. Player UA'yi tasimazsa master ve
                // *   segment istekleri 403 alir, oynatma baslamaz.
                referer = "https://formationfeed.net/"
                headers = mapOf(
                    "Origin"     to "https://formationfeed.net",
                    "User-Agent" to CDN_UA
                )
                quality = Qualities.Unknown.value
            }
        )

        // 4. AŞAMA: Altyazıları (Tracks) Yakala
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]

                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = fixUrl(fileUrl)
                        )
                    )
                }
            }
        }

        return true
    }

    // ============================================================================================
    // * 2026-11-23 mntplayer ZINCIRI (dizipal2152.com + dizipal1.rest) — TAM OYNATMA DOGRULANDI
    // *   bolum sayfasi -> <iframe id="montana-frame" src="https://vod.mntplayer.com:8443/series/<seri>/<ep>">
    // *   -> iframe HTML: <div id="player" data-src="/p/<token>/master.m3u8">
    // *   -> GET master.m3u8 (501 B): #EXT-X-MEDIA ile AYRI audio (audio-0.m3u8) ve altyazi
    // *      (subs-0.m3u8) gruplari; tek #EXT-X-STREAM-INF satiri = "video.m3u8"
    // *   -> GET video.m3u8 (31587 B): #EXTINF=984, #EXT-X-KEY:METHOD=AES-128,URI="key" (IV ETIKETTE YOK)
    // *   -> ilk segment 1318240 B -> AES-128-CBC(sifir IV) decrypt -> 1318238 B
    // *      ilk baytlar 00 00 09 54 "moof" => fMP4/CMAF (moof+mdat). 188'e BOLUNMEZ, 0x47 YOKTUR
    // *      ama GERCEK VIDEODUR. "0x47 yok" diye basarisiz saymak YANLIS olur.
    // * NOT: mntplayer'da segment ve varyant icin Referer/UA SART DEGIL (referersiz de 200 olculdu),
    // *      yine de player'a referer veriyoruz.
    private suspend fun mntPlayerLinkler(
        iframeUrl: String,
        bolumUrl: String,
        chromeUa: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val yanit = run {
                // * 2026-11-23 OLCUM: mntplayer zaman zaman HTTP 503 + "Şu anda yoğunluk var,
                // * lütfen birazdan tekrar deneyin." donduruyor (gecici). Tek denemede pes etmek
                // * bolumu hatali gostermeye yol acar; kisa bekleyip tekrar deniyoruz.
                var son = app.get(
                    url     = iframeUrl,
                    headers = mapOf("User-Agent" to chromeUa, "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"),
                    referer = bolumUrl
                )
                var deneme = 0
                while (deneme < 2 && son.code != 200) {
                    Log.w("DZP-YENI", "mntplayer iframe HTTP ${son.code}, 3 sn sonra tekrar (deneme ${deneme + 1})")
                    delay(3000L)
                    son = app.get(
                        url     = iframeUrl,
                        headers = mapOf("User-Agent" to chromeUa, "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"),
                        referer = bolumUrl
                    )
                    deneme++
                }
                son
            }
            // * data-src goreli olabilir ("/p/<token>/master.m3u8"); iframe URL'sine gore mutlaklastir.
            val dataSrc = Regex("""data-src\s*=\s*"([^"]+)"""").find(yanit.text)?.groupValues?.get(1)
                ?: Regex("""(?:"|')?(/[^"'\s]*master\.m3u8[^"'\s]*)""").find(yanit.text)?.groupValues?.get(1)
            if (dataSrc == null) {
                Log.w("DZP-YENI", "mntplayer: data-src (master.m3u8) bulunamadi (${yanit.text.length} B)")
                return false
            }
            val masterUrl = mutlakUrl(dataSrc, iframeUrl)
            Log.d("DZP-YENI", "mntplayer master » $masterUrl")

            val master = app.get(
                url     = masterUrl,
                headers = mapOf("User-Agent" to chromeUa, "Accept" to "*/*"),
                referer = iframeUrl
            )
            if (!master.text.contains("#EXTM3U")) {
                Log.w("DZP-YENI", "mntplayer: master HLS degil (${master.text.length} B)")
                return false
            }

            // * KRITIK: master'da #EXT-X-MEDIA satirlari da "URI=...m3u8" icerir; duz
            // * "ilk # ile baslamayan satir" almak audio-0.m3u8'i kapabilir. Bu yuzden
            // * SADECE #EXT-X-STREAM-INF'ten SONRAKI ilk anahtar satirini aliyoruz.
            var mediaUrl = masterUrl
            if (master.text.contains("#EXT-X-STREAM-INF")) {
                val satirlar = master.text.lines().map { it.trim() }
                val idx      = satirlar.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                val varyant  = if (idx >= 0) {
                    satirlar.drop(idx + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                } else null
                if (varyant == null) {
                    Log.w("DZP-YENI", "mntplayer: master'da varyant satiri yok")
                    return false
                }
                mediaUrl = mutlakUrl(varyant, masterUrl)
                val v = app.get(
                    url     = mediaUrl,
                    headers = mapOf("User-Agent" to chromeUa, "Accept" to "*/*"),
                    referer = masterUrl
                )
                if (!v.text.contains("#EXTINF")) {
                    Log.w("DZP-YENI", "mntplayer: varyant #EXTINF icermiyor (${v.text.length} B)")
                    return false
                }
                Log.d("DZP-YENI", "mntplayer varyant » $mediaUrl (${v.text.length} B, EXTINF=${Regex("#EXTINF").findAll(v.text).count()}, KEY=${v.text.contains("#EXT-X-KEY")})")
            } else if (!master.text.contains("#EXTINF")) {
                Log.w("DZP-YENI", "mntplayer: ne STREAM-INF ne EXTINF var")
                return false
            }

            val mediaKok = "https://" + mediaUrl.substringAfter("//").substringBefore("/")
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = "${this.name} [mntplayer]",
                    url     = mediaUrl,
                    type    = ExtractorLinkType.M3U8
                ) {
                    this.referer = mediaKok + "/"
                    this.quality = 1080
                    this.headers = mapOf("User-Agent" to chromeUa, "Referer" to mediaKok + "/")
                }
            )
            return true
        } catch (e: Exception) {
            Log.e("DZP-YENI", "mntplayer zinciri hata: ${e.message}")
            return false
        }
    }

    // * 2026-11-22 YENI TASARIM ZINCIRI (dizipal1.rest / dizipal2152.com)
    // * Kanitlanmis akis (segment baytlari dahil olculdu):
    // *   bolum/dizi sayfasi -> a.episode-card-link[data-iframe-url=/embed/?token=..]
    // *   -> /embed/?token=..  (base64 parcali JS)  -> https://<player>/<slug>-<N>-sezon-<N>-bolum?site=dizipal2
    // *   -> player JS: var videoSrc = "/hls/<base64>"  -> https://<cdn>/storage/media/<id>-720.mp4/gomindex.m3u8
    // *   -> media/masker m3u8; segmentler gomplayer-N.jpeg (ICERIK MPEG-TS) VEYA video-N.m4s (fMP4)
    // ============================================================================================
    private suspend fun yeniTasarimLinkler(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val chromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            val iframeBasliklari = mapOf(
                "User-Agent"      to chromeUa,
                "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Sec-Fetch-Dest"  to "iframe",
                "Sec-Fetch-Mode"  to "navigate",
                "Sec-Fetch-Site"  to "same-origin"
            )

            // 1) Bolum sayfasi (yoksa dizi kokune in) ve embed token'i bul
            var sayfa = app.get(url = data, headers = iframeBasliklari)

            // * 2026-11-23 OLCUM (KRITIK KOK NEDEN): site iframe yolunu YINE degistirdi.
            // * Bolum sayfasinda artik "a.episode-card-link[data-iframe-url]" HIC YOK (0 adet,
            // * 4 farkli bolumde olculdu). Yerine dogrudan player iframe'i gomuluyor:
            // *   <iframe id="montana-frame" src="https://vod.mntplayer.com:8443/series/<seriId>/<epId>"
            // *           data-base="..."><iframe id="trailer-iframe">
            // * Bu yuzden v133'teki episode-card-link yolu bulamiyor, eski data-cfg yoluna dusuyor
            // * (o da artik yok) -> TUM bolumlerde oynatma basarisiz. Asagidaki yol bunu cozer.
            // * ESKI episode-card-link yolu SILINMEDI, site eski tasarima donerse diye korunuyor.
            val montanaIframe = sayfa.document
                .selectFirst("iframe#montana-frame[src]")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: sayfa.document.select("iframe[src]")
                    .map { it.attr("src").trim() }
                    .firstOrNull { it.startsWith("http") && !it.contains("trailer") && !it.contains("youtube") }

            if (montanaIframe != null) {
                Log.d("DZP-YENI", "montana-frame iframe » $montanaIframe")
                if (mntPlayerLinkler(montanaIframe, data, chromeUa, callback)) return true
                Log.w("DZP-YENI", "mntplayer zinciri sonuc vermedi, episode-card-link yolu deneniyor")
            }

            var embedYolu = sayfa.document.selectFirst("a.episode-card-link[data-iframe-url]")?.attr("data-iframe-url")?.trim()
                ?.takeIf { it.isNotEmpty() }

            // Bolum URL'sinde kart yoksa dizi sayfasina in ve dogru bolumun kartini bul
            if (embedYolu == null) {
                val diziUrl = if (Regex("/\\d+-sezon/").containsMatchIn(data)) {
                    data.replace(Regex("/\\d+-sezon/.*$"), "")
                } else {
                    data
                }
                if (diziUrl != data) {
                    sayfa = app.get(url = diziUrl, headers = iframeBasliklari)
                }
                val kartlar = sayfa.document.select("a.episode-card-link[data-iframe-url]")
                val anahtar = Regex("/(\\d+)-sezon/(\\d+)-bolum").find(data)
                val hedef = if (anahtar != null) {
                    val s = anahtar.groupValues[1]
                    val b = anahtar.groupValues[2]
                    kartlar.firstOrNull {
                        it.attr("data-season") == s && it.attr("data-episode") == b
                    } ?: kartlar.firstOrNull()
                } else {
                    kartlar.firstOrNull()
                }
                embedYolu = hedef?.attr("data-iframe-url")?.trim()?.takeIf { it.isNotEmpty() }
            }

            if (embedYolu == null) {
                Log.w("DZP-YENI", "embed yolu (data-iframe-url) bulunamadi")
                return false
            }
            Log.d("DZP-YENI", "embed yolu » $embedYolu")

            val kok = mainUrl.trimEnd('/')
            val embedUrl = if (embedYolu.startsWith("http")) embedYolu else "$kok/$embedYolu".replace("//embed/", "/embed/")

            // 2) /embed/ sayfasindaki base64 parcalarini birlestir -> player URL
            val embedYanit  = app.get(url = embedUrl, headers = iframeBasliklari, referer = data)
            val playerUrl   = embedYanit.text
                .let { Regex("\"([A-Za-z0-9+/=]{4,})\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
                .mapNotNull { parca -> b64Metin(parca) }
                .joinToString("")
                .takeIf { it.startsWith("http") }
            if (playerUrl == null) {
                Log.w("DZP-YENI", "embed sayfasindan player URL cikarilamadi (${embedYanit.text.length} B)")
                return false
            }
            Log.d("DZP-YENI", "player URL » $playerUrl")

            // 3) Player sayfasindan videoSrc -> manifest
            val playerYanit = app.get(
                url     = playerUrl,
                headers = mapOf("User-Agent" to chromeUa, "Accept" to "*/*"),
                referer = data
            )
            val videoSrc = Regex("""var\s+videoSrc\s*=\s*"([^"]+)"""").find(playerYanit.text)?.groupValues?.get(1)
            if (videoSrc == null) {
                Log.w("DZP-YENI", "player sayfasinda videoSrc yok (${playerYanit.text.length} B)")
                return false
            }
            val isDirectFile = Regex("""var\s+isDirectFile\s*=\s*true""").containsMatchIn(playerYanit.text)

            val ham     = videoSrc.removePrefix("/hls/")
            val cozulen = b64Metin(ham)
            if (cozulen == null || !cozulen.startsWith("http")) {
                Log.w("DZP-YENI", "videoSrc cozulemedi: $videoSrc")
                return false
            }
            Log.d("DZP-YENI", "videoSrc » $cozulen (isDirectFile=$isDirectFile)")

            // 4) Player'a verilecek link
            val playerKok = "https://" + playerUrl.substringAfter("//").substringBefore("/")
            if (isDirectFile) {
                callback.invoke(
                    newExtractorLink(
                        source  = this.name,
                        name    = "${this.name} [mp4]",
                        url     = cozulen,
                        type    = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerKok + "/"
                        this.quality = 720
                        this.headers = mapOf("User-Agent" to chromeUa, "Referer" to playerKok + "/")
                    }
                )
                return true
            }

            // * manifest GUVENLIK KONTROLU: gercekten HLS mi?
            val mf      = app.get(url = cozulen, headers = mapOf("User-Agent" to chromeUa, "Accept" to "*/*"), referer = playerKok + "/")
            val mfMetin = mf.text
            if (!mfMetin.contains("#EXTM3U")) {
                Log.w("DZP-YENI", "manifest HLS degil (${mfMetin.length} B)")
                return false
            }

            // * Master ise gercek medya playlist'ini cek; degilse kendisi zaten media playlist
            var mediaUrl  = cozulen
            var mediaRef  = playerKok + "/"
            if (mfMetin.contains("#EXT-X-STREAM-INF")) {
                val varyant = mfMetin.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (varyant != null) {
                    mediaUrl = mutlakUrl(varyant, cozulen)
                    val v    = app.get(url = mediaUrl, headers = mapOf("User-Agent" to chromeUa, "Accept" to "*/*"), referer = cozulen)
                    if (!v.text.contains("#EXTINF")) {
                        Log.w("DZP-YENI", "varyant #EXTINF icermiyor")
                        return false
                    }
                    Log.d("DZP-YENI", "varyant » $mediaUrl (${v.text.length} B, EXTINF=${"#EXTINF".toRegex().findAll(v.text).count()})")
                }
            }

            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = "${this.name} [HLS]",
                    url     = mediaUrl,
                    type    = ExtractorLinkType.M3U8
                ) {
                    this.referer = mediaRef
                    this.quality = 720
                    this.headers = mapOf("User-Agent" to chromeUa, "Referer" to mediaRef)
                }
            )
            return true
        } catch (e: Exception) {
            Log.e("DZP-YENI", "yeni tasarim zinciri hata: ${e.message}")
            return false
        }
    }
}

private fun b64ToBytes(s: String): ByteArray? = try {
    val normalized = s.replace('-', '+').replace('_', '/')
        .padEnd((s.length + 3) / 4 * 4, '=')
    Base64.decode(normalized, Base64.DEFAULT)
} catch (e: Exception) { null }

// * 2026-11-22: Yeni tasarimda URL'ler base64 parcalari halinde veriliyor.
// * Bosluk/kaçış karakteri iceren parcalar decode edilemezse sessizce atlanir.
private fun b64Metin(s: String): String? = try {
    val temiz = s.trim().replace('\n', ' ').replace("\\/", "/")
    if (temiz.isEmpty()) null
    else String(
        Base64.decode(temiz.replace('-', '+').replace('_', '/').padEnd((temiz.length + 3) / 4 * 4, '='), Base64.DEFAULT),
        Charsets.UTF_8
    ).filter { it.code in 32..126 }
} catch (e: Exception) { null }

// * goreli bir m3u8 yolunu, referans URL'sinin kokune gore mutlak hale getirir.
private fun mutlakUrl(yol: String, referans: String): String = when {
    yol.startsWith("http") -> yol
    yol.startsWith("//")   -> "https:$yol"
    else -> {
        val kok = referans.substringBefore("?").substringBeforeLast("/")
        if (yol.startsWith("/")) {
            "https://" + referans.substringAfter("//").substringBefore("/") + yol
        } else {
            "$kok/$yol"
        }
    }
}

private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
    val len = minOf(a.size, b.size)
    return ByteArray(len) { (a[it].toInt() xor b[it].toInt()).toByte() }
}

private fun decryptEnc(enc: JSONObject): String? {
    return try {
        val k1 = b64ToBytes(enc.getString("k1")) ?: return null
        val k2 = b64ToBytes(enc.getString("k2")) ?: return null
        val iv = b64ToBytes(enc.getString("iv")) ?: return null
        val ct = b64ToBytes(enc.getString("c"))  ?: return null

        val key = xorBytes(k1, k2)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        String(cipher.doFinal(ct), Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e("DZP", "decryptEnc hata: ${e.message}")
        null
    }
}