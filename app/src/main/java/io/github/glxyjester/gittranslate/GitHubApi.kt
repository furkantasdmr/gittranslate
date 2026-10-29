package io.github.glxyjester.gittranslate

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class SurumDosyasi(val ad: String, val adres: String, val boyut: Long)
data class Surum(val etiket: String, val ad: String, val adres: String, val dosyalar: List<SurumDosyasi>)
data class KokOgesi(val ad: String, val klasor: Boolean)

data class RepoBilgisi(
    val sahip: String,
    val ad: String,
    val aciklama: String?,
    val anaDil: String?,
    val yildiz: Int,
    val konular: List<String>,
    val lisansSpdx: String?,
    val lisansAdi: String?,
    val anaSayfa: String?,
    val dal: String,
    val guncellenme: Long?,
    val arsiv: Boolean,
    val fork: Boolean,
    val readme: String?,
    /** null = henüz alınmadı (sadece README istendi) */
    val kok: List<KokOgesi>?,
    val surum: Surum?,
    /** GitHub sınırı dolduğu için sadece README alınabildi */
    val sinirli: Boolean = false,
) {
    val tam: Boolean get() = kok != null
}

sealed class ApiHatasi {
    object Bulunamadi : ApiHatasi()
    data class Sinir(val dakika: Int) : ApiHatasi()
    data class Ag(val mesaj: String) : ApiHatasi()
}

/**
 * GitHub'ın herkese açık REST API'si. Anahtar gerekmez (saatte 60 istek).
 *  - README için sadece 2 istek; "daha fazla" denince 2 istek daha.
 *  - Sonuçlar diskte saklanır: aynı repo 6 saat boyunca istek harcamadan açılır,
 *    internet ya da GitHub sınırı sorunu olursa eski kayıt gösterilir.
 *  - Sınır dolduysa README, sınırı olmayan raw.githubusercontent.com'dan alınır.
 */
object GitHubApi {

    private val havuz = Executors.newFixedThreadPool(6)
    private val ana = Handler(Looper.getMainLooper())
    private val bellek = HashMap<String, RepoBilgisi>()
    private var klasor: File? = null
    private const val TAZE_SURE = 6 * 60 * 60 * 1000L

    private class Cevap(val kod: Int, val govde: String, val sifirlanma: Long?)
    private class HataIstisnasi(val hata: ApiHatasi) : Exception()

    fun baslat(c: Context) {
        if (klasor == null) klasor = File(c.applicationContext.cacheDir, "repolar").apply { mkdirs() }
    }

    /**
     * @param tam false: açıklama + README; true: ayrıca kök dosyalar ve son sürüm
     */
    fun getir(sahip: String, ad: String, tam: Boolean, sonuc: (RepoBilgisi?, ApiHatasi?) -> Unit) {
        val anahtar = "$sahip/$ad".lowercase()
        bellek[anahtar]?.let { if (!tam || it.tam) { sonuc(it, null); return } }
        havuz.execute {
            val diskte = diskOku(anahtar)
            if (diskte != null && System.currentTimeMillis() - diskte.second < TAZE_SURE && (!tam || diskte.first.tam) && !diskte.first.sinirli) {
                ana.post { bellek[anahtar] = diskte.first; sonuc(diskte.first, null) }
                return@execute
            }
            try {
                val r = yukle(sahip, ad, tam, eski = diskte?.first)
                diskYaz(anahtar, r)
                ana.post { bellek[anahtar] = r; sonuc(r, null) }
            } catch (e: Exception) {
                val hata = (e as? HataIstisnasi)?.hata ?: ApiHatasi.Ag(e.message ?: e.javaClass.simpleName)
                // Bulunamadıysa eski kayıt anlamsız; diğer hatalarda eskisini göster
                if (diskte != null && hata !is ApiHatasi.Bulunamadi) {
                    ana.post { bellek[anahtar] = diskte.first; sonuc(diskte.first, null) }
                } else {
                    ana.post { sonuc(null, hata) }
                }
            }
        }
    }

    private val adOnbellegi = HashMap<String, List<String>>()

    /** Bir kişinin/kurumun repo adları (kesik görünen adı tamamlamak için). Hata olursa boş liste. */
    fun repoAdlari(sahip: String, sonuc: (List<String>) -> Unit) {
        adOnbellegi[sahip.lowercase()]?.let { sonuc(it); return }
        havuz.execute {
            val adlar = ArrayList<String>()
            try {
                for (sayfa in 1..3) {
                    val c = iste("https://api.github.com/users/$sahip/repos?per_page=100&sort=pushed&page=$sayfa")
                    if (c.kod != 200) break
                    val a = JSONArray(c.govde)
                    for (i in 0 until a.length()) adlar.add(a.getJSONObject(i).optString("name"))
                    if (a.length() < 100) break
                }
            } catch (_: Exception) {}
            ana.post {
                if (adlar.isNotEmpty()) adOnbellegi[sahip.lowercase()] = adlar
                sonuc(adlar)
            }
        }
    }

