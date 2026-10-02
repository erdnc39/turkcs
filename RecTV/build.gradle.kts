version = 116

cloudstream {
    authors     = listOf("keyiflerolsun", "yusiqo", "inatchii", "JustRelaxable")
    language    = "tr"
    description = "RecTv APK, TÃ¼rkiyeâ€™deki en popÃ¼ler Ã‡evrimiÃ§i Medya AkÄ±ÅŸ platformlarÄ±ndan biridir. Filmlerin, CanlÄ± SporlarÄ±n, Web Dizilerinin ve Ã§ok daha fazlasÄ±nÄ±n keyfini Ã¼cretsiz Ã§Ä±karÄ±n."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "Live", "TvSeries")
    iconUrl = "https://rectv.org.tr/wp-content/uploads/2024/11/rectv-modified.webp"
}
