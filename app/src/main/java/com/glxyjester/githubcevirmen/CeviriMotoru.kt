package com.glxyjester.githubcevirmen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Dili tanır ve çevirir.
 *  1) Önce telefonun içindeki ML Kit çevirmenini dener (internetsiz, dil paketi gerekir).
 *  2) Dil paketi inmezse (ör. Google Play Hizmetleri yoksa) yazıyı Google Çeviri ile internetten çevirir.
 * Sonuçlar önbellekte tutulur, aynı yazı tekrar gelince anında gösterilir.
 */
class CeviriMotoru {

    private val ana = Handler(Looper.getMainLooper())
    private val ag = Executors.newFixedThreadPool(3)

    private val dilTanima = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build()
    )

    /** "kaynak>hedef" -> çevirmen */
    private val cevirmenler = HashMap<String, Translator>()
    private val hazirCevirmenler = HashSet<String>()
    /** Aynı paket için tek indirme: bekleyen istekler burada sıraya girer */
    private val indirmeBekleyenleri = HashMap<String, MutableList<(Translator?) -> Unit>>()
    /** Paketi inemeyen çiftler: bir süre tekrar denenmez, doğrudan internetten çevrilir */
    private val basarisizCiftler = HashMap<String, Long>()

    /** "hedef|metin" -> çeviri. Boş string = "çevrilmeyecek" (zaten hedef dilde, kod vb.) */
    private val onbellek = LruCache<String, String>(3000)
    private val bekleyenler = HashSet<String>()

    var indirmeSayisi = 0
        private set
    var indirmeDurumuDegisti: (() -> Unit)? = null

    /** Balonda "!" gösterilecek gerçek hata: iki yol da başarısız olduysa */
    var sonHata = ""
        private set
    /** Bilgi amaçlı: dil paketi neden inmedi */
    var paketHatasi = ""
        private set
    var cevrimiciSayisi = 0
        private set
    val bekleyenSayisi: Int get() = bekleyenler.size

    private val durumlar = LruCache<String, String>(300)
    fun durum(metin: String, hedef: String): String = durumlar.get("$hedef|$metin") ?: "sırada"

    fun onbellektenAl(metin: String, hedef: String): String? = onbellek.get("$hedef|$metin")

    /** Önbellekte yoksa arka planda çevirmeye başlar; bitince [bitti] çağrılır. */
    fun cevir(metin: String, hedef: String, bitti: () -> Unit) {
        val anahtar = "$hedef|$metin"
        if (onbellek.get(anahtar) != null || !bekleyenler.add(anahtar)) return

        dilTanima.identifyLanguage(metin)
            .addOnSuccessListener { etiket ->
                val kaynak = kaynakDil(etiket, metin)
                if (kaynak == null || kaynak == hedef) {
                    durumlar.put(anahtar, "dil=$etiket → atlandı")
                    sonuc(anahtar, "", bitti)
                    return@addOnSuccessListener
                }
                durumlar.put(anahtar, "dil=$kaynak → hazırlanıyor")
                cevirmenAl(kaynak, hedef) { cevirmen ->
                    if (cevirmen == null) {
                        cevrimiciDene(anahtar, metin, kaynak, hedef, bitti)
                        return@cevirmenAl
                    }
                    cevirmen.translate(metin)
                        .addOnSuccessListener { ceviri ->
                            durumlar.put(anahtar, "cihazda → \"${ceviri.trim().take(30)}\"")
                            sonuc(anahtar, temizle(ceviri, metin), bitti)
                        }
                        .addOnFailureListener { cevrimiciDene(anahtar, metin, kaynak, hedef, bitti) }
                }
            }
            .addOnFailureListener { cevrimiciDene(anahtar, metin, null, hedef, bitti) }
    }

    private fun cevrimiciDene(anahtar: String, metin: String, kaynak: String?, hedef: String, bitti: () -> Unit) {
        durumlar.put(anahtar, "internetten çevriliyor…")
        cevrimiciCevir(metin, kaynak, hedef) { ceviri, hataMesaji ->
            if (ceviri != null) {
                durumlar.put(anahtar, "internet → \"${ceviri.take(30)}\"")
                sonHata = ""
                sonuc(anahtar, temizle(ceviri, metin), bitti)
            } else {
                durumlar.put(anahtar, "internet hatası")
                sonHata = "İnternetten çeviri: $hataMesaji".take(160)
                indirmeDurumuDegisti?.invoke()
                bekleyenler.remove(anahtar) // önbelleğe yazma, sonra tekrar denensin
            }
        }
    }

    private fun temizle(ceviri: String, metin: String): String {
        val t = ceviri.trim()
        return if (t.isEmpty() || t.equals(metin.trim(), ignoreCase = true)) "" else t
    }

    private fun sonuc(anahtar: String, deger: String, bitti: () -> Unit) {
        onbellek.put(anahtar, deger)
        bekleyenler.remove(anahtar)
        if (deger.isNotEmpty()) bitti()
    }

    private fun kaynakDil(etiket: String, metin: String): String? {
        if (etiket == "und") {
            // Kısa yazılarda dil tanıma kararsız kalabiliyor: yazı türünden tahmin et
            for (c in metin) {
                when (Character.UnicodeScript.of(c.code)) {
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return TranslateLanguage.JAPANESE
                    Character.UnicodeScript.HANGUL -> return TranslateLanguage.KOREAN
                    else -> {}
                }
            }
            if (metin.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return TranslateLanguage.CHINESE
            if (metin.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }) return TranslateLanguage.RUSSIAN
            return null
        }
        val ana = etiket.substringBefore('-').let { if (it == "iw") "he" else it }
        return TranslateLanguage.fromLanguageTag(ana)
    }

    /** Gerekirse dil paketini indirip çevirmeni hazır hâle getirir. Başarısızsa null verir. */
    fun cevirmenAl(kaynak: String, hedef: String, hazir: (Translator?) -> Unit) {
        val anahtar = "$kaynak>$hedef"
        basarisizCiftler[anahtar]?.let { zaman ->
            if (SystemClock.elapsedRealtime() - zaman < 10 * 60_000) { hazir(null); return }
            basarisizCiftler.remove(anahtar)
        }
        val cevirmen = try {
            cevirmenler.getOrPut(anahtar) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(kaynak).setTargetLanguage(hedef).build()
                )
            }
        } catch (e: Exception) {
            paketHatasi = e.message ?: e.javaClass.simpleName
            basarisizCiftler[anahtar] = SystemClock.elapsedRealtime()
            hazir(null); return
        }
        if (anahtar in hazirCevirmenler) { hazir(cevirmen); return }

        // Bu paket zaten iniyorsa sıraya gir, ikinci kez indirme başlatma
        indirmeBekleyenleri[anahtar]?.let { it.add(hazir); return }
        indirmeBekleyenleri[anahtar] = mutableListOf(hazir)

        indirmeSayisi++
        indirmeDurumuDegisti?.invoke()

        // Bazı telefonlarda indirme hiç bitmiyor: 45 sn sonra vazgeç, internetten çevir
        var bitti = false
        fun bitir(sonuc: Translator?) {
            if (bitti) return
            bitti = true
            indirmeSayisi--
            indirmeDurumuDegisti?.invoke()
            indirmeBekleyenleri.remove(anahtar)?.forEach { it(sonuc) }
        }
        ana.postDelayed({
            if (!bitti) {
                paketHatasi = "45 sn içinde inmedi"
                basarisizCiftler[anahtar] = SystemClock.elapsedRealtime()
                bitir(null)
            }
        }, 45_000)

        try {
            cevirmen.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    hazirCevirmenler.add(anahtar)
                    basarisizCiftler.remove(anahtar)
                    bitir(cevirmen)
                }
                .addOnFailureListener { e ->
                    Log.w("CeviriMotoru", "Dil paketi indirilemedi: $anahtar", e)
                    paketHatasi = (e.message ?: e.javaClass.simpleName).take(160)
                    basarisizCiftler[anahtar] = SystemClock.elapsedRealtime()
                    bitir(null)
                }
        } catch (e: Exception) {
            paketHatasi = (e.message ?: e.javaClass.simpleName).take(160)
            basarisizCiftler[anahtar] = SystemClock.elapsedRealtime()
            bitir(null)
        }
    }

    /** Google Çeviri'nin herkese açık web adresiyle çeviri. Sonuç ana iş parçacığında döner. */
    private fun cevrimiciCevir(metin: String, kaynak: String?, hedef: String, sonuc: (String?, String?) -> Unit) {
        cevrimiciSayisi++
        ag.execute {
            var ceviri: String? = null
            var hataMesaji: String? = null
            try {
                val sl = when (kaynak) { null -> "auto"; "zh" -> "zh-CN"; "he" -> "iw"; else -> kaynak }
                val tl = when (hedef) { "zh" -> "zh-CN"; else -> hedef }
                val adres = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                    "&sl=$sl&tl=$tl&q=" + URLEncoder.encode(metin, "UTF-8")
                val b = URL(adres).openConnection() as HttpURLConnection
                b.connectTimeout = 8000
                b.readTimeout = 8000
                b.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                try {
                    if (b.responseCode != 200) {
                        hataMesaji = "HTTP ${b.responseCode}"
                    } else {
                        val govde = b.inputStream.bufferedReader().use { it.readText() }
                        val kok = JSONArray(govde)
                        val algilanan = kok.optString(2, "")
                        if (algilanan.substringBefore('-') == hedef.substringBefore('-')) {
                            ceviri = "" // zaten hedef dilde
                        } else {
                            val parcalar = kok.getJSONArray(0)
                            val sb = StringBuilder()
                            for (i in 0 until parcalar.length()) {
                                val p = parcalar.optJSONArray(i) ?: continue
                                if (!p.isNull(0)) sb.append(p.getString(0))
                            }
                            ceviri = sb.toString()
                        }
                    }
                } finally {
                    b.disconnect()
                }
            } catch (e: Exception) {
                hataMesaji = e.message ?: e.javaClass.simpleName
            }
            ana.post { sonuc(ceviri, hataMesaji) }
        }
    }

    /** Teşhis: dil tanıma + çeviri zincirini tek cümleyle dener. */
    fun testEt(metin: String, hedef: String, sonuc: (String) -> Unit) {
        dilTanima.identifyLanguage(metin)
            .addOnSuccessListener { etiket ->
                val kaynak = kaynakDil(etiket, metin)
                if (kaynak == null || kaynak == hedef) { sonuc("dil=$etiket, çevrilmedi"); return@addOnSuccessListener }
                cevirmenAl(kaynak, hedef) { c ->
                    if (c == null) {
                        cevrimiciCevir(metin, kaynak, hedef) { s, h ->
                            sonuc(if (s != null) "internetten ✅ \"$s\"" else "paket yok, internet de HATA: $h")
                        }
                        return@cevirmenAl
                    }
                    c.translate(metin)
                        .addOnSuccessListener { sonuc("cihazda ✅ \"$it\"") }
                        .addOnFailureListener { e -> sonuc("çeviri HATASI: ${e.message}") }
                }
            }
            .addOnFailureListener { e -> sonuc("dil tanıma HATASI: ${e.message}") }
    }

    fun onbellegiTemizle() {
        onbellek.evictAll()
        bekleyenler.clear()
    }

    fun kapat() {
        dilTanima.close()
        cevirmenler.values.forEach { it.close() }
        cevirmenler.clear()
        ag.shutdownNow()
    }
}
