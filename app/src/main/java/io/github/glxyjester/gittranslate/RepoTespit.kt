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
data class TespitSonucu(
    val sahip: String,
    val ad: String,
    val kesik: Kesik = Kesik.YOK,
    /**
     * false: ekranda birden çok repo adı var (arama sonuçları, liste…). Bu durumda arka planda
     * hazırlık yapılmaz (GitHub'ın saatlik sınırını boşa harcamamak için); dokununca yine açılır.
     */
    val kesin: Boolean = true,
) {
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
        // 3) Üst bölgede alt alta iki yazı: küçük olan sahip, altındaki repo adı (repo sayfasının başlığı)
        baslikCifti(yazilar, ekranYuksekligi, dp)?.let { return it }
        // 2) "sahip/repo" ya da "sahip / repo" biçiminde yazılar (üstteki öncelikli)
        val adaylar = yazilar.sortedBy { it.ust }.mapNotNull { y ->
            val m = SAHIP_AD.matchEntire(y.metin) ?: return@mapNotNull null
            val sahip = m.groupValues[1]; val ad = m.groupValues[2]
            if (sahip.any { it.isLetter() } && !arayuzYazisi(sahip) && ad.any { it.isLetter() }) (sahip to ad) else null
        }.distinctBy { "${it.first}/${it.second}".lowercase() }
        val ilk = adaylar.firstOrNull() ?: return null
        return TespitSonucu(ilk.first, ilk.second, kesin = adaylar.size == 1)
    }

    private fun baslikCifti(yazilar: List<EkranYazisi>, ekranYuksekligi: Int, dp: Float): TespitSonucu? {
        val ciftler = ciftler(yazilar, dp)
        val ust = ciftler.firstOrNull { it.second < ekranYuksekligi * 0.30 }?.first ?: return null
        // Ekranın her yerinde aynı düzende başka sahip/repo çiftleri varsa bu bir liste olabilir
        val farkli = ciftler.map { "${it.first.sahip}/${it.first.ad}".lowercase() }.distinct().size
        return ust.copy(kesin = farkli == 1)
    }

    /** Alt alta duran (sahip, repo adı) çiftleri ve üst kenarları, yukarıdan aşağı */
    private fun ciftler(yazilar: List<EkranYazisi>, dp: Float): List<Pair<TespitSonucu, Int>> {
        val sirali = yazilar.filter { it.metin.isNotBlank() }.sortedBy { it.ust }
        val sonuc = ArrayList<Pair<TespitSonucu, Int>>()
        for (i in sirali.indices) {
            val a = sirali[i]
            val sahip = a.metin.trim()
            if (!SAHIP.matches(sahip) || arayuzYazisi(sahip) || sahip.none { it.isLetter() }) continue
            for (j in i + 1 until sirali.size) {
                val b = sirali[j]
                if (b.ust - a.alt > 40 * dp) break
                if (b.ust < a.ust + (a.alt - a.ust) / 2) continue // aynı satır
                if (kotlin.math.abs(b.sol - a.sol) > 28 * dp) continue
                val (ad, kesik) = elipsSil(b.metin)
                if (ad.length < 2 || !AD.matches(ad) || arayuzYazisi(ad)) continue
                sonuc.add(TespitSonucu(sahip, ad, kesik) to a.ust)
                break
            }
        }
        return sonuc
    }

    /**
     * Yanlış/kesik okunmuş adı ("…of-Chin", "Administrative-divisions-of-Chlna") sahibin
     * gerçek repo adlarıyla eşleştirir: önce tam, sonra baş/son eşleşmesi, sonra küçük yazım farkı.
     */
    fun enYakin(okunan: String, adlar: List<String>): String? {
        val o = okunan.lowercase().trim().trim('.', '…')
        if (o.isEmpty() || adlar.isEmpty()) return null
        adlar.firstOrNull { it.equals(o, ignoreCase = true) }?.let { return it }
        val kucuk = adlar.map { it to it.lowercase() }
        if (o.length >= 3) {
            kucuk.filter { (_, a) -> a.startsWith(o) || a.endsWith(o) }.minByOrNull { it.first.length }?.let { return it.first }
            kucuk.filter { (_, a) -> a.contains(o) }.minByOrNull { it.first.length }?.let { return it.first }
        }
        val sinir = maxOf(2, o.length / 6)
        return kucuk.map { (gercek, a) -> gercek to duzenlemeMesafesi(o, a) }
            .filter { it.second <= sinir }
            .minByOrNull { it.second }?.first
    }

    private fun duzenlemeMesafesi(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 8) return Int.MAX_VALUE
        var onceki = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val su = IntArray(b.length + 1)
            su[0] = i
            for (j in 1..b.length) {
                su[j] = minOf(onceki[j] + 1, su[j - 1] + 1, onceki[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            onceki = su
        }
        return onceki[b.length]
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
