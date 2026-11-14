// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import java.net.URLEncoder
import org.jsoup.nodes.Element

class DiziMom : MainAPI() {
    // * 2026-11-05: Site tamamen yenilendi (Laravel + Tailwind). Eski WordPress
    // * yapisi (div.single-item / div.episode-box / div.bolumust) artik YOK.
    // * Yeni adres ddizimom.net; www.dizidad.com artik Let's Encrypt YES2 zinciri
    // * yuzunden cihazda dogrulanamiyor, bu yuzden orasi kullanilmiyor.
    override var mainUrl              = "https://ddizimom.net"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    /*
     * Ana sayfa bolumleri — hepsi tek bir HTML dosyasindan (ana sayfa) geliyor.
     * Siralari kullanici tarafindan belirtildi:
     *   Son Eklenen Bolumler, Son Eklenen Diziler, Trend Diziler,
     *   Son Eklenen Filmler, Trend Filmler
     * Sonra platform bolumleri geliyor.
     */
    private val homeBolumleri = listOf(
        "Son Eklenen Bölümler",
        "Son Eklenen Diziler",
        "Trend Diziler",
        "Son Eklenen Filmler",
        "Trend Filmler",
    )

    private val platformlar = listOf(
        "Netflix"          to "$mainUrl/diziler?platform=netflix&ulke=&yil=&sirala=yeni",
        "Tüm Diziler"      to "$mainUrl/diziler?platform=&ulke=&yil=&sirala=yeni",
        "Paramount Plus"   to "$mainUrl/diziler?platform=paramount-plus&ulke=&yil=&sirala=yeni",
        "Mubi"             to "$mainUrl/platform/mubi?ulke=&yil=&sirala=yeni",
        "TOD"              to "$mainUrl/diziler?platform=tod&ulke=&yil=&sirala=yeni",
        "Disney+"          to "$mainUrl/diziler?platform=disney&ulke=&yil=&sirala=yeni",
        "TV+"              to "$mainUrl/diziler?tur=&platform=tv-plus&ulke=&yil=&sirala=yeni",
        "Tabii"            to "$mainUrl/diziler?platform=tabii&ulke=&yil=&sirala=yeni",
        "Hulu"             to "$mainUrl/diziler?tur=&platform=hulu&ulke=&yil=&sirala=yeni",
        "Exxen"            to "$mainUrl/diziler?tur=&platform=exxen&ulke=&yil=&sirala=yeni",
        "BluTV"            to "$mainUrl/diziler?tur=&platform=blutv&ulke=&yil=&sirala=yeni",
        "Amazon Prime"     to "$mainUrl/diziler?tur=&platform=amazon-prime&ulke=&yil=&sirala=yeni",
    )

    // * mainPageOf(url to ad) bekler — sira KRITIK.
    override val mainPage = mainPageOf(
        *homeBolumleri.map { ("home:$it") to it }.toTypedArray(),
        *platformlar.map { (ad, url) -> "url:$url" to ad }.toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tur = request.data

        if (tur.startsWith("home:")) {
            // * Yerel bolumler: her zaman ana sayfadan okunur (sayfalama yok).
            if (page > 1) return newHomePageResponse(request.name, emptyList())

            val etiket  = tur.removePrefix("home:")
            val anaDoku = app.get(mainUrl, headers = headers).document
            val kartlar = bolumKartlari(anaDoku, etiket)

            Log.d("DZM", "ana sayfa » '$etiket' -> ${kartlar.size} kart")

            return newHomePageResponse(request.name, kartlar)
        }

        // * Platform bolumleri: ?page=N ile sayfalanir.
        val temel = tur.removePrefix("url:")
        val sayfa = if (page <= 1) temel else "$temel&page=$page"
        val doku  = app.get(sayfa, headers = headers).document
        val liste = listKartlari(doku)

        Log.d("DZM", "platform » '$sayfa' -> ${liste.size} kart")

        return newHomePageResponse(request.name, liste)
    }

