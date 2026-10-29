package io.github.glxyjester.gittranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Balon bir repo gördüğünde, kullanıcı dokunmadan önce arka planda:
 *  1) repo adını doğrular (kesik/yanlış okunduysa sahibin listesinden düzeltir),
 *  2) açıklama + README + son sürüm notlarını indirir,
 *  3) panelde görünecek bütün yazıları çevirip önbelleğe koyar.
 * Böylece balona dokununca panel çevrilmiş hâliyle anında açılır.
 * Bütün çağrılar ana iş parçacığında.
 */
object OnHazirlik {

    enum class Durum { HAZIRLANIYOR, HAZIR, HATA }

    data class Hal(val sahip: String, val ad: String, val hedef: String, val durum: Durum, val ilerleme: Float)

    private val ana = Handler(Looper.getMainLooper())
    private var nesil = 0
    private var tespitAnahtari = ""
    private var baslangic = 0L

    /** Son hazırlığın durumu (null: hiç yok) */
    var hal: Hal? = null
        private set

    /** Balon bu dinleyiciyle rengini ve halkasını günceller */
    var dinleyici: ((Hal?) -> Unit)? = null

    private const val ES_ZAMANLI = 3

    /**
     * Aynı repo için tekrar çağrılırsa bir şey yapmaz (hata aldıysa 1 dk sonra yeniden dener).
     */
    fun baslat(c: Context, t: TespitSonucu) {
        val hedef = Ayarlar.hedefDil(c)
        val anahtar = "${t.sahip}/${t.ad}|$hedef".lowercase()
        val h = hal
        if (anahtar == tespitAnahtari && h != null &&
            (h.durum != Durum.HATA || SystemClock.elapsedRealtime() - baslangic < 60_000)
        ) return
        tespitAnahtari = anahtar
        baslangic = SystemClock.elapsedRealtime()
        val n = ++nesil
        GitHubApi.baslat(c)
        yay(Hal(t.sahip, t.ad, hedef, Durum.HAZIRLANIYOR, 0f))

        adiCoz(t) { sahip, ad ->
            if (n != nesil) return@adiCoz
            getirDuzelterek(sahip, ad) { b ->
                if (n != nesil) return@getirDuzelterek
                if (b == null) { yay(hal?.copy(durum = Durum.HATA)); return@getirDuzelterek }
                yay(Hal(b.sahip, b.ad, hedef, Durum.HAZIRLANIYOR, 0.05f))
                hepsiniCevir(b, hedef, n)
            }
        }
    }

    /** Balon başka bir ekrana geçildiğini söylediğinde */
    fun unut() {
        nesil++
        tespitAnahtari = ""
        yay(null)
    }

    /** Dokunulunca açılacak doğru (düzeltilmiş) ad, bu tespit için hazırlık başladıysa */
    fun cozulmusAd(t: TespitSonucu): Pair<String, String>? {
        val h = hal ?: return null
        val anahtar = "${t.sahip}/${t.ad}".lowercase()
        return if (tespitAnahtari.startsWith("$anahtar|")) h.sahip to h.ad else null
    }

    // ---------------- Adımlar ----------------

    private fun adiCoz(t: TespitSonucu, devam: (String, String) -> Unit) {
        if (t.kesik == TespitSonucu.Kesik.YOK) { devam(t.sahip, t.ad); return }
        GitHubApi.repoAdlari(t.sahip) { adlar ->
            devam(t.sahip, RepoTespit.kesikAdiEslestir(t.ad, t.kesik, adlar) ?: t.ad)
        }
    }

    private fun getirDuzelterek(sahip: String, ad: String, sonuc: (RepoBilgisi?) -> Unit) {
        GitHubApi.getir(sahip, ad, tam = false) { b, h ->
            if (b != null) { sonuc(b); return@getir }
            if (h !is ApiHatasi.Bulunamadi) { sonuc(null); return@getir }
            GitHubApi.repoAdlari(sahip) { adlar ->
                val dogru = RepoTespit.enYakin(ad, adlar)
                if (dogru == null || dogru == ad) sonuc(null)
                else GitHubApi.getir(sahip, dogru, tam = false) { b2, _ -> sonuc(b2) }
            }
        }
    }

    private fun hepsiniCevir(b: RepoBilgisi, hedef: String, n: Int) {
        val readme = b.readme?.let { Markdown.ayristir(it, b.ad) }
        val notlar = b.surum?.notlar?.takeIf { it.isNotBlank() }?.let { Markdown.ayristir(it, null) }
        val metinler = CeviriListesi.topla(b.aciklama, readme, notlar)
        if (metinler.isEmpty()) { yay(Hal(b.sahip, b.ad, hedef, Durum.HAZIR, 1f)); return }

        var sira = 0
        var biten = 0
        var sonBildirim = 0L
        fun ilerle() {
            while (n == nesil && sira < metinler.size && (sira - biten) < ES_ZAMANLI) {
                val m = metinler[sira++]
                CeviriMotoru.cevir(m, hedef) {
                    biten++
                    if (n != nesil) return@cevir
                    val simdi = SystemClock.uptimeMillis()
                    if (biten == metinler.size) {
                        yay(Hal(b.sahip, b.ad, hedef, Durum.HAZIR, 1f))
                    } else {
                        if (simdi - sonBildirim > 150) {
                            sonBildirim = simdi
                            yay(Hal(b.sahip, b.ad, hedef, Durum.HAZIRLANIYOR, 0.05f + 0.95f * biten / metinler.size))
                        }
                        // Önbellekten gelen sonuçlar hemen döner; derin özyineleme olmasın diye sıraya at
                        ana.post { ilerle() }
                    }
                }
            }
        }
        ilerle()
    }

    private fun yay(h: Hal?) {
        hal = h
        ana.post { try { dinleyici?.invoke(h) } catch (_: Throwable) {} }
    }
}