    // ---------------- Yükleme ----------------

    private fun yukle(sahip: String, ad: String, tam: Boolean, eski: RepoBilgisi?): RepoBilgisi {
        val temel = "https://api.github.com/repos/$sahip/$ad"
        val repoF = havuz.submit(Callable { iste(temel) })
        val readmeF = havuz.submit(Callable { iste("$temel/readme", ham = true) })
        val kokF = if (tam) havuz.submit(Callable { iste("$temel/contents") }) else null
        val surumF = if (tam) havuz.submit(Callable { iste("$temel/releases/latest") }) else null

        val repo = repoF.get()
        if (repo.sifirlanma != null) {
            // Saatlik sınır doldu: README'yi sınırsız kaynaktan almayı dene
            hamReadme(sahip, ad)?.let { metin ->
                return eski?.copy(readme = metin) ?: RepoBilgisi(
                    sahip, ad, null, null, 0, emptyList(), null, null, null, "HEAD", null,
                    arsiv = false, fork = false, readme = metin, kok = null, surum = null, sinirli = true,
                )
            }
        }
        kontrol(repo)
        val j = JSONObject(repo.govde)
        val gercekSahip = j.optJSONObject("owner")?.optString("login")?.takeIf { it.isNotEmpty() } ?: sahip
        val gercekAd = j.optString("name", ad)

        val readmeCevap = readmeF.get()
        val readme = when (readmeCevap.kod) {
            200 -> readmeCevap.govde
            404 -> null
            else -> hamReadme(gercekSahip, gercekAd) ?: eski?.readme
        }

        val kok = kokF?.get()?.let { c ->
            if (c.kod != 200) emptyList() else try {
                val a = JSONArray(c.govde)
                (0 until a.length()).map { i -> a.getJSONObject(i).let { KokOgesi(it.optString("name"), it.optString("type") == "dir") } }
            } catch (_: Exception) { emptyList() }
        } ?: eski?.kok

        val surum = surumF?.get()?.let { c -> if (c.kod == 200) surumAyristir(c.govde) else null } ?: eski?.surum

        val konular = j.optJSONArray("topics")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
        val lisans = j.optJSONObject("license")
        return RepoBilgisi(
            sahip = gercekSahip,
            ad = gercekAd,
            aciklama = j.yaziVeyaNull("description"),
            anaDil = j.yaziVeyaNull("language"),
            yildiz = j.optInt("stargazers_count"),
            konular = konular,
            lisansSpdx = lisans?.yaziVeyaNull("spdx_id"),
            lisansAdi = lisans?.yaziVeyaNull("name"),
            anaSayfa = j.yaziVeyaNull("homepage"),
            dal = j.optString("default_branch", "main"),
            guncellenme = tarih(j.yaziVeyaNull("pushed_at")),
            arsiv = j.optBoolean("archived"),
            fork = j.optBoolean("fork"),
            readme = readme,
            kok = kok,
            surum = surum,
        )
    }

    private fun surumAyristir(govde: String): Surum? = try {
        val o = JSONObject(govde)
        val v = o.optJSONArray("assets") ?: JSONArray()
        Surum(
            etiket = o.optString("tag_name"),
            ad = o.optString("name").ifBlank { o.optString("tag_name") },
            adres = o.optString("html_url"),
            dosyalar = (0 until v.length()).map { i ->
                val d = v.getJSONObject(i)
                SurumDosyasi(d.optString("name"), d.optString("browser_download_url"), d.optLong("size"))
            },
        )
    } catch (_: Exception) { null }

    /** API sınırına takılmayan ham dosya sunucusundan README */
    private fun hamReadme(sahip: String, ad: String): String? {
        for (dosya in listOf("README.md", "readme.md", "Readme.md", "README.MD", "README.markdown", "README.rst", "README.txt", "README")) {
            try {
                val c = iste("https://raw.githubusercontent.com/$sahip/$ad/HEAD/$dosya", ham = true, api = false)
                if (c.kod == 200 && c.govde.isNotBlank()) return c.govde
            } catch (_: Exception) {}
        }
        return null
    }

