// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

// TODO: sınıf adını siteye göre yeniden adlandır (klasör adıyla aynı olmalı)
class YeniSite : MainAPI() {
    override var mainUrl              = "https://YENI_SITE_URL"   // TODO: gerçek adres
    override var name                 = "YeniSite"                // TODO: uygulamada görünecek ad
    override val hasMainPage          = true
    override var lang                 = "tr"
    override var hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)       // TvType.TvSeries / Anime / Live ...

    // TODO: site kategorilerine göre doldur (birinci sayfa bu listeden gelir)
    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aksiyon/"   to "Aksiyon",
        "${mainUrl}/tur/dram/"      to "Dram",
        "${mainUrl}/tur/komedi/"    to "Komedi",
        "${mainUrl}/tur/belgesel/"  to "Belgesel"
    )

    // ============================ 1) ANA SAYFA ============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}").document
        // TODO: CSS seçiciyi sitenin kart yapısına göre değiştir
        val home     = document.select("div.kart").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    // ============================ 2) ARAMA ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        // TODO: CSS seçiciyi arama sonuç kartına göre değiştir
        return document.select("div.sonuc").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================ 3) DETAY SAYFASI ============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.aciklama")?.text()?.trim()
        val year        = document.selectFirst("span.yil")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("div.turler a").map { it.text() }
        val score       = document.selectFirst("span.imdb")?.text()?.trim()?.let { Score.from10(it) }
        val duration    = document.selectFirst("span.sure")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val actors      = document.select("div.oyuncular a").map { Actor(it.text()) }
        val trailer     = document.selectFirst("iframe")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
            this.duration  = duration
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // ============================ 4) VİDEO LİNKLERİ ============================
    // Sitenin en zor kısmı: izleyici sayfasındaki iframe/kaynakları bulup
    // gerçek .m3u8/.mp4 adresine çevirmek.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("YNST", "data » $data")
        val document = app.get(data).document

        // TODO: video oynatıcılarının bulunduğu seçiciye göre değiştir ("iframe" veya "div.player iframe")
        document.select("iframe").forEach { iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            val src = fixUrlNull(raw) ?: return@forEach

            // Yaygın video hostları için yerleşik extractor:
            loadExtractor(src, "${mainUrl}/", subtitleCallback, callback)

            // Kendi API'si olan sitelerde (JSON kaynak döndürüyorsa) doğrudan da:
            // val source = app.get(src).parsedSafe<GetSource>() ?: return@forEach
            // source.sources?.forEach { s ->
            //     callback.invoke(newExtractorLink(source = name, name = name, url = fixUrl(s.src),
            //         type = ExtractorLinkType.M3U8) {
            //         headers = mapOf("Referer" to "${mainUrl}/")
            //         quality = getQualityFromName(s.label)
            //     })
            // }
            // source.subtitle?.let { subtitleCallback.invoke(SubtitleFile(lang = "Türkçe", url = fixUrl(it))) }
        }

        return true
    }
}
