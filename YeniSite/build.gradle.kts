version = 0

cloudstream {
    authors     = listOf("keyiflerolsun")
    language    = "tr"
    description = "YENI_SITE_AACIKLAMA - Cloudstream için yeni eklenti iskeleti."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=YENI_SITE_ALAN_ADI&sz=%size%"
}
