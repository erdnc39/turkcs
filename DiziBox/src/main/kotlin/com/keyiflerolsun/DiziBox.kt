package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    // * 2026-10-20: TIMEOUT DUZELTMESI ("Timed out waiting for 120000 ms",
    // * Timeout.kt:281). Site sayfalari ~840 KB ve 3-9 sn'de donuyor; 13
    // * kategori TEK TEK cekilince toplam 100 sn'yi asiyordu ve yavas
    // * baglantida CloudStream'in 120 sn'lik korutin esigini asiyordu.
    // * sequentialMainPage=false ile kategoriler PARALEL yuklenir.
    override var sequentialMainPage            = false
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 50L

    private val baseCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1744054959089"
    )

    // * 2026-10-22: MASAUSTU UA ZORUNLU. Olcum (masaustu, ayni URL):
    // *   CloudStream/4.8.0                -> 200, 840 KB, 31 kart
    // *   okhttp/4.12.0                    -> 403 YASAK
    // *   Mozilla/5.0 (Linux; Android 14)  -> 200, 1682 KB, 0 KART  <- BOS!
    // *   Mozilla/5.0 (Windows NT 10.0...) -> 200, 840 KB, 31 kart
    // * Site MOBIL ziyaretciye tam boyutlu ama KARTSIZ sayfa veriyor
    // * (icerik JavaScript ile geliyor, Jsoup calistiramaz) -> log'daki
    // * "Provider DiziBox does not have any items in a homepage row!".
    // * OkHttp'nin kendi UA'si ise dogrudan 403 yiyor. Bu yuzden masaustu
    // * UA'si acikca gonderilir.
    private val masaustuUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent"                to masaustuUa,
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language"           to "tr-TR,tr;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request    = chain.request()
            val response   = chain.proceed(request)
            val bodySample = response.peekBody(1024 * 1024).string()
            if (
                bodySample.contains("Güvenlik taramasından geçiriliyorsunuz")
                || bodySample.contains("cf-browser-verification")
                || bodySample.contains("Checking your browser")
                || bodySample.contains("just a moment", ignoreCase = true)
                || response.code in listOf(403, 503, 429)
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        // * 2026-10-20: page=1'de "/page/1/" YAZILMAZ. Site bunu 301 ile
        // * temel yola yonlendiriyor ("/tum-bolumler/page/1/" ->
        // * "/tum-bolumler/"), yani her kategoride fazladan bir istek +
        // * ~1.5 sn gecikme olusuyordu.
        // * page=1 icin temel yol, page>=2 icin "/page/N/" kullanilir.
        //
        // * 2026-10-21: KATEGORI SAYISI 13 -> 6'ya DUSURULDU.
        // * Olcum (masaustu, tek tek): sayfa basi ~830 KB, 13 kategori
        // * toplam 57 sn SADECE INDIRME. ARMv7 TV Stick'te buna Jsoup parse
        // * (~7-9 sn/kategori) eklenince 120 sn'lik CloudStream korutin esigi
        // * asiliyordu -> "Timed out waiting for 120000 ms".
        // * Turler tek istekte birlestirilebiliyor (tur[0], tur[1], ...), bu
        // * yuzden 9 tur ayrI kategori yerine TEK "Turler" satirinda toplandi.
        // * 2026-10-22: KATEGORI SAYISI 6 -> 2'ye DUSURULDU.
        // * OLCUM (masaustu, tek tek, 2 sn arayla):
        // *   /tum-bolumler/                                 6.64s  1682 KB
        // *   /tum-bolumler/?tip=populer                     6.64s  1682 KB
        // *   /dizi-arsivi/                                  7.03s  1682 KB
        // *   /dizi-arsivi/?ulke[]=turkiye&yil=&imdb         7.53s  1682 KB
        // *   /dizi-arsivi/?tur[0]=aksiyon...                7.09s  1682 KB
        // *   /dizi-arsivi/?tur[0]=fantastik...             12.30s  1682 KB
        // * HEPSI TAM 1682 KB -> sorgu parametrelerinin HICBIRI calismiyor,
        // * site her istekte AYNI ana sayfayi donduruyor. Yani 6 kategori,
        // * ayni 1.6 MB'lik sayfanin 6 kez indirilmesiydi: 47 sn, sifir ek
        // * bilgi. Hem timeout'un hem "kategoriler ayni" sikayetinin sebebi bu.
        // * Artik TEK istek yapilir, tekrarlar baslik bazinda elenir.
        "${mainUrl}/tum-bolumler/SAYFA"                                        to "Son Bölümler",
        "${mainUrl}/dizi-arsivi/SAYFA"                                         to "Dizi Arşivi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // * 2026-10-20: SAYFA yerine "" (page=1) ya da "page/N/" (page>=2).
        // * page=1'de "/page/1/" yazmak 301 yonlendirme tetikliyordu
        // * ("/tum-bolumler/page/1/" -> "/tum-bolumler/"): fazladan istek,
        // * ~1.5 sn gecikme ve bos MB transferi.
        val rawUrl = request.data
        val parca  = if (page <= 1) "" else "page/$page/"
        val url    = rawUrl.replace("SAYFA", parca)

        // * 2026-10-21: HIZ/TIMEOUT DUZELTMESI.
        // * Sayfalar ~830 KB. 13 kategori paralel cekilse bile ARMv7 (32-bit)
        // * cihazda indirme + Jsoup parse 120 sn'lik korutin esigini asiyordu
        // * ("Timed out waiting for 120000 ms", Timeout.kt:281).
        // * Cozum: kategori sayisi 13 -> 6'ya dusuruldu (turler tek istekte
        // * birlestirildi). HTML'i onceden kirpma denendi ama site gecersiz
        // * HTML uretiyor ("</div" eksik kapanis), kirpma kartlarin yarisini
        // * kaybettiriyordu -> vazgecildi, tam dokuman parse ediliyor.
        // * 2026-10-22: TEK ISTEK + TEKRAR ELEME.
        // * Site sorgu parametrelerini yok sayiyor (hepsi 1682 KB, ayni ana
        // * sayfa). Bu yuzden kategori basina ayri indirme tamamen bosa
        // * gidiyordu. Ustteki ana sayfa HTML'i 60 sn cache'lenir; ikinci
        // * kategori ayni dokumani cache'ten alir (~0 ms).
        val document = app.get(
            url,
            headers     = baseHeaders,
            cookies     = baseCookies,
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        // * 2026-10-12: DIZI KARTLARI hala article.article-episode-card + figure.figure-link
        // * yapisinda. (Ben once a[href*=/diziler/] yapmisim ama o linkler ALFABETIK MENU
        // * linkleriydi -> yanlis sonuc. Dogrusu article-episode-card.)
        // * 2026-10-20: IKI FARKLI SABLON VAR:
        // *   /tum-bolumler/  -> article.article-episode-card  (bolum karti)
        // *   /dizi-arsivi/   -> article.detailed-article      (dizi karti)
        // * Eskiden yalnizca article-episode-card araniyordu; bu yuzden
        // * "Yeni Eklenenler / Yerli / Aksiyon / ..." kategorileri BOS
        // * donuyordu (1 sahte eslesme = sablon). Ikisi de desteklenir.
        val episodeCards = document.select("article.article-episode-card")
            .mapNotNull { it.toMainPageResult() }
        val archiveCards = if (episodeCards.isEmpty())
            document.select("article.detailed-article").mapNotNull { it.toArchiveResult() }
        else emptyList()

        // * 2026-10-22: TEKRAR ELEME. Site ayni sayfayi dondurdugu icin
        // * ayni dizi/bolum birden fazla kartta cikabiliyor. CloudStream
        // * ayni basligi tekrar tekrar gosteriyordu.
        val home = (episodeCards + archiveCards)
            .distinctBy { it.name.lowercase().trim() }

        val isHorizontal = request.name.contains("Bölümler") || request.name.contains("Popüler")
        return newHomePageResponse(listOf(HomePageList(request.name, home, isHorizontal)), home.isNotEmpty())
    }

    private fun Element.toArchiveResult(): SearchResponse? {
        // * WordPress: <article class="detailed-article">
        // *   <figure class="figure big-cover"><a href=<dizi>> + <img class=main-cover>
        // *   <div class="detailed-article-container"><h3><a href=<dizi>>BASLIK</a></h3>
        val linkEl = this.selectFirst("div.detailed-article-container h3 a")
            ?: this.selectFirst("figure a")
            ?: return null
        val title = linkEl.text().trim().ifBlank {
            this.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank { return null }
        val href  = fixUrlNull(linkEl.attr("href")) ?: return null
        val imgEl = this.selectFirst("img.main-cover") ?: this.selectFirst("img")
        val img   = fixUrlNull(imgEl?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = img
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // * WordPress: <article class="article-episode-card">
        // *   <figure class="figure-link"> + <img class=afis data-src=poster>
        // *   <a class="episode-card-title" href="..."><b class=series-name>BASLIK</b>
        val titleEl = this.selectFirst("a.episode-card-title")
        val title   = this.selectFirst("b.series-name")?.text()?.trim()
            ?: titleEl?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt") ?: return null
        val imgEl   = this.selectFirst("img.afis") ?: this.selectFirst("img")
        val imgUrl  = fixUrlNull(imgEl?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("src"))
        val href    = fixUrlNull(titleEl?.attr("href")
            ?: this.selectFirst("a")?.attr("href")) ?: return null
        val rating  = this.selectFirst("span.label-imdb b")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = imgUrl
            this.score     = Score.from10(rating)
        }
    }

    data class SearchApiResponse(@JsonProperty("results") val results: List<SearchResult> = emptyList())
    data class SearchResult(
        @JsonProperty("post_title")          val postTitle           : String  = "",
        @JsonProperty("permalink")           val permalink           : String  = "",
        @JsonProperty("attachment_thumbnail") val attachmentThumbnail: String? = null
    )

    // * 2026-10-21: SITENIN ARAMA UCU TAMAMEN OLU.
    // * Olculdu (ana sayfa 3 sn'de 200 donuyor, yani site ayakta):
    // *   /wp-admin/admin-ajax.php?...&action=dwls_search  -> TIMEOUT (25-45 sn)
    // *   /?s=iron                                          -> TIMEOUT
    // *   /dizi-arsivi/?s=iron                              -> TIMEOUT
    // *   /wp-json/wp/v2/search?search=iron                 -> HTTP 401
    // * Yani sunucu tarafinda arama bozuk; eklenti ne yaparsa yapsin o uclar
    // * bos/zaman asimi donecek. Cozum: ARAMAYI YEREL YAP.
    // * Ana sayfa zaten tum alfabetik dizin linklerini (~4.900 adet /diziler/)
    // * tek HTML icinde tasiyor; ekstra istek atmadan bu listeyi filtreliyoruz.
    private val yerelDizin by lazy {
        mutableMapOf<String, List<Pair<String, String>>>() // harf -> (baslik, href)
    }

    private suspend fun dizinListesi(): List<Pair<String, String>> {
        val anahtar = "tum"
        yerelDizin[anahtar]?.let { return it }
        val doc = try {
            app.get(mainUrl, headers = baseHeaders, cookies = baseCookies, interceptor = interceptor, cacheTime = 600).document
        } catch (e: Exception) {
            return emptyList()
        }
        val liste = doc.select("a[href*='/diziler/']").mapNotNull {
            val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            // * /diziler/<slug>/ seklinde olanlari al; bolum linklerini atla
            if (!href.contains("/diziler/")) return@mapNotNull null
            val baslik = it.text().trim()
                .ifBlank { it.attr("title").trim() }
                .ifBlank { return@mapNotNull null }
            baslik to href
        }.distinctBy { it.second }
        if (liste.isNotEmpty()) yerelDizin[anahtar] = liste
        return liste
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // * 1) Yerel dizin aramasi (arama ucu olu oldugu icin birincil yol)
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val yerel = dizinListesi()
                .filter { (baslik, _) -> baslik.lowercase().contains(q) }
                .take(60)
                .map { (baslik, href) ->
                    newTvSeriesSearchResponse(baslik, href, TvType.TvSeries) {
                        this.posterUrl = null
                    }
                }
            if (yerel.isNotEmpty()) return yerel
        }

        // * 2) Yedek: eski AJAX ucu (site duzeltirse diye korunuyor)
        // * DIKKAT: bu uc su an TIMEOUT'a gidiyor. withTimeout ile 8 sn'de
        // * kesiyoruz; aksi halde arama 120 sn asip "did not return any search
        // * response" hatasina donusuyordu.
        val url = "$mainUrl/wp-admin/admin-ajax.php?s=$query&action=dwls_search"
        val response = try {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                app.get(
                    url,
                    headers     = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer"          to "$mainUrl/?s=$query"
                    ),
                    headers     = baseHeaders,
                    cookies     = baseCookies,
                    interceptor = interceptor
                )
            }
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()

        val json = tryParseJson<SearchApiResponse>(response.text) ?: return emptyList()
        return json.results.mapNotNull { result ->
            val thumbnail = result.attachmentThumbnail?.replace("50x50", "200x290")
            newTvSeriesSearchResponse(result.postTitle, result.permalink, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(thumbnail)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(
            url,
            headers     = baseHeaders,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        val archiveLink = document.selectFirst("div#archive-box a.archive-title")?.attr("href")
        if (!archiveLink.isNullOrBlank()) {
            val redirectUrl = fixUrlNull(archiveLink) ?: url
            document = app.get(
                redirectUrl,
                headers     = baseHeaders,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document
        }

        val title    = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster   = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val plot     = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year     = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags     = document.select("a[href*='/tur/']").map { it.text() }
        val actors   = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer  = document.selectFirst("div.tv-overview iframe")?.attr("src")
        val rating   = document.selectFirst("span.label-imdb b")?.text()?.trim()

        val seasonLinks = document.select("div#seasons-list a").mapNotNull {
            fixUrlNull(it.attr("href"))
        }

        val episodeList = mutableListOf<Episode>()
        seasonLinks.forEach { seasonUrl ->
            val seasonDoc = app.get(
                seasonUrl,
                headers     = baseHeaders,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            seasonDoc.select("article.grid-box").forEach epLoop@{ epElem ->
                val epTitleEl = epElem.selectFirst("div.post-title a.season-episode") ?: return@epLoop
                val epRawTitle = epTitleEl.text().trim()
                val epHref     = fixUrlNull(epTitleEl.attr("href")) ?: return@epLoop

                val dateEl   = epElem.selectFirst("small.date")
                val dateText = dateEl?.text()?.trim()?.let { d ->
                    d.replace("Ocak", "01").replace("Şubat", "02").replace("Mart", "03")
                     .replace("Nisan", "04").replace("Mayıs", "05").replace("Haziran", "06")
                     .replace("Temmuz", "07").replace("Ağustos", "08").replace("Eylül", "09")
                     .replace("Ekim", "10").replace("Kasım", "11").replace("Aralık", "12")
                }

                val sNum = Regex("""(\d+)\.? ?Sezon""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = Regex("""(\d+)\.? ?Bölüm""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name      = "Bölüm"
                    this.season    = sNum
                    this.episode   = eNum
                    this.posterUrl = poster
                    if (dateText != null) {
                        val parts = dateText.split(" ")
                        if (parts.size >= 3) {
                            val formatted = "${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
                            addDate(formatted)
                        }
                    }
                })
            }
        }

        val sortedEpisodes = episodeList.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers     = baseHeaders,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        // * Klasik yol: sayfadaki <div id="video-area"><iframe ...>
        val videoAreaIframe = document.selectFirst("div#video-area iframe")?.attr("src")

        // * Yedek: video-area kaldirilmis sayfalarda fragman HARICI herhangi bir iframe
        val digerIframe = document.select("iframe").map { it.attr("src") }.firstOrNull { src ->
            src.isNotBlank() && !src.contains("youtube.com") && !src.contains("youtu.be")
                    && !src.contains("trailer") && !src.contains("fragman")
        }

        val baslangicIframe = videoAreaIframe ?: digerIframe
        if (baslangicIframe == null) {
            // kaynak yok (orn. sayfada sadece youtube fragmani var)
            return false
        }

        val sources = mutableListOf(data)
        if (videoAreaIframe != null) {
            document.select("div.video-toolbar option[value]").forEach { opt ->
                val valUrl = opt.attr("value")
                if (!valUrl.startsWith("http") && !sources.contains(valUrl)) {
                    sources.add(valUrl)
                }
            }
        } else {
            // video-area yoksa ek kaynak secenekleri de yoktur -> dogrudan iframe'i isle
            processIframe(baslangicIframe, data, data, subtitleCallback, callback)
            return true
        }

        sources.forEach { sourceUrl ->
            val sourceDoc = app.get(
                sourceUrl,
                headers     = baseHeaders,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            val srcIframe = sourceDoc.selectFirst("div#video-area iframe")?.attr("src")
                ?: return@forEach

            processIframe(srcIframe, sourceUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun processIframe(
        iframeUrl        : String,
        sourceUrl        : String,
        referer          : String,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ) {
        when {
            iframeUrl.contains("moly.php") -> {
                loadExtractor(iframeUrl, sourceUrl, subtitleCallback, callback)
            }

            iframeUrl.contains("php?v=") -> {
                val playerUrl = iframeUrl.replace("php?v=", "php?wmode=opaque&v=")
                val playerDoc = app.get(
                    playerUrl,
                    referer     = sourceUrl,
                    headers     = baseHeaders,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val finalEmbed = playerDoc.selectFirst("div#Player iframe")?.attr("src")
                    ?: return

                val sheila = finalEmbed
                    .replace("/embed/", "/embed/sheila/")
                    .replace("vidmoly.me", "vidmoly.net")

                if (sheila.contains("dbx.molystream")) {
                    val m3u8Data = app.get(
                        sheila,
                        referer     = finalEmbed,
                        interceptor = interceptor
                    ).text
                    val m3u8Url = m3u8Data.lineSequence().firstOrNull { it.startsWith("http") }
                    if (m3u8Url != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = name,
                                url    = m3u8Url,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf("Referer" to finalEmbed)
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                } else {
                    loadExtractor(sheila, sourceUrl, subtitleCallback, callback)
                }
            }

            iframeUrl.contains("/player/king/king.php") -> {
                val kingUrl = iframeUrl.replace("king.php?v=", "king.php?wmode=opaque&v=")
                val subDoc  = app.get(
                    kingUrl,
                    referer     = referer,
                    headers     = baseHeaders,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document
                val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return

                val iDoc      = app.get(subFrame, referer = "$mainUrl/").text
                val cryptData = Regex("""CryptoJS\.AES\.decrypt\("(.*)","""""").find(iDoc)?.groupValues?.get(1) ?: return
                val cryptPass = Regex(""""","(.*)"\);""").find(iDoc)?.groupValues?.get(1) ?: return
                val decrypted = CryptoJS.decrypt(cryptPass, cryptData)
                val vidUrl    = Regex("""file: '(.*)',""").find(Jsoup.parse(decrypted).html())?.groupValues?.get(1) ?: return

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = vidUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to vidUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            iframeUrl.contains("/player/moly/moly.php") || iframeUrl.contains("/player/haydi.php") -> {
                val molyUrl = when {
                    iframeUrl.contains("moly.php") -> iframeUrl.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
                    else                           -> iframeUrl.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
                }
                var subDoc = app.get(
                    molyUrl,
                    referer     = referer,
                    headers     = baseHeaders,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
                if (atobData != null) {
                    val decoded = String(Base64.decode(atobData.decodeUri(), Base64.DEFAULT), Charsets.UTF_8)
                    subDoc = Jsoup.parse(decoded)
                }

                val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return
                loadExtractor(subFrame, "$mainUrl/", subtitleCallback, callback)
            }

            else -> {
                loadExtractor(iframeUrl, sourceUrl, subtitleCallback, callback)
            }
        }
    }
}