    /**
     * Ana sayfadaki bir basligin altindaki kartlari toplar.
     * Her baslik <h2 class="section-title">X</h2> ile baslar; bir sonraki
     * section-title'a kadar olan <a class="group block"> etiketleri o basligin
     * kartlaridir.
     */
    private fun bolumKartlari(doku: org.jsoup.nodes.Document, etiket: String): List<SearchResponse> {
        val basliklar = doku.select("h2.section-title")
        val hedef     = basliklar.firstOrNull { it.text().trim() == etiket } ?: return emptyList()

        // Bu basligin kapsayicisi: en yakin <section>
        val kapsayici = hedef.parents().firstOrNull { it.tagName() == "section" }
        val kartlar   = if (kapsayici != null) {
            // Ilk section-title'dan sonrasini al; sonraki section'lar zaten disarida.
            kapsayici.select("a.group.block")
        } else {
            // Guvenli yol: basliktan sonraki ilk section'in kartlari
            var sonraki = hedef.nextElementSibling()
            while (sonraki != null && sonraki.tagName() != "section") {
                sonraki = sonraki.nextElementSibling()
            }
            sonraki?.select("a.group.block") ?: emptyList()
        }

        return kartlar.mapNotNull { it.kart() }.distinctBy { it.url }
    }

    /** /diziler ve /filmler gibi liste sayfalarindaki kartlar. */
    private fun listKartlari(doku: org.jsoup.nodes.Document): List<SearchResponse> =
        doku.select("a.group.block").mapNotNull { it.kart() }.distinctBy { it.url }

