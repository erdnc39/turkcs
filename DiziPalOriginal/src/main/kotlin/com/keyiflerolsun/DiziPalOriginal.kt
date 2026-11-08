// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziPalOriginal : MainAPI() {
    // * 2026-10-17: site TAMAMEN yeni tasarima gecti (m3/Modern UI).
    // * Eski /yeni-eklenen-dizi-bolumler, /hd-film-izle, /kanal/* yollari 404.
    // * Yeni yollar: /yeni-eklenen-bolumler (a.ep-row), /diziler /filmler /anime (poster karti).
    // * data-rm-k sifreli video yapisi AYNI kaldi -> loadLinks degismedi.
    // * 2026-10-20: 1820 oldu -> 1821 (4 yol 200, baslik "Dizipal Orijinal | yabanci dizi ve film izle",
    // *             /yeni-eklenen-bolumler a.ep-row 104 + /diziler poster 130 secici dogrulandi)
    // * 2026-10-21: 1821 OLDU (TLS ConnectionReset). 1822: 4 yol 200,
    // *             /yeni-eklenen-bolumler a.ep-row 104 dogrulandi, baslik Turkce.
    // * 2026-10-30: 1822 -> 1823 (SAGLIK.py 302 yakaladi). TASARIM DEGISTI: eski a.ep-row /
    // *             .poster / a.ep-row siniflari KALDIRILDI. Yeni yapi:
    // *             arsiv kartlari a.tc (aria-label "Baslik, 2026, Tur, IMDb 7.7"),
    // *             bolum kartlari a.ecl / a.mwr (aria-label "Baslik, 4. Sezon 1. Bolum").
    // *             Olculen: /diziler a.tc 54, /filmler 16, /anime 60, /yeni-eklenen-bolumler a.ecl+mwr.
    // * 2026-11-03: 1823 -> 1824 (SAGLIK.py 302 yakaladi). 1824: 4 yol HTTP 200, baslik Turkce,
    // *             /diziler a.tc 54, /filmler 16, /anime 30, /yeni-eklenen-bolumler a.ecl 27 + a.mwr 5,
    // *             detay 118 KB + 4 JSON-LD TVSeason blogu (yapi AYNI).
    // *             ARAMA BULUNDU: /?s= arama yapmiyor (404 + popular listesi). Dogru uc:
    // *             /arama-yap?s=<kelime> -> "ceki" 2, "over" 4, "iron" 9 dogru a.tc karti.
    // * 2026-11-08: 1824 olu (WinError 10054). 1825: baslik "Dizipal Orijinal | yabanci dizi ve film izle",
    // *             5 yol HTTP 200, /yeni-eklenen-bolumler a.ecl 27 + a.mwr 5, /diziler a.tc 54,
    // *             /filmler 16, /anime 30, /arama-yap?s=over a.tc 7. Tasarim AYNI.
    override var mainUrl              = "https://dizipal1825.com"
    override var name                 = "DiziPalOriginal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
// ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        // * Yeni tasarim: bolum listesi tek sayfada (a.ep-row kartlari)
        "${mainUrl}/yeni-eklenen-bolumler"         to "Son Bölümler",
        "${mainUrl}/diziler"                       to "Tüm Diziler",
        "${mainUrl}/filmler"                       to "Tüm Filmler",
        "${mainUrl}/anime"                         to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // * Yeni tasarim: sayfalama ?sayfa=N ile
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&sayfa=$page" else "${request.data}?sayfa=$page"
        } else {
            request.data
        }

        val document = app.get(
            url, timeout = 15000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val home = mutableListOf<SearchResponse>()

        if (request.data.contains("/yeni-eklenen-bolumler")) {
            // * 2026-10-30: yeni tasarim bolum kartlari a.ecl / a.mwr (aria-label tabanli)
            home.addAll(document.select("a.ecl, a.mwr").mapNotNull { it.epRowKarti() })
        } else {
            // * 2026-10-30: arsiv sayfalari (/diziler /filmler /anime) -> a.tc kartlari
            home.addAll(document.select("a.tc").mapNotNull { it.posterKarti() })
            if (home.isEmpty()) {
                home.addAll(document.select("a[href*=/dizi/], a[href*=/film/]")
                    .mapNotNull { it.posterKarti() })
            }
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    // * 2026-11-04: /dizi/<slug> -> 404. Seri sayfasi /dizi/<slug>-dizi-izle.
    // * Slug'lar 2026-10-30'dan beri zaten "-dizi-izle" ile bitiyor (orn. visionquest-dizi-izle).
    // * Eski bolum linklerinden turetilen ciplak slug'lari da bu fonksiyon tamamlar.
    private fun seriIzi(slug: String): String {
        val s = slug.trim().trim('/')
        return when {
            s.endsWith("-dizi-izle") || s.endsWith("-film-izle") -> s
            else -> "$s-dizi-izle"
        }
    }

    // * 2026-10-30 yeni tasarim bolum karti:
    // * <a href="/dizi/slug/4-sezon/8-bolum" class="ecl|mwr" aria-label="BASLIK, 4. Sezon 8. Bölüm ...">
    private fun Element.epRowKarti(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null

        val img = this.selectFirst("img")
        val aria = this.attr("aria-label").trim()
        val baslik = aria.substringBefore(", ").trim()
            .ifBlank { this.attr("title").removeSuffix(" izle").trim() }
            .ifBlank { img?.attr("alt")?.trim() ?: "" }
            .ifBlank { return null }

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        // * Bolum linkinden seri kok adresi: /dizi/slug/sezon/bolum -> /dizi/slug
        // * 2026-11-04 KRITIK: /dizi/<slug> 404 VERIYOR (Next.js). Gecerli seri sayfasi
        // * /dizi/<slug>-dizi-izle; JSON-LD (TVSeries + containsSeason[].episode[]) SADECE
        // * orada var. Bolum sayfasinda yalnizca TVEpisode blogu + 6 link bulunuyor, bu
        // * yuzden "JSON-LD: 0 sezon, 0 bolum" -> "Konu Bulunamadı / Çok yakında".
        val seriUrl = href.split("/dizi/").lastOrNull()?.split("/")?.take(1)?.firstOrNull()
            ?.let { "${mainUrl}/dizi/${seriIzi(it)}" } ?: href

        // * Sezon/Bolum bilgisi aria-label veya basliktaki "3. Sezon 4. Bölüm" kismindan
        val kaynak = if (aria.isNotBlank()) aria else baslik
        val sezon = Regex("""(\d+)\.\s*Sezon""").find(kaynak)?.groupValues?.get(1)
        val bolum = Regex("""(\d+)\.\s*Bölüm""").find(kaynak)?.groupValues?.get(1)
            ?: Regex("""(\d+)-bolum""").find(href)?.groupValues?.get(1)
        val etiket = if (sezon != null && bolum != null) " (${sezon}x${bolum})" else ""

        return newTvSeriesSearchResponse(baslik + etiket, seriUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // * 2026-10-30 yeni tasarim arsiv karti:
    // * <a class="tc" href="/dizi/slug" aria-label="BASLIK, 2026, Dram, IMDb 7.7">
    private fun Element.posterKarti(): SearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: this.closest("a")
        val href = fixUrlNull(a?.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null

        val img = this.selectFirst("img") ?: a?.selectFirst("img")
        // * Baslik: aria-label ilk virgul oncesi; yoksa img alt / title / slug
        val aria = (a?.attr("aria-label") ?: this.attr("aria-label")).trim()
        val baslik = aria.substringBefore(", ").trim().ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: a?.attr("title")?.removeSuffix(" izle")?.trim()?.ifBlank { null }
            ?: href.trimEnd('/').split("/").lastOrNull()?.let {
                it.removeSuffix("-dizi-izle").removeSuffix("-film-izle")
                    .split("-").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            }?.ifBlank { null }
            ?: return null

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }
        )
        val tip = if (href.contains("/film/")) TvType.Movie else TvType.TvSeries

        return if (tip == TvType.Movie) {
            newMovieSearchResponse(baslik, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(baslik, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // * 2026-11-03: /?s= ARAMA YAPMIYOR (Next.js 404 + "popular" listesi donuyor).
        // * Dogru uc: /arama-yap?s=<kelime>
        val document = app.get(
            "${mainUrl}/arama-yap?s=${query}",
            timeout = 15000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val sonuc = mutableListOf<SearchResponse>()
        sonuc.addAll(document.select("a.tc").mapNotNull { it.posterKarti() })
        if (sonuc.isEmpty()) {
            sonuc.addAll(document.select("a.ecl, a.mwr").mapNotNull { it.epRowKarti() })
        }
        if (sonuc.isEmpty()) {
            sonuc.addAll(document.select("a[href*=/dizi/], a[href*=/film/]")
                .mapNotNull { it.posterKarti() })
        }
        // * Ayni dizi birden fazla bolumle gelebilir -> tekille
        return sonuc.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // * 2026-11-04 KRITIK TASARIM DEGISIKLIGI (olculdu, t107 "Konu Bulunamadı"):
        // *   /dizi/<slug>                  -> 404
        // *   /dizi/<slug>/1-sezon/1-bolum -> 200 ama ICINDE YALNIZCA TVEpisode JSON-LD var
        // *        (+ "Son Bolumler" listesinde 6 link). TVSeason/TVSeries YOK.
        // *        -> "JSON-LD: 0 sezon, 0 bolum" -> RVM episodes bos -> "Çok yakında".
        // *   /dizi/<slug>-dizi-izle        -> 200, TVSeries + containsSeason[].episode[] TAM liste.
        // * Bolum URL'si geldiyse seri sayfasina gecip bolum listesini oradan aliyoruz; bolum
        // * listesindeki data alanlari gercek /dizi/<slug>/<s>-sezon/<b>-bolum URL'leri kalir,
        // * yani loadLinks tarafi degismiyor.
        val bolumSayfasi = url.contains("/dizi/") && Regex("""/\d+-sezon/\d+-bolum""").containsMatchIn(url)
        val seriSlug = url.split("/dizi/").lastOrNull()?.split("/")?.take(1)?.firstOrNull()
        val seriUrl = seriSlug?.let { "${mainUrl}/dizi/${seriIzi(it)}" } ?: url

        val document = app.get(
            if (bolumSayfasi) seriUrl else url,
            interceptor = interceptor, headers = getHeaders(mainUrl), timeout = 20000L
        ).document

        // * Yeni tasarimda baslik: <h1>4. Bölüm — 10 Bin Adım (10 Bin Adim) 3. Sezon 4. Bölüm İzle</h1>
        val h1 = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("img[class*=poster]")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val score = document.selectXpath("//*[contains(text(),'IMDB')]/following-sibling::*[1]")
            .text().trim().ifBlank { null }?.let { Score.from10(it) }

        if (url.contains("/dizi/")) {
            // * Seri adini h1'den turet: "4. Bölüm — 10 Bin Adım (10 Bin Adim) 3. Sezon 4. Bölüm İzle"
            val temiz = h1.substringAfter("— ").substringBefore(" (")
                .ifBlank { h1.substringBefore(" izle") }
                .ifBlank { "Bölüm" }
                .trim()
            val seriKok = seriUrl

            // * 2026-11-01 KRITIK: sayfada yalnizca ~5 "son bolum" linki var; TUM sezon/bolum
            // * listesi JSON-LD (schema.org TVSeason) icinde gomulu. Ornek: The Mentalist
            // * -> 7 sezon, her biri numberOfEpisodes + episode[].url. Bu blok olmadan
            // * kullanici sadece ilk 5 bolumu goruyordu (liste bos geliyordu).
            val bolumler = mutableListOf<Episode>()

            // 1) JSON-LD: TVSeason -> episode[]
            // * Sayfada BIRDEN FAZLA ld+json blogu var; birlestirip parse etmek GECERSIZ JSON
            // * olur. Her blok ayri ayri cozulur.
            run {
                val bloklar = document.select("script[type=application/ld+json]")
                    .map { it.data().trim() }
                    .filter { it.isNotBlank() }
                var toplamSezon = 0
                var eklenen = 0

                fun sezonlariTopla(o: Any?, hedef: MutableList<JSONObject>) {
                    when (o) {
                        is JSONObject -> {
                            when (o.optString("@type")) {
                                "TVSeason" -> hedef.add(o)
                                "TVSeries" -> {
                                    // * 2026-11-01: containsSeason TEK nesne ya da DIZI olabilir.
                                    // * Sadece optJSONArray kullanmak tek sezonlu dizilerde 0
                                    // * sonuc veriyordu (nocturne, balaraw). Ikisini de destekle.
                                    val cs = o.opt("containsSeason")
                                    when (cs) {
                                        is org.json.JSONArray -> for (i in 0 until cs.length()) {
                                            cs.optJSONObject(i)?.let { hedef.add(it) }
                                        }
                                        is JSONObject -> hedef.add(cs)
                                    }
                                }
                            }
                            o.optJSONArray("@graph")?.let { arr ->
                                for (i in 0 until arr.length()) sezonlariTopla(arr.opt(i), hedef)
                            }
                        }
                        is org.json.JSONArray -> {
                            for (i in 0 until o.length()) sezonlariTopla(o.opt(i), hedef)
                        }
                    }
                }

                for (blok in bloklar) {
                    val sezonlar = mutableListOf<JSONObject>()
                    try {
                        val kok: Any = if (blok.startsWith("[")) org.json.JSONArray(blok) else JSONObject(blok)
                        sezonlariTopla(kok, sezonlar)
                    } catch (e: Exception) {
                        Log.e("DiziPalOriginal", "JSON-LD blok cozulemedi: ${e.message}")
                        continue
                    }
                    toplamSezon += sezonlar.size
                    for (sz in sezonlar) {
                        val szNo = sz.optInt("seasonNumber", 0).takeIf { it > 0 }
                        val eps = sz.optJSONArray("episode") ?: continue
                        for (i in 0 until eps.length()) {
                            val ep = eps.optJSONObject(i) ?: continue
                            val epUrl = fixUrlNull(ep.optString("url").trim()) ?: continue
                            if (!epUrl.contains("-sezon")) continue
                            val s = szNo
                                ?: Regex("""/(\d+)-sezon/""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                            val b = ep.optInt("episodeNumber", 0).takeIf { it > 0 }
                                ?: Regex("""/(\d+)-bolum""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                            if (s == null || b == null) continue
                            val ad = ep.optString("name").trim().ifBlank { "$b. Bölüm" }
                            bolumler.add(newEpisode(epUrl) {
                                this.name    = ad
                                this.season  = s
                                this.episode = b
                            })
                            eklenen++
                        }
                    }
                }
                Log.d("DiziPalOriginal", "JSON-LD: $toplamSezon sezon, $eklenen bolum")
            }

            // 2) Yedek: sayfadaki bolum linkleri (JSON-LD yoksa)
            if (bolumler.isEmpty()) {
                bolumler.addAll(document.select("a[href*=/dizi/]").mapNotNull { el ->
                    val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                    val sezon = Regex("""/(\d+)-sezon/""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    val bolum = Regex("""/(\d+)-bolum/?""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    if (sezon == null || bolum == null) return@mapNotNull null
                    newEpisode(href) {
                        val aria = el.attr("aria-label").trim()
                        this.name    = aria.ifBlank { el.attr("title").removeSuffix(" izle").trim() }
                            .ifBlank { "Bölüm $bolum" }
                        this.season  = sezon
                        this.episode = bolum
                    }
                })
            }

            val sirali = bolumler.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(temiz, seriKok, TvType.TvSeries, sirali) {
                this.posterUrl = poster
                this.plot      = description
                this.score     = score
            }
        }

        // * Film detay sayfasi
        val filmBaslik = h1.substringBefore(" izle").ifBlank { document.title() }
        return newMovieLoadResponse(filmBaslik, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.score     = score
        }
    }

    // 2. LOAD LINKS: 2026-11-03 TAMAMEN YENI AKIS (olculdu, tarayici + urllib dogrulandi)
    //
    //  ESKI (artik YOK): div[data-rm-k=true] -> AES/PBKDF2 -> iframe
    //  YENI (Next.js RSC):
    //    1) Bolum sayfasinda "enc":{ciphertext,iv,salt} + "k":"<passphrase>" alanlari var
    //       (JSON icinde tek seviye kacisli: \"ciphertext\" -> once \\" -> " normalizasyonu)
    //    2) PBKDF2WithHmacSHA512(passphrase, salt, 999 iter, 256 bit) -> AES-256-CBC(iv)
    //       sifreli metin Base64. Sonuc iframe URL'i (//four.dplayer82.site/iframe.php?v=...)
    //    3) o iframe -> window.NP_CONFIG.sources[0].master (nm.php?v=...)
    //    4) master -> #EXT-X-STREAM-INF varyantlari (720p / 1080p) nl.php?v=...
    //  olculen: master 2197 B, varyant manifest 33824 B, segmentler lkm-*.com/f/...
    //
    //  NOT: Playwright GEREKMEZ; saf HTTP ile calisir (Android CloudStream icinde de).
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPalOriginal", "--> loadLinks: $data")

        val sayfa = app.get(data, interceptor = interceptor, headers = getHeaders(mainUrl), timeout = 20000L).text
        // * RSC payload'i JSON icinde kacisli; tek seviye normalizasyon yeterli
        val norm = sayfa.replace("\\\"", "\"")

        val ciphertext = Regex(""""ciphertext"\s*:\s*"([^"]+)"""").find(norm)?.groupValues?.get(1)
        val ivHex      = Regex(""""iv"\s*:\s*"([0-9a-fA-F]{32})"""").find(norm)?.groupValues?.get(1)
        val saltHex    = Regex(""""salt"\s*:\s*"([0-9a-fA-F]{64,})"""").find(norm)?.groupValues?.get(1)
        val passphrase = Regex(""""k"\s*:\s*"([A-Za-z0-9+/=]{40,})"""").find(norm)?.groupValues?.get(1)

        if (ciphertext == null || ivHex == null || saltHex == null || passphrase == null) {
            Log.e("DiziPalOriginal", "--> enc/k alanlari bulunamadi (tasarim degismis olabilir)")

            // * Yedek: eski sifreli div hala varsa onu dene
            val eskiMetin = Jsoup.parse(sayfa).selectFirst("div[data-rm-k=true]")?.text() ?: ""
            if (eskiMetin.isNotEmpty()) {
                val eskiUrl = decryptEski(eskiMetin)
                if (eskiUrl.isNotEmpty()) return oynat(eskiUrl, data, subtitleCallback, callback)
            }
            return false
        }

        val iframeUrl = try {
            val salt = saltHex.decodeHex()
            val iv = ivHex.decodeHex()
            val sifre = android.util.Base64.decode(ciphertext, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secret = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            var url = String(cipher.doFinal(sifre), Charsets.UTF_8).replace("\\/", "/").trim()
            url = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("://") -> "https$url"
                !url.startsWith("http") -> "https://$url"
                else -> url
            }
            Log.d("DiziPalOriginal", "--> sifre cozuldu: $url")
            url
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> sifre cozme hatasi: ${e.message}")
            ""
        }

        if (iframeUrl.isEmpty()) return false
        return oynat(iframeUrl, data, subtitleCallback, callback)
    }

    // * dplayer iframe -> NP_CONFIG.sources[].master -> master m3u8 varyantlari
    private suspend fun oynat(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframeHtml = try {
            app.get(iframeUrl, headers = getHeaders(referer), timeout = 20000L).text
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> iframe alinamadi: ${e.message}")
            return false
        }

        // * 2026-11-03 OLCUM: dplayer82.site/multiplayer.php artik NP_CONFIG uretmiyor.
        // * Yeni yuk (361 KB) dogrudan player scripti + "apiUrl" / "sources:[{file:...}]":
        // *   apiUrl: https://dplayer82.site/source2.php?v=UzFsMDZ...
        // *   sources:[{file:"https://dplayer82.site/master.m3u8?v=cHVQSnhE..."}]
        // * Eski NP_CONFIG hala cikarsa onu kullaniyoruz (geriye donuk uyum).

        val cfgMetni = Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\})\s*;?\s*\n""", RegexOption.DOT_MATCHES_ALL)
            .find(iframeHtml)?.groupValues?.get(1)
            ?: Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(iframeHtml)?.groupValues?.get(1)

        if (cfgMetni != null) {
            val npSonuc = npConfigCoz(cfgMetni, iframeUrl, subtitleCallback, callback)
            if (npSonuc) return true
            Log.w("DiziPalOriginal", "--> NP_CONFIG ise yaramadi, yeni akis denenecek")
        } else {
            Log.d("DiziPalOriginal", "--> NP_CONFIG yok (yeni player yuku, ${iframeHtml.length} bayt)")
        }

        // * YENI AKIS: player yukunden dogrudan kaynak adresi cikar
        if (yeniAkisCoz(iframeHtml, iframeUrl, subtitleCallback, callback)) return true

        // * Son sans: iframe'i extractora ver
        Log.w("DiziPalOriginal", "--> kaynak cikarilamadi, extractora devrediliyor")
        DizipalPlayer().getUrl(iframeUrl, referer, subtitleCallback, callback)
        return true
    }

    // * ESKI akis: window.NP_CONFIG = {"sources":[{"master":"...","quality":"..."}]}
    private suspend fun npConfigCoz(
        cfgMetni: String,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var bulundu = false
        try {
            val cfg = JSONObject(cfgMetni)
            val kaynaklar = cfg.optJSONArray("sources") ?: return false
            for (i in 0 until kaynaklar.length()) {
                val kaynak = kaynaklar.optJSONObject(i) ?: continue
                val master = kaynak.optString("master").trim()
                if (master.isEmpty()) continue

                val varyantlar = masterVaryantlari(master, iframeUrl)
                if (varyantlar.isEmpty()) continue

                val kalite = kaynak.optString("quality").ifBlank { "Orijinal" }
                for ((sira, v) in varyantlar.withIndex()) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = if (varyantlar.size > 1) "${this.name} · $kalite #$sira" else "${this.name} · $kalite",
                            url = v,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = iframeUrl
                            this.quality = if (v.contains("1080")) 1080 else if (v.contains("720")) 720 else 0
                        }
                    )
                }
                bulundu = true
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> NP_CONFIG parse hatasi: ${e.message}")
        }
        return bulundu
    }

    // * YENI akis: player yukunden "sources:[{file:..}]" / "apiUrl" / "master.m3u8" cikar
    private suspend fun yeniAkisCoz(
        iframeHtml: String,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // * 2026-11-04: CloudStream'in KENDI calisan extractoru "DPlayer (Auto)" = ContentX.
        // * ContentXExtractor.kt'yi baksmali ile soktum; gercek zinciri su:
        // *   1) iframe HTML icinde  window.openPlayer('<VID>')  -> VID
        // *   2) GET  https://dplayer82.site/source2.php?v=<VID>   (mainUrl + "/source2.php?v=" + VID)
        // *   3) cevap KACISLI JSON:  \"file\":\"<m3u8>\",\"label\":\"<etiket>\"
        // *   4) her eslesme icin ExtractorLink(referer = iframeUrl, quality = Unknown)
        // *   5) ek olarak  ,\"<vtt>\",\"Türkçe...\"  -> altyazi
        // * Onceki halimiz normallesmis metinde "sources:[{file:..}]" ariyordu; yeni yukte
        // * boyle bir sey YOK, o yuzden hep "kaynak bulunamadi" diyordu.
        val norm = iframeHtml.replace("\\/", "/")

        val kaynaklar = mutableListOf<String>()
        val gorulen = mutableSetOf<String>()

        /** source2.php cevabini (veya herhangi bir player yukunu) ayristirir. */
        fun ayristir(metin: String, etiketOnek: String) {
            // * 2026-11-04 GERCEK GOVDE (source2.php, 2769 B):
            // *   {"state":true,"expired":false,"playlist":[{"sources":[
            // *     {"default":false,"type":"hls","title":"Türkçe Altyazı",
            // *      "mimeType":"application/vnd.apple.mpegurl",
            // *      "file":"https:\/\/dplayer82.site\/m.php?v=cHVQ..."}]}]}
            // * Yani: normal JSON, sadece "\\/" kacisli. Dosya adi m.php / master.m3u8
            // * olabiliyor -> ".m3u8" sartı KOYMAYACAGIZ.
            val desen = Regex(""""file"\s*:\s*"([^"]+)"""")
            desen.findAll(metin).forEach { eslesme ->
                var adres = eslesme.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\", "")
                    .trim()
                if (adres.isBlank()) return@forEach
                adres = when {
                    adres.startsWith("//") -> "https:$adres"
                    !adres.startsWith("http") -> "https://$adres"
                    else -> adres
                }
                if (gorulen.add(adres)) {
                    Log.d("DiziPalOriginal", "--> kaynak bulundu ($etiketOnek): $adres")
                    kaynaklar.add(adres)
                }
            }
        }

        // * ADIM 1: window.openPlayer('<VID>')
        val videoId = Regex("""window\.openPlayer\(\s*['"]([^'"]+)['"]""")
            .find(norm)?.groupValues?.get(1)
        Log.d("DiziPalOriginal", "--> openPlayer id: $videoId")

        // * ADIM 2: <mainUrl>/source2.php?v=<VID>
        if (!videoId.isNullOrBlank()) {
            // * dikkat: substringBefore("/") "https:" birakir -> sema + host'u elle ayir
            val taban = run {
                val semaSon = iframeUrl.indexOf("://")
                if (semaSon <= 0) return@run mainUrl
                val kalan = iframeUrl.substring(semaSon + 3)
                val host = kalan.substringBefore("/")
                if (host.isBlank()) mainUrl else "${iframeUrl.substring(0, semaSon)}://$host"
            }
            val tunel = "$taban/source2.php?v=$videoId"
            Log.d("DiziPalOriginal", "--> source2: $tunel")
            try {
                val cevap = app.get(tunel, headers = getHeaders(iframeUrl), timeout = 20000L).text
                Log.d("DiziPalOriginal", "--> source2 cevabi ${cevap.length} bayt")
                ayristir(cevap, "source2")
            } catch (e: Exception) {
                Log.e("DiziPalOriginal", "--> source2 alinamadi: ${e.message}")
            }
        }

        // * ADIM 3: yukun kendisinde zaten m3u8 varsa onlari da al
        ayristir(norm, "")
        ayristir(norm, "gömülü")

        if (kaynaklar.isEmpty()) {
            Log.e("DiziPalOriginal", "--> yeni akista kaynak bulunamadi")
            return false
        }

        // * m.php?v=... bir MASTER manifest. Iceriginden varyantlari cikar.
        val nihaiListe = mutableListOf<Pair<String, ExtractorLinkType>>()
        for (aday in kaynaklar) {
            val tur = if (aday.contains(".m3u8")) ExtractorLinkType.M3U8
            else if (aday.contains(".mp4")) ExtractorLinkType.VIDEO
            else ExtractorLinkType.M3U8

            if (aday.contains("master.m3u8") || Regex("""/m\.php\?""").containsMatchIn(aday)) {
                val varyantlar = masterVaryantlari(aday, iframeUrl)
                Log.d("DiziPalOriginal", "--> master varyant sayisi: ${varyantlar.size}")
                for (v in varyantlar) nihaiListe.add(v to tur)
            } else {
                nihaiListe.add(aday to tur)
            }
        }

        for ((sira, cift) in nihaiListe.withIndex()) {
            val (url, tur) = cift
            callback(
                newExtractorLink(
                    source = this.name,
                    name = if (nihaiListe.size > 1) "${this.name} · Sunucu #${sira + 1}" else "${this.name} · Ana Sunucu",
                    url = url,
                    type = tur
                ) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // * Altyazilar:  ,\"<adres>\"  ,\"Türkçe...\"  (ContentX ile ayni desen)
        Regex(""",\\"([^\']+)\\",\\"Türkçe""").findAll(norm).forEach { eslesme ->
            val adres = fixUrl(eslesme.groupValues[1].replace("\\", ""))
            subtitleCallback(SubtitleFile(lang = "Türkçe", url = adres))
        }

        return true
    }

    // * ".../ujgwfx4lhhpt_,n,h,.urlset/master.m3u8?v=..." -> ".../ujgwfx4lhhpt_n/index-v1-a1.m3u8"
    private fun masterVaryantTuret(master: String): String? {
        return try {
            val kok = master.substringBefore("/master.m3u8")
            if (!kok.contains("/")) return null
            val yol = kok.substringBeforeLast("/")
            val dosya = kok.substringAfterLast("/")
            val anahtar = dosya.substringBefore("_,")
            if (anahtar.isEmpty() || anahtar == dosya) return null
            "$yol/${anahtar}_n/index-v1-a1.m3u8"
        } catch (e: Exception) {
            null
        }
    }

    // * master manifest -> #EXT-X-STREAM-INF varyantlari (yoksa master'in kendisi)
    private suspend fun masterVaryantlari(master: String, iframeUrl: String): List<String> {
        val varyantlar = mutableListOf<String>()
        try {
            val man = app.get(master, headers = getHeaders(iframeUrl), timeout = 20000L).text
            if (man.contains("#EXT-X-STREAM-INF")) {
                val satirlar = man.lines()
                for (j in satirlar.indices) {
                    if (satirlar[j].startsWith("#EXT-X-STREAM-INF")) {
                        val sonraki = satirlar.getOrNull(j + 1)?.trim().orEmpty()
                        if (sonraki.isNotEmpty() && !sonraki.startsWith("#")) {
                            varyantlar.add(if (sonraki.startsWith("http")) sonraki else fixUrl(sonraki))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> master alinamadi: ${e.message}")
        }
        if (varyantlar.isEmpty()) {
            masterVaryantTuret(master)?.let { varyantlar.add(it) } ?: varyantlar.add(master)
        }
        return varyantlar
    }

    // 3. ESKI SIFRE COZME (geriye donuk yedek): div[data-rm-k=true]
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptEski(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return ""
            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return ""
            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return ""

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secret = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            var finalUrl = String(cipher.doFinal(ciphertext), Charsets.UTF_8).replace("\\/", "/")
            finalUrl = when {
                finalUrl.startsWith("://") -> "https$finalUrl"
                finalUrl.startsWith("//") -> "https:$finalUrl"
                !finalUrl.startsWith("http") -> "https://$finalUrl"
                else -> finalUrl
            }
            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> eski decrypt hatasi: ${e.message}")
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to baseUrl
        )
    }
}

