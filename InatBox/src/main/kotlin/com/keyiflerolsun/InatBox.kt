package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

class InatBox : MainAPI() {
    // * 2026: yollar /v2 altina tasinmis + istekler HMAC-SHA256 ile imzalanmak zorunda
    private val contentUrl = "https://diziboxen.help/CDN/001/002/dizibox/v2"

    override var name = "InatBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    // * 20 kategori PARALEL cekiliyordu -> sunucu hizi azarlarsa IP banliyor (403/1006) ve
    // * TUM LISTELER BOS kaliyordu. Artik sirayla, araliqli cekiyoruz.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 5000L     // * istekler arasi 5 sn (ban tetiklememek icin)
    override var sequentialMainPageScrollDelay = 100L
    // * NOT: eski tanim `sequentialMainPage = false` buradan silindi (cift tanim derleme hatasi)

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()
    // * kategori bazli yerel onbellek: url -> (zaman, ham json)
    private val sayfaOnbellek = mutableMapOf<String, Pair<Long, String>>()
    private val aesKey = "ywevqtjrurkwtqgz" //Master secret and iv key

    // * 2026: istek imzalama anahtari (HMAC-SHA256) ve sunucu saat ofseti
    private val signingKey = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"
    private var timeOffset = 0L
    private val secureRandom = SecureRandom()
    // * AG ANAHTARI: ban üstüne ban yememek icin kapali.
    // * Ban kaldirildiginda (veya hotspot'te tek cekim yapildiktan sonra) false yapilir.
    private val agKapali = true

    // * Iki istek arasinda beklenecek minimum sure (ms). diziboxen.help hiz limiti
    // * koyuyor: ardisik isteklerde 403/1006 donuyor ve IP banliyor.
    private val ISTEK_ARASI_BEKLEME_MS = 8_000L

    // * Bekleme tek basina yetmiyor: kullanici diziler arasinda hizli gezince
    // * (arama yazarken, kategori degistirirken, bolum atlayinca) istek sayisi
    // * dakikada onlara cikiyor ve sunucu bunu burst olarak gorup banliyor.
    // * Bu yuzden ayrica bir ZAMAN PENCERESI limiti koyuyoruz: 1 dakikada
    // * en fazla N istek. Limit dolunca kalan sure beklenir.
    private val ISTEK_PENCERESI_MS = 60_000L
    private val ISTEK_PENCERESI_LIMITI = 8

    // * Son istegin atildigi zaman (epoch ms). makeInatRequest bunu kullanarak
    // * her istek oncesi gerekirse bekler -> paralel cagirmalar da tek seri akisa duser.
    @Volatile private var sonIstekZamani = 0L

    // * Es zamanli istekleri tek seri akisa indirir. getMainPage + arama + load
    // * ayni anda tetiklenince Mutex olmadan ikisi de ayni "bos" anda geciyi gorup
    // * ikisi de hemen atardi -> burst -> 403/1006.
    private val istekKilidi = Mutex()

    // * Pencere icindeki istek zaman damgalari.
    private val pencereLogu = ArrayDeque<Long>()

    private fun pencereTemizle(simdi: Long) {
        while (pencereLogu.isNotEmpty() && simdi - pencereLogu.first() > ISTEK_PENCERESI_MS) {
            pencereLogu.removeFirst()
        }
    }

    // * Pencere doluysa kalan bekleme suresi (ms), dolu degilse 0.
    private fun pencereBeklemeSuresi(simdi: Long): Long {
        synchronized(pencereLogu) {
            pencereTemizle(simdi)
            if (pencereLogu.size < ISTEK_PENCERESI_LIMITI) return 0L
            return (ISTEK_PENCERESI_MS - (simdi - pencereLogu.first())).coerceAtLeast(1_000L)
        }
    }

    private fun pencereyeEkle(simdi: Long) {
        synchronized(pencereLogu) {
            pencereLogu.addLast(simdi)
            pencereTemizle(simdi)
        }
    }

