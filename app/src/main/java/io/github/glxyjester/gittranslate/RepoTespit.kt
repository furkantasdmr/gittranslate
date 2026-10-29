package io.github.glxyjester.gittranslate

/*
 * Ekranda görünen yazılardan hangi repoda olunduğunu bulur. Android'e bağımlı değildir (birim testli).
 * GitHub uygulamasının repo sayfasında üstte küçük harfle sahip, altında büyük harfle repo adı yazar:
 *     modood
 *     …tive-divisions-of-China
 * Ad sığmazsa başı ya da sonu "…" ile kesilir; bu durumda sahibin repo listesinden tamamlanır.
 */

/** Ekrandaki bir yazı ve konumu (piksel) */
data class EkranYazisi(val metin: String, val sol: Int, val ust: Int, val sag: Int, val alt: Int)

/** Bulunan repo. [kesik] doluysa ad tam görünmüyordu; [RepoTespit.kesikAdiEslestir] ile tamamlanmalı. */
data class TespitSonucu(val sahip: String, val ad: String, val kesik: Kesik = Kesik.YOK) {
    enum class Kesik { YOK, BASI, SONU }
}

object RepoTespit {

    private val SAHIP = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")
    private val AD = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val SAHIP_AD = Regex("^\\s*([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\\s*/\\s*([A-Za-z0-9._-]{1,100})\\s*$")

    /** GitHub arayüzünde sahip/repo sanılabilecek sabit yazılar */
    private val ARAYUZ = setOf(
        "home", "inbox", "explore", "copilot", "profile", "code", "issues", "pulls", "pull", "requests", "actions",
        "projects", "wiki", "security", "insights", "settings", "readme", "readme.md", "license", "about", "releases",
        "packages", "contributors", "languages", "watch", "fork", "forks", "star", "stars", "starred", "unstar",
        "share", "more", "search", "branches", "tags", "commits", "discussions", "browse", "repository", "repositories",
        "public", "private", "main", "master", "notifications", "back", "menu", "github", "files", "activity",
        "sponsor", "follow", "following", "followers", "organizations", "overview", "edit", "add", "new", "open",
        "closed", "all", "filter", "sort", "view", "code", "documentation", "md", "markdown",
    )

    private fun elipsSil(s: String): Pair<String, TespitSonucu.Kesik> {
        var t = s.trim()
        var k = TespitSonucu.Kesik.YOK
        if (t.startsWith("…") || t.startsWith("...")) { t = t.removePrefix("…").removePrefix("..."); k = TespitSonucu.Kesik.BASI }
        if (t.endsWith("…") || t.endsWith("...")) { t = t.removeSuffix("…").removeSuffix("..."); k = TespitSonucu.Kesik.SONU }
        return t.trim() to k
    }

    private fun arayuzYazisi(s: String) = s.lowercase() in ARAYUZ

    /**
     * @param ekranYuksekligi üst bölgeyi (başlık) bulmak için
     * @param dp 1 dp kaç piksel
     */
    fun bul(yazilar: List<EkranYazisi>, ekranYuksekligi: Int, dp: Float): TespitSonucu? {
        // 1) Herhangi bir yazıda tam bağlantı varsa (paylaş metni, "about" kutusu…)
        for (y in yazilar) {
            val s = RepoAnaliz.baglantiAyikla(y.metin.takeIf { it.contains("github.com", ignoreCase = true) })
            if (s is RepoAnaliz.BaglantiSonucu.Repo) return TespitSonucu(s.sahip, s.ad)
        }
        // 2) "sahip/repo" ya da "sahip / repo" biçiminde tek bir yazı (üstteki öncelikli)
        yazilar.sortedBy { it.ust }.forEach { y ->
            val m = SAHIP_AD.matchEntire(y.metin) ?: return@forEach
            val sahip = m.groupValues[1]; val ad = m.groupValues[2]
            if (sahip.any { it.isLetter() } && !arayuzYazisi(sahip) && ad.any { it.isLetter() }) return TespitSonucu(sahip, ad)
        }
        // 3) Üst bölgede alt alta iki yazı: küçük olan sahip, altındaki repo adı
        return baslikCifti(yazilar, ekranYuksekligi, dp)
    }

    private fun baslikCifti(yazilar: List<EkranYazisi>, ekranYuksekligi: Int, dp: Float): TespitSonucu? {
        val ust = yazilar.filter { it.ust < ekranYuksekligi * 0.30 && it.metin.isNotBlank() }.sortedBy { it.ust }
        for (i in ust.indices) {
            val a = ust[i]
            val sahip = a.metin.trim()
            if (!SAHIP.matches(sahip) || arayuzYazisi(sahip) || sahip.none { it.isLetter() }) continue
            for (j in i + 1 until ust.size) {
                val b = ust[j]
                if (b.ust - a.alt > 40 * dp) break
                if (b.ust < a.ust + (a.alt - a.ust) / 2) continue // aynı satır
                if (kotlin.math.abs(b.sol - a.sol) > 28 * dp) continue
                val (ad, kesik) = elipsSil(b.metin)
                if (ad.length < 2 || !AD.matches(ad) || arayuzYazisi(ad)) continue
                return TespitSonucu(sahip, ad, kesik)
            }
        }
        return null
    }

    /** Kesik görünen adı sahibin repo adlarından tamamlar. Bulamazsa null. */
    fun kesikAdiEslestir(parca: String, kesik: TespitSonucu.Kesik, adlar: List<String>): String? {
        val p = parca.lowercase()
        adlar.firstOrNull { it.equals(parca, ignoreCase = true) }?.let { return it }
        val adaylar = when (kesik) {
            TespitSonucu.Kesik.BASI -> adlar.filter { it.lowercase().endsWith(p) }
            TespitSonucu.Kesik.SONU -> adlar.filter { it.lowercase().startsWith(p) }
            TespitSonucu.Kesik.YOK -> adlar.filter { it.lowercase().contains(p) }
        }
        return adaylar.minByOrNull { it.length }
    }
}
