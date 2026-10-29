package com.glxyjester.githubcevirmen

import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Dili tanır ve telefonun içinde çevirir (ML Kit). Sonuçları önbellekte tutar,
 * böylece aynı yazı ekrana tekrar geldiğinde anında gösterilir.
 */
class CeviriMotoru {

    private val dilTanima = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build()
    )

    /** "kaynak>hedef" -> çevirmen */
    private val cevirmenler = HashMap<String, Translator>()
    private val hazirCevirmenler = HashSet<String>()

    /** "hedef|metin" -> çeviri. Boş string = "çevrilmeyecek" (zaten hedef dilde, kod vb.) */
    private val onbellek = LruCache<String, String>(3000)
    private val bekleyenler = HashSet<String>()

    /** Dil paketi indirilirken balonda saat göstermek için. */
    var indirmeSayisi = 0
        private set
    var indirmeDurumuDegisti: (() -> Unit)? = null

    /** Teşhis için: en son hatanın kısa açıklaması */
    var sonHata = ""
        private set
    val bekleyenSayisi: Int get() = bekleyenler.size

    /** Teşhis: her metnin son durumu ("dil=zh, çevrildi" gibi) */
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
                durumlar.put(anahtar, "dil=$etiket")
                if (kaynak == null || kaynak == hedef) {
                    durumlar.put(anahtar, "dil=$etiket → atlandı")
                    sonuc(anahtar, "", bitti)
                    return@addOnSuccessListener
                }
                durumlar.put(anahtar, "dil=$kaynak → paket hazırlanıyor")
                cevirmenAl(kaynak, hedef) { cevirmen ->
                    if (cevirmen == null) {
                        durumlar.put(anahtar, "dil=$kaynak → paket inemedi")
                        // İndirme başarısız: önbelleğe yazma ki internet gelince tekrar denensin
                        bekleyenler.remove(anahtar); return@cevirmenAl
                    }
                    cevirmen.translate(metin)
                        .addOnSuccessListener { ceviri ->
                            val temiz = ceviri.trim()
                            durumlar.put(anahtar, "dil=$kaynak → \"${temiz.take(30)}\"")
                            sonuc(anahtar, if (temiz.equals(metin.trim(), ignoreCase = true)) "" else temiz, bitti)
                        }
                        .addOnFailureListener { e -> durumlar.put(anahtar, "çeviri hatası"); hata("Çeviri", e); bekleyenler.remove(anahtar) }
                }
            }
            .addOnFailureListener { e -> hata("Dil tanıma", e); bekleyenler.remove(anahtar) }
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
        // "zh-Latn" gibi etiketlerde ilk parça yeterli
        val ana = etiket.substringBefore('-').let { if (it == "iw") "he" else it }
        return TranslateLanguage.fromLanguageTag(ana)
    }

    /** Gerekirse dil paketini indirip çevirmeni hazır hâle getirir. */
    fun cevirmenAl(kaynak: String, hedef: String, hazir: (Translator?) -> Unit) {
        val anahtar = "$kaynak>$hedef"
        val cevirmen = cevirmenler.getOrPut(anahtar) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(kaynak)
                    .setTargetLanguage(hedef)
                    .build()
            )
        }
        if (anahtar in hazirCevirmenler) {
            hazir(cevirmen); return
        }
        indirmeSayisi++
        indirmeDurumuDegisti?.invoke()
        cevirmen.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                hazirCevirmenler.add(anahtar)
                sonHata = ""
                hazir(cevirmen)
            }
            .addOnFailureListener { e ->
                Log.w("CeviriMotoru", "Dil paketi indirilemedi: $anahtar", e)
                hata("Paket indirme ($anahtar)", e)
                hazir(null)
            }
            .addOnCompleteListener {
                indirmeSayisi--
                indirmeDurumuDegisti?.invoke()
            }
    }

    private fun hata(nerede: String, e: Exception) {
        sonHata = "$nerede: ${e.message ?: e.javaClass.simpleName}".take(160)
        indirmeDurumuDegisti?.invoke()
    }

    /** Teşhis: dil tanıma + indirme + çeviri zincirini tek cümleyle dener. */
    fun testEt(metin: String, hedef: String, sonuc: (String) -> Unit) {
        dilTanima.identifyLanguage(metin)
            .addOnSuccessListener { etiket ->
                val kaynak = kaynakDil(etiket, metin)
                if (kaynak == null || kaynak == hedef) { sonuc("dil=$etiket, çevrilmedi"); return@addOnSuccessListener }
                cevirmenAl(kaynak, hedef) { c ->
                    if (c == null) { sonuc("dil=$kaynak, paket İNEMEDİ: $sonHata"); return@cevirmenAl }
                    c.translate(metin)
                        .addOnSuccessListener { sonuc("dil=$kaynak → \"$it\"") }
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
    }
}