    private suspend fun istekOncesiBekle() {
        // * 1) Pencere limiti dolduysa once onu bekle (burst engeli).
        val pencereBekle = pencereBeklemeSuresi(System.currentTimeMillis())
        if (pencereBekle > 0) {
            Log.d("InatBox", "HIZ LIMITI: $ISTEK_PENCERESI_LIMITI istek/dk doldu, ${pencereBekle}ms bekleniyor")
            delay(pencereBekle)
        }

        istekKilidi.withLock {
            val simdi = System.currentTimeMillis()
            val gecen = simdi - sonIstekZamani
            if (gecen < ISTEK_ARASI_BEKLEME_MS) {
                delay(ISTEK_ARASI_BEKLEME_MS - gecen)
            }
            pencereyeEkle(System.currentTimeMillis())
            sonIstekZamani = System.currentTimeMillis()
        }
    }

    override val mainPage = mainPageOf(
        "${contentUrl}/tv/list1.php"              to "Spor ve Kanallar",
        "${contentUrl}/tv/list2.php"              to "Kanallar Liste 2",
        "${contentUrl}/tv/sinema.php"             to "Sinema Kanalları",
        "${contentUrl}/tv/belgesel.php"           to "Belgesel Kanalları",
        "${contentUrl}/tv/ulusal.php"             to "Ulusal Kanallar",
        "${contentUrl}/tv/haber.php"              to "Haber Kanalları",
        "${contentUrl}/tv/cocuk.php"              to "Çocuk Kanalları",
        "${contentUrl}/tv/dini.php"               to "Dini Kanallar",
        "${contentUrl}/ex/index.php"              to "EXXEN",
        "${contentUrl}/ga/index.php"              to "Gain",
        "${contentUrl}/nf/index.php"              to "Netflix",
        "${contentUrl}/dsny/index.php"            to "Disney+",
        "${contentUrl}/amz/index.php"             to "Amazon Prime",
        "${contentUrl}/hb/index.php"              to "HBO Max",
        "${contentUrl}/tbi/index.php"             to "Tabii",
        "${contentUrl}/film/mubi.php"             to "Mubi",
        "${contentUrl}/yabanci-dizi/index.php"    to "Yabancı Diziler",
        "${contentUrl}/yerli-dizi/index.php"      to "Yerli Diziler",
        "${contentUrl}/film/yerli-filmler.php"    to "Yerli Filmler",
        "${contentUrl}/film/4k-film-exo.php"      to "4K Film İzle | Exo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // * 1) ONCE DISKTEN: ban dongusunde istek atmadan icerik goster (6 saat gecerli)
        val disk = hamJsonOku(request.data)
        if (disk != null && System.currentTimeMillis() - disk.second < 6 * 3600_000L) {
            Log.d("InatBox", "KAT-DISK '${request.name}' -> ${disk.first.length} bayt (ISTEK YOK)")
            return newHomePageResponse(request.name, getSearchResponseList(disk.first))
        }

        // * 2) AG KAPALI -> hic istek atma (ban üstüne ban yememek icin)
        if (agKapali) {
            Log.d("InatBox", "KAT-AG-KAPALI '${request.name}' -> istek atilmadi")
            return newHomePageResponse(request.name, emptyList())
        }

        // * Yerel onbellek: sunucu istek yogunlugunda IP banliyor (403/1006).
        // * Bir kez cekilen kategori 45 dk boyunca tekrar istek atmadan kullanilir.
        val onbellekKey = request.data
        val onbellegi = sayfaOnbellek[onbellekKey]
        if (onbellegi != null && System.currentTimeMillis() - onbellegi.first < 45 * 60_000L) {
            Log.d("InatBox", "KAT-ONBELLEK '${request.name}' -> ${onbellegi.second.take(80).length} bayt (istek atilmadi)")
            val cacheSonuc = getSearchResponseList(onbellegi.second)
            return newHomePageResponse(request.name, cacheSonuc)
        }

        val jsonResponse = makeInatRequest(request.data)
        if (jsonResponse == null) {
            // * onbellekte varsa bayat veriyle de olsa goster
            if (onbellegi != null) {
                Log.d("InatBox", "KAT-ESKI-ONBELLEK '${request.name}' (istek basarisiz, onbellek kullanildi)")
                return newHomePageResponse(request.name, getSearchResponseList(onbellegi.second))
            }
            // * teshis: hangi kategori istek atiyor ve basarisiz oluyor?
            Log.e("InatBox", "KAT-BASARISIZ '${request.name}' -> ${request.data.substringAfter("/dizibox/")}")
            return newHomePageResponse(request.name, emptyList())
        }

        sayfaOnbellek[onbellekKey] = System.currentTimeMillis() to jsonResponse

        // * Diske de yaz: aynagi ag uzerinden Python ile cekmek engelleniyor (TLS parmakizi),
        // * uygulamanin cektigi veriyi TV sayfasi cihazdan okuyabilsin
        hamJsonYaz(request.data, jsonResponse)

        val searchResults = getSearchResponseList(jsonResponse)

        // * teshis: kategori etiketi gercek icerikle uyusuyor mu? (ilk 3 ad + adet)
        Log.d("InatBox", "KAT '${request.name}' -> ${searchResults.size} kayit | " +
            searchResults.take(3).joinToString(" / ") { it.name.take(30) })

        for (searchResponse in searchResults) {
            val url = searchResponse.url
            if (!urlToSearchResponse.containsKey(url)) {
                urlToSearchResponse[url] = searchResponse
            }
        }

        // Return a HomePageResponse with the parsed results
        return newHomePageResponse(request.name, searchResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // * ASIL BAN KAYNAGI BURASIYDI: bu blok ag kapisini hic kontrol etmiyordu.
        // * Cache bos olmasa bile 20 kategori x 1 istek = aninda ~20 imzali POST atiyordu
        // * (quickSearch her tusa basildiginda tetiklenir) -> 403/1006 -> ban ustune ban.
        // * Artik: ag kapaliyken veya onbellek bosken HICBIR istek atilmaz.
        if (urlToSearchResponse.isEmpty()) {
            if (agKapali) {
                Log.d("InatBox", "ARAMA-AG-KAPALI '$query' -> 20 kategori isteği atılmadı")
            } else {
                for (pageData in mainPage) {
                    val jsonResponse = makeInatRequest(pageData.data) ?: continue

                    val searchResults = getSearchResponseList(jsonResponse)

                    for (searchResponse in searchResults) {
                        val contentUrl = searchResponse.url
                        if (!urlToSearchResponse.containsKey(contentUrl)) {
                            urlToSearchResponse[contentUrl] = searchResponse
                        }
                    }
                }
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)

        if (!inatContentAllowed(item)) {
            return null
        }

        if (item.has("diziType")) {
            item.getString("diziName")
            val type = item.getString("diziType")

            return when (type) {
                "dizi", "dizi_mode" -> parseTvSeriesResponse(item)
                "film", "film_mode" -> parseMovieResponse(item)
                else -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            item.getString("chName")
            val chType = item.getString("chType")

            val loadResponse = when (chType) {
                "live_url", "cable_sh" -> parseLiveStreamLoadResponse(item)
                "tekli_regex_lb_sh_3" -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
            return loadResponse
        } else {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("InatBox", "data: $data")
        return try {
            if (data.startsWith("[")) {
                val chContentJsonArray = JSONArray(data)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonArray = JSONObject(data)
                val chContent = parseToChContent(chContentJsonArray)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e("InatBox", "Error on loadLinks:${e::class.simpleName} - ${e.message}")
            false
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.getString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                val seasonData = SeasonData(season = (i + 1), name = seasonName)
                seasonDataList.add(seasonData)

                val seasonUrl = seasonItem.getString("diziUrl")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    Log.e("InatBox", "Failed to parse episode JSON for season: $seasonName", e)
                    continue
                }

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.getString("chName")
                        val episodePoster = episodeItem.getString("chImg")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = episodeName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (_: JSONException) {
                        continue
                    }
                }
            }

            // Get the poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.getString("diziImg")

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV series response: ${e.message}\nStacktrace:${
                    e.stackTrace.joinToString("\n")
                }"
            )
            return null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.getString("diziImg")
                val plot = item.getString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                return newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                item.getString("chUrl")
                val posterUrl = item.getString("chImg")
                return newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse sports live stream response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val name = chContent.chName
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    // * Ham JSON'u dis diske yaz (TV sayfasi icin): /sdcard/.../files/inatbox/<kategori>.json
    private val diskKlasor =
        java.io.File("/sdcard/Android/data/com.lagradost.cloudstream3.prerelease/files/inatbox")

    private fun kategoriAdi(kategoriUrl: String): String =
        kategoriUrl.substringAfterLast("/").ifBlank { "kategori" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun hamJsonYaz(kategoriUrl: String, hamJson: String) {
        try {
            if (!diskKlasor.exists()) diskKlasor.mkdirs()
            java.io.File(diskKlasor, "${kategoriAdi(kategoriUrl)}.json").writeText(hamJson)
            Log.d("InatBox", "ONBELLEK-YAZILDI ${kategoriAdi(kategoriUrl)}.json (${hamJson.length} bayt)")
        } catch (e: Exception) {
            Log.e("InatBox", "ONBELLEK-YAZILAMADI: ${e.message}")
        }
    }

    // * Diskten oku: ban dongusunde istek ATMADAN icerik goster
    private fun hamJsonOku(kategoriUrl: String): Pair<String, Long>? = try {
        val dosya = java.io.File(diskKlasor, "${kategoriAdi(kategoriUrl)}.json")
        if (dosya.exists() && dosya.length() > 0L) dosya.readText() to dosya.lastModified() else null
    } catch (e: Exception) {
        null
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type: String = if (item.has("diziType")) {
            item.getString("diziType")
        } else {
            item.getString("chType")
        }

        // * Liste karisik gorunuyordu: API tanitim/sosyal medya satirlari da donuyor
        // * (link_mode, link, web, web_mode, redirect, ad) -> bunlar icerik degil
        val kucuk = type.lowercase()
        if (kucuk.startsWith("link")) return false
        if (kucuk.startsWith("web")) return false
        if (kucuk == "redirect" || kucuk == "ad" || kucuk == "promo") return false

        return when (type) {
            "link", "web" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.getString("chName"),
            chUrl = item.getString("chUrl").vkSourceFix(),
            chImg = item.getString("chImg"),
            chHeaders = item.getString("chHeaders"),
            chReg = item.getString("chReg"),
            chType = item.getString("chType")
        )
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val chType = chContent.chType
        val contentToProcess: ChContent

        if (chType == "tekli_regex_lb_sh_3") {
            val name = chContent.chName
            val url = chContent.chUrl
            val posterUrl = chContent.chImg
            val headers = chContent.chHeaders
            val reg = chContent.chReg
            val type = chContent.chType

            val jsonResponse = runCatching { makeInatRequest(url) }.getOrNull()
                ?: getJsonFromEncryptedInatResponse(app.get(url).body.string()) ?: return
            val firstItem = JSONObject(jsonResponse)
            firstItem.put("chHeaders", headers)
            firstItem.put("chReg", reg)
            firstItem.put("chName", name)
            firstItem.put("chImg", posterUrl)
            firstItem.put("chType", type)
            contentToProcess = parseToChContent(firstItem)
        } else {
            contentToProcess = chContent
        }

        val sourceUrl = contentToProcess.chUrl

        // Headerları hazırlama kısmı
        val headers: MutableMap<String, String> = mutableMapOf()
        try {
            val chHeaders = contentToProcess.chHeaders
            val chReg = contentToProcess.chReg
            if (chHeaders != "null") {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (entry in jsonHeaders.keys()) {
                    headers[entry] = jsonHeaders[entry].toString()
                }
            }
            if (chReg != "null") {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                val cookie = jsonReg.getString("playSH2")
                headers["Cookie"] = cookie
            }
        } catch (_: Exception) {
        }

        val extractorFound = if (sourceUrl.contains("dzen.ru")) {
            loadExtractor(sourceUrl, subtitleCallback, callback)
        } else {
            loadExtractor(sourceUrl, subtitleCallback, callback)
        }

        // Extractor bulunamazsa genel yükleme denemesi
        if (!extractorFound) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = contentToProcess.chName,
                    url = sourceUrl,
                    type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if (sourceUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                ) {
                    // 4. DÜZELTME: 'mapOf("Referer" to headers)' hatalıydı (Map içinde Map).
                    // Direkt 'headers' değişkenini atıyoruz.
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun makeInatRequest(url: String): String? {
        // * HIZ SINIRI: iki imzali POST arasinda minimum 8 sn bekleniyor.
        // * Once getMainPage (20 kategori) + arama + load birlikte calistiginda
        // * saniyede onlarca isket atilip sunucu tarafindan ban isleniyordu.
        istekOncesiBekle()

        // Extract hostname using URI
        val hostName = try {
            URI(url).host ?: throw IllegalArgumentException("Invalid URL: $url")
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to extract hostname from URL: $url", e)
            return null
        }

        val requestBody = "1=${aesKey}&0=${aesKey}"
        val path = URI(url).path

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = request.newBuilder().header("User-Agent", "speedrestapi").build()
            chain.proceed(newRequest)
        }

        // * 2026: artik imzali istek gerekiyor (imzasiz -> 403 "Content could not load")
        // * 1. deneme guncel ofsetle; 403 gelirse govdedeki x-st (sunucu saati) ile ofsetleyip tekrar dener
        repeat(2) { deneme ->
            // * 2. deneme de arka arkaya POST atiyor -> araya bekleme (burst engeli)
            if (deneme > 0) istekOncesiBekle()

            val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val ts = (System.currentTimeMillis() / 1000 + timeOffset).toString()
            val imza = imzala(path, ts, nonce, requestBody)

            val headers = mapOf(
                "Cache-Control" to "no-cache",
                "Content-Length" to "37",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Host" to hostName,
                "Referer" to "https://speedrestapi.com/",
                "X-Requested-With" to "com.bp.box",
                "X-Ts" to ts,
                "X-Nc" to nonce,
                "X-Sg" to imza
            )

            val response = app.post(
                url = url,
                headers = headers,
                requestBody = requestBody.toRequestBody(contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()),
                interceptor = interceptor
            )

            if (response.isSuccessful) {
                val encryptedResponse = response.body.string()
                return getJsonFromEncryptedInatResponse(encryptedResponse)
            }

            val serverTime = response.headers["x-st"]?.toLongOrNull()
            if (serverTime != null && deneme == 0) {
                timeOffset = serverTime - System.currentTimeMillis() / 1000
                Log.d("InatBox", "Saat ofseti guncellendi: $timeOffset sn -> tekrar denenecek")
                return@repeat
            }

            // * hata govdesini de logla: "error code: 1006" = Cloudflare engeli,
            // * "Content could not load" = imza sorunu
            val hataGovdesi = try { response.body.string().take(120) } catch (e: Exception) { "(govde okunamadi)" }
            Log.e("InatBox", "Request failed: HTTP ${response.code} | $hataGovdesi")
            return null
        }

        Log.e("InatBox", "Request failed after retry")
        return null
    }

    // * canonical = "POST\n<path>\n<ts>\n<nonce>\n<sha256(body)>" -> HMAC-SHA256(signingKey)
    private fun imzala(path: String, ts: String, nonce: String, body: String): String {
        val bodyHash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val canonical = "POST\n$path\n$ts\n$nonce\n$bodyHash"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getJsonFromEncryptedInatResponse(response: String): String? {
        try {
            val algorithm = "AES/CBC/PKCS5Padding"
            val keySpec = SecretKeySpec(aesKey.toByteArray(), "AES")

            // First decryption iteration
            val cipher1 = Cipher.getInstance(algorithm)
            cipher1.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val firstIterationData =
                cipher1.doFinal(Base64.decode(response.split(":")[0], Base64.DEFAULT))

            // Second decryption iteration
            val cipher2 = Cipher.getInstance(algorithm)
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val secondIterationData = cipher2.doFinal(
                Base64.decode(
                    String(firstIterationData).split(":")[0],
                    Base64.DEFAULT
                )
            )

            // Parse JSON
            val jsonString = String(secondIterationData)
            return jsonString
        } catch (e: Exception) {
            Log.e("InatBox", "Decryption failed: ${e.message}")
            return null
        }
    }

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                //Let's pass item directly to the next step
                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    val searchResponse = when (type) {
                        "dizi", "dizi_mode" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        "film", "film_mode" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        else -> null // Ignore unsupported types
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name = item.getString("chName")
                    val posterUrl = item.getString("chImg")
                    val chType = item.getString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "tekli_regex_lb_sh_3" -> newLiveSearchResponse(
                            name,
                            item.toString(),
                            TvType.Live
                        ) {
                            this.posterUrl = posterUrl
                        }

                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }
}
