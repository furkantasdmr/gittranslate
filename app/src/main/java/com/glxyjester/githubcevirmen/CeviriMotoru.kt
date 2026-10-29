package com.glxyjester.githubcevirmen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 *  1) Önce telefonun içindeki ML Kit çevirmeni (ücretsiz, internetsiz; dil paketi bir kez iner).
 *  2) Dil paketi inmezse Google Çeviri'nin herkese açık web adresi.
 * Yapay zekâ özetleme yoktur; yalnızca çeviri yapılır.
 */
object CeviriMotoru {

    private val ana = Handler(Looper.getMainLooper())
    private val ag = Executors.newFixedThreadPool(3)

    private val dilTanima by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
    }

    private val cevirmenler = HashMap<String, Translator>()
    private val hazir = HashSet<String>()
    private val indirmeBekleyenleri = HashMap<String, MutableList<(Translator?) -> Unit>>()
    private val basarisiz = HashMap<String, Long>()

    /** "hedef|metin" -> çeviri ("" = çevirmeye gerek yok) */
    private val onbellek = LruCache<String, String>(4000)

    /**
     * @param sonuc çeviri; metin zaten hedef dildeyse ya da çevrilemezse null
     */
    fun cevir(metin: String, hedef: String, sonuc: (String?) -> Unit) {
        val m = metin.trim()
        if (m.isEmpty() || m.none { it.isLetter() }) { sonuc(null); return }
        val anahtar = "$hedef|$m"
        onbellek.get(anahtar)?.let { sonuc(it.ifEmpty { null }); return }

        if (internetOnce) {
            // Daha iyi çeviri için önce Google Çeviri; olmazsa telefonun içindeki çevirmen
            cevrimici(m, null, hedef) { c ->
                if (c != null) kaydet(anahtar, m, c, sonuc) else cihazdaCevir(m, hedef, anahtar, sonuc)
            }
            return
        }
        cihazdaCevir(m, hedef, anahtar, sonuc)
    }

    /** true: önce internetten (Google Çeviri) çevir. */
    var internetOnce = true

    private fun cihazdaCevir(m: String, hedef: String, anahtar: String, sonuc: (String?) -> Unit) {
        dilTanima.identifyLanguage(m)
            .addOnSuccessListener { etiket ->
                val kaynak = kaynakDil(etiket, m)
                if (kaynak == hedef) { onbellek.put(anahtar, ""); sonuc(null); return@addOnSuccessListener }
                if (kaynak == null) {
                    if (internetOnce) sonuc(null) else cevrimici(m, null, hedef) { c -> kaydet(anahtar, m, c, sonuc) }
                    return@addOnSuccessListener
                }
                cevirmenAl(kaynak, hedef) { cevirmen ->
                    if (cevirmen == null) {
                        if (internetOnce) sonuc(null) else cevrimici(m, kaynak, hedef) { c -> kaydet(anahtar, m, c, sonuc) }
                        return@cevirmenAl
                    }
                    cevirmen.translate(m)
                        .addOnSuccessListener { c -> kaydet(anahtar, m, c, sonuc) }
                        .addOnFailureListener { sonuc(null) }
                }
            }
            .addOnFailureListener { sonuc(null) }
    }

    private fun kaydet(anahtar: String, metin: String, ceviri: String?, sonuc: (String?) -> Unit) {
        if (ceviri == null) { sonuc(null); return } // hata: önbelleğe yazma, sonra tekrar denensin
        val t = ceviri.trim()
        val deger = if (t.isEmpty() || t.equals(metin, ignoreCase = true)) "" else t
        onbellek.put(anahtar, deger)
        sonuc(deger.ifEmpty { null })
    }

    private fun kaynakDil(etiket: String, metin: String): String? {
        if (etiket == "und") {
            for (c in metin) when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return TranslateLanguage.JAPANESE
                Character.UnicodeScript.HANGUL -> return TranslateLanguage.KOREAN
                else -> {}
            }
            if (metin.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return TranslateLanguage.CHINESE
            if (metin.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }) return TranslateLanguage.RUSSIAN
            return null
        }
        val k = etiket.substringBefore('-').let { if (it == "iw") "he" else it }
        return TranslateLanguage.fromLanguageTag(k)
    }

    /** Gerekirse dil paketini indirip çevirmeni hazırlar. İnmezse null. */
    fun cevirmenAl(kaynak: String, hedef: String, hazirCb: (Translator?) -> Unit) {
        val anahtar = "$kaynak>$hedef"
        basarisiz[anahtar]?.let { if (SystemClock.elapsedRealtime() - it < 10 * 60_000) { hazirCb(null); return } }
        val cevirmen = try {
            cevirmenler.getOrPut(anahtar) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(kaynak).setTargetLanguage(hedef).build())
            }
        } catch (_: Exception) { basarisiz[anahtar] = SystemClock.elapsedRealtime(); hazirCb(null); return }
        if (anahtar in hazir) { hazirCb(cevirmen); return }

        indirmeBekleyenleri[anahtar]?.let { it.add(hazirCb); return }
        indirmeBekleyenleri[anahtar] = mutableListOf(hazirCb)

        var bitti = false
        fun bitir(c: Translator?) {
            if (bitti) return
            bitti = true
            if (c == null) basarisiz[anahtar] = SystemClock.elapsedRealtime() else hazir.add(anahtar)
            indirmeBekleyenleri.remove(anahtar)?.forEach { it(c) }
        }
        ana.postDelayed({ bitir(null) }, 60_000) // bazı telefonlarda indirme hiç bitmiyor
        try {
            cevirmen.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { bitir(cevirmen) }
                .addOnFailureListener { bitir(null) }
        } catch (_: Exception) { bitir(null) }
    }

    /** Uzun yazıyı cümle sınırlarından bölerek parçalara ayırır. */
    private fun parcala(metin: String, enFazla: Int): List<String> {
        if (metin.length <= enFazla) return listOf(metin)
        val cumleler = metin.split(Regex("(?<=[.!?。！？\\n])\\s*"))
        val sonuc = ArrayList<String>()
        val sb = StringBuilder()
        for (c in cumleler) {
            if (sb.length + c.length > enFazla && sb.isNotEmpty()) { sonuc.add(sb.toString()); sb.clear() }
            if (c.length > enFazla) { c.chunked(enFazla).forEach { sonuc.add(it) }; continue }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(c)
        }
        if (sb.isNotEmpty()) sonuc.add(sb.toString())
        return sonuc
    }

    private fun cevrimici(metin: String, kaynak: String?, hedef: String, sonuc: (String?) -> Unit) {
        ag.execute {
            var ceviri: String? = null
            try {
                val sl = when (kaynak) { null -> "auto"; "zh" -> "zh-CN"; "he" -> "iw"; else -> kaynak }
                val tl = when (hedef) { "zh" -> "zh-CN"; "he" -> "iw"; else -> hedef }
                val sb = StringBuilder()
                var hedefDilde = false
                for (parca in parcala(metin, 1200)) {
                    val adres = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                        "&sl=$sl&tl=$tl&q=" + URLEncoder.encode(parca, "UTF-8")
                    val b = URL(adres).openConnection() as HttpURLConnection
                    b.connectTimeout = 10000
                    b.readTimeout = 10000
                    b.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                    try {
                        if (b.responseCode != 200) throw IllegalStateException("HTTP ${b.responseCode}")
                        val kok = JSONArray(b.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        if (kok.optString(2, "").substringBefore('-') == hedef) hedefDilde = true
                        val p = kok.getJSONArray(0)
                        if (sb.isNotEmpty()) sb.append(' ')
                        for (i in 0 until p.length()) p.optJSONArray(i)?.let { if (!it.isNull(0)) sb.append(it.getString(0)) }
                    } finally { b.disconnect() }
                }
                ceviri = if (hedefDilde) "" else sb.toString()
            } catch (_: Exception) { ceviri = null }
            ana.post { sonuc(ceviri) }
        }
    }
}
