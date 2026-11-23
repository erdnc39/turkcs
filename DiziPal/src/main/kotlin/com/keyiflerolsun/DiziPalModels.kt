// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty


data class DizipalSearchData(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("results") val results: List<DizipalSearchResult>? = null
)

// * 2026-11-23 OLCUM (/search): alan adlari DEGISTI, ama bazilari SESSIZCE kayboluyor:
// *   {"title":"Dexter","slug":"dexter-8","year":2006,"poster":"...","imdb":8.2,
// *    "type":"dizi","url":"https://dizipal1.rest/dizi/dexter-8"}
// *   - "success" alani ARTIK YOK  -> success nullable yapildi (yoksa parse patlar)
// *   - reyting "rating" yerine "imdb" ve SAYI tipinde (eski String idi)
// *   - "type" artik KUCUK HARF: "dizi" / "film" (eski kod "Dizi" ariyordu)
// * ESKI alanlar SILINMEDI; ikisi de okunuyor (site eski bicime donerse diye).
data class DizipalSearchResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("imdb") val imdb: Double? = null
)