    /**
     * Kart yapisi (ana sayfa ve liste sayfalari ayni):
     *   <a href=".../dizi/slug" class="group block">
     *     <div class="... aspect-[2/3] ..."><img src=".../posters/w342/x.webp" alt="Gibi Dizi izle" ...></div>
     *     ...
     *     <h3 class="truncate ...">Gibi</h3>
     *     <p class="mt-0.5 text-xs text-content-muted">2021</p>
     *   </a>
     */
    private fun Element.kart(): SearchResponse? {
        val href = this.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null

        val img = this.selectFirst("img")
        val alt = img?.attr("alt")?.trim().orEmpty().removeSuffix(" izle").trim()

        val ad = this.selectFirst("h3")?.text()?.trim()
            ?: alt.takeIf { it.isNotBlank() }
            ?: return null

        val gorsel = img?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.trim()?.takeIf { it.isNotBlank() }

        val yil = this.selectFirst("p")?.text()?.trim()?.toIntOrNull()
        val dizi = href.contains("/dizi/")

        return if (dizi) {
            newTvSeriesSearchResponse(ad, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(gorsel)
                this.year      = yil
            }
        } else {
            newMovieSearchResponse(ad, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrlNull(gorsel)
                this.year      = yil
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")

        // * Site arama ucu: /ara?q=  (2026-11-05 dogrulandi; /arama ve /arama-yap 404)
        val doku  = app.get("$mainUrl/ara?q=$q", headers = headers).document
        val sonuc = listKartlari(doku)

        Log.d("DZM", "arama » '$query' -> ${sonuc.size}")
        if (sonuc.isNotEmpty()) return sonuc

        // * Yedek: /diziler?q= uzerinden dene
        val yedek = runCatching {
            app.get("$mainUrl/diziler?q=$q&sirala=yeni", headers = headers).document
        }.getOrNull()?.let { listKartlari(it) } ?: emptyList()

        Log.d("DZM", "arama yedek » ${yedek.size}")
        return yedek

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doku = app.get(url, headers = headers).document

        val baslik = doku.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")
            ?: return null

        val gorsel = doku.selectFirst("img[src*=/posters/]")?.attr("src")?.trim()
            ?: doku.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        val aciklama = doku.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doku.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val yil = Regex("""\b(19|20)\d{2}\b""")
            .find(doku.select("a[href*=/yil/]").joinToString(" ") { it.text() })
            ?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doku.html())?.value?.toIntOrNull()

        // * Turler: /tur/<slug> baglantilari
        val etiketler = doku.select("a[href*=/tur/]").mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        // * Oyuncular: JSON-LD actor listesinden
        val oyuncular = Regex(""""actor"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(doku.html())?.groupValues?.get(1)
            ?.let { Regex(""""name"\s*:\s*"([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?: emptyList()

        val filmMi = url.contains("/film/") && !url.contains("/dizi/")

        if (filmMi) {
            return newMovieLoadResponse(baslik, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(gorsel)
                this.year      = yil
                this.plot      = aciklama
                this.tags      = etiketler
                addActors(oyuncular.map { Actor(it) })
            }
        }

        // * Dizi: sezon secenekleri ?sezon=N#bolumler ile gelir.
        val sezonlar = doku.select("a[href*=\"sezon=\"]")
            .mapNotNull { Regex("""sezon=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()

        val bolumler = mutableListOf<Episode>()

        for (sezon in sezonlar.ifEmpty { listOf(1) }) {
            val sezonDoku = if (sezonlar.isEmpty()) {
                doku
            } else {
                app.get("$url?sezon=$sezon#bolumler", headers = headers).document
            }

            val satirlar = sezonDoku.select("a[href*=\"/sezon-$sezon/bolum-\"]")
            Log.d("DZM", "sezon $sezon -> ${satirlar.size} bolum")

            for (satir in satirlar) {
                val epHref = fixUrlNull(satir.attr("href")) ?: continue
                val no = Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                bolumler.add(
                    newEpisode(epHref) {
                        this.name    = satir.selectFirst("img")?.attr("alt")?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: "$sezon. Sezon $no. Bölüm"
                        this.season  = sezon
                        this.episode = no
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(baslik, url, TvType.TvSeries, bolumler) {
            this.posterUrl = fixUrlNull(gorsel)
            this.year      = yil
            this.plot      = aciklama
            this.tags      = etiketler
            addActors(oyuncular.map { Actor(it) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZM", "data » $data")

        val doku = app.get(data, headers = headers).document

        // * 1) Pilavyer embed: <div data-pv="SLUG"> + https://pilavyerplay.top/assets/js/core.js
        // *    core.js, /assets/js/s.php?s=SLUG adresini iframe'e kurar; oradaki
        // *    window.__PLAYER__.stream = /api/stream.php?v=..&token=.. master m3u8'dir.
        val slug = Regex("""data-pv="([^"]+)"""")
            .find(doku.html())?.groupValues?.get(1)

        val pilavyerSunucu = Regex("""https://([a-z0-9.-]*pilavyer[^/"]*)/assets/js/core\.js""")
            .find(doku.html())?.groupValues?.get(1)

        if (slug != null && pilavyerSunucu != null) {
            val sunucu  = "https://$pilavyerSunucu"
            val embed   = "$sunucu/assets/js/s.php?s=$slug"
            val playerDocu = runCatching {
                app.get(
                    embed,
                    headers = headers + mapOf("Referer" to "$mainUrl/"),
                    referer = "$mainUrl/"
                ).document
            }.getOrNull()

            if (playerDocu != null) {
                val streamUrl = Regex(""""stream"\s*:\s*"([^"]+)"""")
                    .find(playerDocu.html())?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")

                if (streamUrl != null) {
                    callback(
                        newExtractorLink(
                            source   = name,
                            name     = "$name (Pilavyer)",
                            url      = streamUrl,
                            type     = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$sunucu/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to headers["User-Agent"]!!,
                                "Referer"    to "$sunucu/",
                                "Origin"     to sunucu,
                            )
                        }
                    )
                    Log.d("DZM", "pilavyer m3u8 » $streamUrl")
                    return true
                }
            }
        }

        // * 2) Yedek: sayfadaki dogrudan iframe / m3u8 izleri
        val adaylar = mutableListOf<String>()

        doku.select("iframe[src]").forEach { adaylar.add(it.attr("src")) }

        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            .findAll(doku.html()).forEach { adaylar.add(it.value) }

        var bulundu = false
        for (aday in adaylar.distinct()) {
            if (aday.isBlank()) continue
            Log.d("DZM", "yedek kaynak » $aday")

            if (aday.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = aday,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                bulundu = true
            } else {
                loadExtractor(fixUrl(aday), "$mainUrl/", subtitleCallback, callback)
                bulundu = true
            }
        }

        return bulundu
    }
}