    private fun kontrol(c: Cevap) {
        when {
            c.kod == 200 -> return
            c.kod == 404 || c.kod == 451 -> throw HataIstisnasi(ApiHatasi.Bulunamadi)
            c.sifirlanma != null -> {
                val dk = ((c.sifirlanma * 1000 - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(1)
                throw HataIstisnasi(ApiHatasi.Sinir(dk))
            }
            else -> throw HataIstisnasi(ApiHatasi.Ag("HTTP ${c.kod}"))
        }
    }

    /** Ağ hatasında bir kez daha dener. */
    private fun iste(adres: String, ham: Boolean = false, api: Boolean = true): Cevap {
        var son: IOException? = null
        repeat(2) { deneme ->
            try { return istekAt(adres, ham, api) } catch (e: IOException) {
                son = e
                if (deneme == 0) Thread.sleep(700)
            }
        }
        throw son ?: IOException("bağlantı")
    }

    private fun istekAt(adres: String, ham: Boolean, api: Boolean): Cevap {
        val b = URL(adres).openConnection() as HttpURLConnection
        b.connectTimeout = 12000
        b.readTimeout = 20000
        if (api) {
            b.setRequestProperty("Accept", if (ham) "application/vnd.github.raw" else "application/vnd.github+json")
            b.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        b.setRequestProperty("User-Agent", "GitTranslate-Android")
        try {
            val kod = b.responseCode
            val akis = if (kod in 200..299) b.inputStream else b.errorStream
            val govde = akis?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val kalan = b.getHeaderField("x-ratelimit-remaining")?.toIntOrNull()
            val sifir = b.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
            val sinirda = (kod == 403 || kod == 429) && (kalan == 0 || kod == 429)
            return Cevap(kod, govde, if (sinirda) (sifir ?: (System.currentTimeMillis() / 1000 + 3600)) else null)
        } finally {
            b.disconnect()
        }
    }

    // ---------------- Disk önbelleği ----------------

    private fun dosya(anahtar: String) = klasor?.let { File(it, anahtar.replace('/', '~') + ".json") }

    private fun diskYaz(anahtar: String, r: RepoBilgisi) {
        val f = dosya(anahtar) ?: return
        try {
            val o = JSONObject()
                .put("zaman", System.currentTimeMillis())
                .put("sahip", r.sahip).put("ad", r.ad).put("aciklama", r.aciklama).put("anaDil", r.anaDil)
                .put("yildiz", r.yildiz).put("konular", JSONArray(r.konular))
                .put("lisansSpdx", r.lisansSpdx).put("lisansAdi", r.lisansAdi).put("anaSayfa", r.anaSayfa)
                .put("dal", r.dal).put("guncellenme", r.guncellenme ?: 0L).put("arsiv", r.arsiv).put("fork", r.fork)
                .put("readme", r.readme).put("sinirli", r.sinirli)
            r.kok?.let { k -> o.put("kok", JSONArray(k.map { JSONObject().put("ad", it.ad).put("klasor", it.klasor) })) }
            r.surum?.let { s ->
                o.put("surum", JSONObject().put("etiket", s.etiket).put("ad", s.ad).put("adres", s.adres)
                    .put("dosyalar", JSONArray(s.dosyalar.map { JSONObject().put("ad", it.ad).put("adres", it.adres).put("boyut", it.boyut) })))
            }
            val gecici = File(f.path + ".tmp")
            gecici.writeText(o.toString())
            gecici.renameTo(f)
            temizle()
        } catch (_: Exception) {}
    }

    private fun diskOku(anahtar: String): Pair<RepoBilgisi, Long>? {
        val f = dosya(anahtar)?.takeIf { it.exists() } ?: return null
        return try {
            val o = JSONObject(f.readText())
            val kok = o.optJSONArray("kok")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { k -> KokOgesi(k.optString("ad"), k.optBoolean("klasor")) } } }
            val surum = o.optJSONObject("surum")?.let { s ->
                val d = s.optJSONArray("dosyalar") ?: JSONArray()
                Surum(s.optString("etiket"), s.optString("ad"), s.optString("adres"),
                    (0 until d.length()).map { d.getJSONObject(it).let { x -> SurumDosyasi(x.optString("ad"), x.optString("adres"), x.optLong("boyut")) } })
            }
            RepoBilgisi(
                sahip = o.getString("sahip"), ad = o.getString("ad"), aciklama = o.yaziVeyaNull("aciklama"),
                anaDil = o.yaziVeyaNull("anaDil"), yildiz = o.optInt("yildiz"),
                konular = o.optJSONArray("konular")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                lisansSpdx = o.yaziVeyaNull("lisansSpdx"), lisansAdi = o.yaziVeyaNull("lisansAdi"),
                anaSayfa = o.yaziVeyaNull("anaSayfa"), dal = o.optString("dal", "HEAD"),
                guncellenme = o.optLong("guncellenme").takeIf { it > 0 }, arsiv = o.optBoolean("arsiv"),
                fork = o.optBoolean("fork"), readme = o.yaziVeyaNull("readme"), kok = kok, surum = surum,
                sinirli = o.optBoolean("sinirli"),
            ) to o.optLong("zaman")
        } catch (_: Exception) { null }
    }

    /** En fazla 80 repo saklanır, eskiler silinir */
    private fun temizle() {
        val dosyalar = klasor?.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (dosyalar.size <= 80) return
        dosyalar.sortedBy { it.lastModified() }.take(dosyalar.size - 80).forEach { it.delete() }
    }

    private fun tarih(s: String?): Long? = try {
        s?.let {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)?.time
        }
    } catch (_: Exception) { null }

    private fun JSONObject.yaziVeyaNull(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
}
