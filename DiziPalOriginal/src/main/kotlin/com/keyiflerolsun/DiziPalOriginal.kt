// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import okhttp3.Interceptor
import okhttp3.Response
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
    override var mainUrl              = "https://dizipal1822.com"
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
            // * Bolum listesi: <a href="/dizi/slug/sezon/bolum" class="ep-row" title="... izle">
            // * Baslik: <a title="..."> icinden; poster: <img data-src|src> / alt
            home.addAll(document.select("a.ep-row").mapNotNull { it.epRowKarti() })
        } else {
            // * Arsiv sayfalari (/diziler /filmler /anime): poster kartlari
            home.addAll(document.select("a:has(div.poster), a:has(img.poster), div.poster")
                .mapNotNull { it.posterKarti() })
            if (home.isEmpty()) {
                // * yedek: dogrudan poster sinifi tasiyan elemanlar
                home.addAll(document.select("[class*=poster]").mapNotNull { it.posterKarti() })
            }
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    // * Yeni tasarim bolum karti:
    // * <a href="/dizi/slug/3-sezon/4-bolum" class="ep-row ..." title="BASLIK izle">
    // *   <span class="ep-row-img"><img src="..." alt="BASLIK">
    private fun Element.epRowKarti(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null

        val img = this.selectFirst("img")
        val baslik = this.attr("title").removeSuffix(" izle").trim()
            .ifBlank { img?.attr("alt")?.trim() ?: "" }
            .ifBlank { return null }

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        // * Bolum linkinden seri kok adresi: /dizi/slug/sezon/bolum -> /dizi/slug
        val seriUrl = href.split("/dizi/").lastOrNull()?.split("/")?.take(1)?.firstOrNull()
            ?.let { "${mainUrl}/dizi/$it" } ?: href

        // * Sezon/Bolum bilgisi basliktaki "3. Sezon 4. Bölüm" kismindan
        val sezon = Regex("""(\d+)\.\s*Sezon""").find(baslik)?.groupValues?.get(1)
        val bolum = Regex("""(\d+)\.\s*Bölüm""").find(baslik)?.groupValues?.get(1)
        val etiket = if (sezon != null && bolum != null) " (${sezon}x${bolum})" else ""

        return newTvSeriesSearchResponse(baslik + etiket, seriUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // * Arsiv (dizi/film/anime) poster karti
    private fun Element.posterKarti(): SearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: this.closest("a")
        val href = fixUrlNull(a?.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null

        val img = this.selectFirst("img") ?: a?.selectFirst("img")
        val baslik = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: a?.attr("title")?.removeSuffix(" izle")?.trim()?.ifBlank { null }
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
        // * Yeni tasarim: arama artik HTML ile -> /?s=query  (a.ep-row kartlari)
        val document = app.get(
            "${mainUrl}/?s=${query}",
            timeout = 15000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val sonuc = mutableListOf<SearchResponse>()
        sonuc.addAll(document.select("a.ep-row").mapNotNull { it.epRowKarti() })
        if (sonuc.isEmpty()) {
            sonuc.addAll(document.select("[class*=poster]").mapNotNull { it.posterKarti() })
        }
        // * Ayni dizi birden fazla bolumle gelebilir -> tekille
        return sonuc.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = getHeaders(mainUrl)).document

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
            val seriKok = url.split("/dizi/").lastOrNull()?.split("/")?.take(1)?.firstOrNull()
                ?.let { "${mainUrl}/dizi/$it" } ?: url

            // * Tum bolumleri listeden topla (hem ep-row kartlari hem dogrudan linkler)
            val bolumler = document.select("a[href*=/dizi/]").mapNotNull { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val sezon = Regex("""/(\d+)-sezon/""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                val bolum = Regex("""/(\d+)-bolum/?""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (sezon == null || bolum == null) return@mapNotNull null
                newEpisode(href) {
                    this.name    = el.attr("title").removeSuffix(" izle").trim().ifBlank { "Bölüm $bolum" }
                    this.season  = sezon
                    this.episode = bolum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(temiz, seriKok, TvType.TvSeries, bolumler) {
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

    // 2. LOAD LINKS: Asıl şifre çözme ve Iframe yakalama işleminin yapıldığı yer
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPalOriginal", "--> loadLinks ÇAĞRILDI. Gelen URL: $data")
        val doc = app.get(data).document
        
        // Şifreli div'i bul
        val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
        Log.d("DiziPalOriginal", "--> Şifreli metin uzunluğu: ${encryptedText.length}")
        
        var iframeUrl = if (encryptedText.isNotEmpty()) {
            Log.d("DiziPalOriginal", "--> Şifreli veri bulundu, decrypt işlemine geçiliyor...")
            decryptDizipalData(encryptedText)
        } else {
            Log.w("DiziPalOriginal", "--> DİKKAT: Şifreli veri DOM'da YOK! Fallback iframe aranıyor...")
            doc.selectFirst("iframe")?.attr("src") ?: ""
        }

        Log.d("DiziPalOriginal", "--> Elde edilen Ham Iframe URL: $iframeUrl")

        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }
            Log.d("DiziPalOriginal", "--> Extractor'a gönderilen Final URL: $iframeUrl")
            
            // Extractor'ı tetikle
            DizipalPlayer().getUrl(
                url = iframeUrl,
                referer = data, // Videonun bulunduğu sayfa
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } else {
            Log.e("DiziPalOriginal", "--> HATA: iframeUrl tamamen BOŞ. Video linki bulunamadı!")
        }
        return true
    }

    // 3. DECRYPT VE YARDIMCI FONKSİYON: Şifreyi çözen business logic
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'ciphertext' değerini bulamadı!") }
                
            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'iv' değerini bulamadı!") }
                
            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'salt' değerini bulamadı!") }

            Log.d("DiziPalOriginal", "--> Regex başarılı. Key türetiliyor...")

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            Log.d("DiziPalOriginal", "--> AES Çözümleme Başarılı. İlk Çıktı: $finalUrl")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            e.printStackTrace()
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

