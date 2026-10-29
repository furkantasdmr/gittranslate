package com.glxyjester.githubcevirmen

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
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
    val kok: List<KokOgesi>,
    val surum: Surum?,
)

sealed class ApiHatasi {
    object Bulunamadi : ApiHatasi()
    data class Sinir(val dakika: Int) : ApiHatasi()
    data class Ag(val mesaj: String) : ApiHatasi()
}

/**
 * GitHub'ın herkese açık REST API'si. Anahtar gerekmez (saatte 60 istek; bir repo 4 istek).
 * Aynı repo tekrar açılırsa bellekteki sonuç kullanılır.
 */
object GitHubApi {

    private val havuz = Executors.newFixedThreadPool(4)
    private val ana = Handler(Looper.getMainLooper())
    private val onbellek = HashMap<String, RepoBilgisi>()

    private class Cevap(val kod: Int, val govde: String, val sifirlanma: Long?)
    private class HataIstisnasi(val hata: ApiHatasi) : Exception()

    fun getir(sahip: String, ad: String, sonuc: (RepoBilgisi?, ApiHatasi?) -> Unit) {
        val anahtar = "$sahip/$ad".lowercase()
        onbellek[anahtar]?.let { sonuc(it, null); return }
        havuz.execute {
            val r: RepoBilgisi? = try { yukle(sahip, ad) } catch (e: HataIstisnasi) {
                ana.post { sonuc(null, e.hata) }; return@execute
            } catch (e: Exception) {
                ana.post { sonuc(null, ApiHatasi.Ag(e.message ?: e.javaClass.simpleName)) }; return@execute
            }
            ana.post {
                if (r != null) onbellek[anahtar] = r
                sonuc(r, null)
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

    private fun yukle(sahip: String, ad: String): RepoBilgisi {
        val temel = "https://api.github.com/repos/$sahip/$ad"
        // Dört isteği aynı anda gönder
        val repoF = havuz.submit(Callable { iste(temel) })
        val readmeF = havuz.submit(Callable { iste("$temel/readme", ham = true) })
        val kokF = havuz.submit(Callable { iste("$temel/contents") })
        val surumF = havuz.submit(Callable { iste("$temel/releases/latest") })

        val repo = repoF.get()
        kontrol(repo)
        val j = JSONObject(repo.govde)
        // Yeniden adlandırılmış repolarda doğru adı kullan
        val gercekSahip = j.optJSONObject("owner")?.optString("login")?.takeIf { it.isNotEmpty() } ?: sahip
        val gercekAd = j.optString("name", ad)

        val readme = readmeF.get().let { if (it.kod == 200) it.govde else null }

        val kok = kokF.get().let { c ->
            if (c.kod != 200) emptyList() else try {
                val a = JSONArray(c.govde)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    KokOgesi(o.optString("name"), o.optString("type") == "dir")
                }
            } catch (_: Exception) { emptyList() }
        }

        val surum = surumF.get().let { c ->
            if (c.kod != 200) null else try {
                val o = JSONObject(c.govde)
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
        }

        val konular = j.optJSONArray("topics")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
        val lisans = j.optJSONObject("license")

        return RepoBilgisi(
            sahip = gercekSahip,
            ad = gercekAd,
            aciklama = j.optStringOrNull("description"),
            anaDil = j.optStringOrNull("language"),
            yildiz = j.optInt("stargazers_count"),
            konular = konular,
            lisansSpdx = lisans?.optStringOrNull("spdx_id"),
            lisansAdi = lisans?.optStringOrNull("name"),
            anaSayfa = j.optStringOrNull("homepage"),
            dal = j.optString("default_branch", "main"),
            guncellenme = tarih(j.optStringOrNull("pushed_at")),
            arsiv = j.optBoolean("archived"),
            fork = j.optBoolean("fork"),
            readme = readme,
            kok = kok,
            surum = surum,
        )
    }

    private fun kontrol(c: Cevap) {
        when {
            c.kod == 200 -> return
            c.kod == 404 || c.kod == 451 -> throw HataIstisnasi(ApiHatasi.Bulunamadi)
            (c.kod == 403 || c.kod == 429) && c.sifirlanma != null -> {
                val dk = ((c.sifirlanma * 1000 - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(1)
                throw HataIstisnasi(ApiHatasi.Sinir(dk))
            }
            else -> throw HataIstisnasi(ApiHatasi.Ag("HTTP ${c.kod}"))
        }
    }

    private fun iste(adres: String, ham: Boolean = false): Cevap {
        val b = URL(adres).openConnection() as HttpURLConnection
        b.connectTimeout = 12000
        b.readTimeout = 15000
        b.setRequestProperty("Accept", if (ham) "application/vnd.github.raw" else "application/vnd.github+json")
        b.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        b.setRequestProperty("User-Agent", "GitHubCevirmen-Android")
        try {
            val kod = b.responseCode
            val akis = if (kod in 200..299) b.inputStream else b.errorStream
            val govde = akis?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val kalan = b.getHeaderField("x-ratelimit-remaining")?.toIntOrNull()
            val sifir = b.getHeaderField("x-ratelimit-reset")?.toLongOrNull()
            return Cevap(kod, govde, if (kalan == 0 || kod == 429) sifir else null)
        } finally {
            b.disconnect()
        }
    }

    private fun tarih(s: String?): Long? = try {
        s?.let {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(it)?.time
        }
    } catch (_: Exception) { null }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
}
