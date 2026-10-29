package com.glxyjester.githubcevirmen

/*
 * README (Markdown + biraz HTML) dosyasını basit parçalara ayırır.
 * Android'e bağımlı değildir; bu yüzden bilgisayarda birim testiyle denenebilir.
 */

data class Baglanti(val metin: String, val adres: String)

sealed class Parca {
    data class Baslik(val seviye: Int, val metin: String) : Parca()
    data class Paragraf(val metin: String, val baglantilar: List<Baglanti>) : Parca()
    data class Liste(val ogeler: List<ListeOgesi>) : Parca()
    data class Kod(val dil: String, val kod: String) : Parca()
    data class Alinti(val metin: String, val baglantilar: List<Baglanti>) : Parca()
    data class Tablo(val satirlar: List<List<String>>) : Parca()
}

data class ListeOgesi(val derinlik: Int, val metin: String, val sirali: Boolean, val baglantilar: List<Baglanti>)

enum class BolumTuru { GIRIS, KURULUM, KULLANIM, OZELLIKLER, DIGER, EK }

data class Bolum(val baslik: String, val seviye: Int, val tur: BolumTuru, val parcalar: List<Parca>)

data class Readme(val baslik: String?, val bolumler: List<Bolum>) {
    val giris: Bolum? get() = bolumler.firstOrNull { it.tur == BolumTuru.GIRIS }
    fun ilk(tur: BolumTuru): Bolum? = bolumler.firstOrNull { it.tur == tur }

    /** Girişteki ilk paragraflar: "bu repo ne?" sorusunun cevabı */
    fun ozet(enFazla: Int = 700): String {
        val sb = StringBuilder()
        for (p in giris?.parcalar.orEmpty()) {
            val m = when (p) {
                is Parca.Paragraf -> p.metin
                is Parca.Alinti -> p.metin
                else -> null
            } ?: continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(m)
            if (sb.length >= enFazla) break
        }
        return sb.toString().take(enFazla + 200)
    }
}

object Markdown {

    // ---------------- Dışarıya açık ----------------

    fun ayristir(kaynak: String, repoAdi: String? = null): Readme {
        val parcalar = parcalaraAyir(htmlTemizle(kaynak))
        return bolumle(parcalar, repoAdi)
    }

    // ---------------- 1) HTML temizliği (kod blokları hariç) ----------------

    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)\\s*([^`\\s]*).*$")

    fun htmlTemizle(kaynak: String): String {
        val metin = kaynak.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val cikti = StringBuilder()
        val parca = StringBuilder()
        var kodda: String? = null
        for (satir in metin.split('\n')) {
            val f = FENCE.matchEntire(satir)
            if (kodda == null && f != null) {
                cikti.append(htmlParcasi(parca.toString())); parca.clear()
                kodda = f.groupValues[1].take(3)
                cikti.append(satir).append('\n')
            } else if (kodda != null) {
                cikti.append(satir).append('\n')
                if (satir.trim().startsWith(kodda!!) && satir.trim().trim(kodda!![0]).isEmpty()) kodda = null
            } else {
                parca.append(satir).append('\n')
            }
        }
        cikti.append(htmlParcasi(parca.toString()))
        return cikti.toString()
    }

    private fun htmlParcasi(s: String): String {
        if (!s.contains('<') && !s.contains('&')) return s
        var t = s
        val ic = RegexOption.IGNORE_CASE
        val hepsi = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        // <h1>..</h1> -> # başlık
        t = Regex("<h([1-6])[^>]*>(.*?)</h\\1\\s*>", hepsi).replace(t) {
            "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2].replace('\n', ' ') + "\n\n"
        }
        // <a href="x">metin</a> -> [metin](x)
        t = Regex("<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a\\s*>", hepsi).replace(t) {
            "[" + it.groupValues[2].replace('\n', ' ') + "](" + it.groupValues[1] + ")"
        }
        t = Regex("<img[^>]*>", ic).replace(t, "")
        t = Regex("<br\\s*/?>", ic).replace(t, "\n")
        t = Regex("</?(b|strong)\\s*>", ic).replace(t, "**")
        t = Regex("</?(code|kbd|tt)\\s*>", ic).replace(t, "`")
        t = Regex("<li[^>]*>", ic).replace(t, "\n- ")
        t = Regex("</?(p|div|details|summary|center|table|thead|tbody|tr|ul|ol|li|section|article|header|footer|picture|blockquote|hr|figure)\\b[^>]*>", ic)
            .replace(t, "\n")
        t = Regex("</?(td|th)\\b[^>]*>", ic).replace(t, " ")
        // Kalan tüm etiketler (span, sup, em, source, svg...) sessizce silinir
        t = Regex("</?[a-zA-Z][a-zA-Z0-9-]*(\\s[^<>]*)?/?>").replace(t, "")
        return varlikCoz(t)
    }

    private fun varlikCoz(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&copy;", "©").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&amp;", "&")

    // ---------------- 2) Satır satır blok ayrıştırma ----------------

    private val ATX = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val SETEXT1 = Regex("^\\s{0,3}=+\\s*$")
    private val SETEXT2 = Regex("^\\s{0,3}-+\\s*$")
    private val HR = Regex("^\\s{0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    private val ALINTI = Regex("^\\s{0,3}>\\s?(.*)$")
    private val LISTE = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val TABLO_AYRAC = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    private class HamListeOgesi(val derinlik: Int, val sirali: Boolean, val metin: StringBuilder)

    fun parcalaraAyir(metin: String): List<Parca> {
        val satirlar = metin.split('\n')
        val sonuc = ArrayList<Parca>()
        val paragraf = ArrayList<String>()
        val alinti = ArrayList<String>()
        var liste = ArrayList<HamListeOgesi>()
        var oncekiBos = true

        fun paragrafBitir() {
            if (paragraf.isEmpty()) return
            val ham = paragraf.joinToString(" ") { it.trim() }
            paragraf.clear()
            satirIci(ham).let { (m, b) ->
                if (m.isNotBlank() && !sadeceBaglantiListesi(ham, m)) sonuc.add(Parca.Paragraf(m, b))
            }
        }
        fun alintiBitir() {
            if (alinti.isEmpty()) return
            // Alıntı içinde boş satırlar paragraf ayırır
            val ham = alinti.joinToString("\n").trim()
            alinti.clear()
            val paragraflar = ham.split(Regex("\\n\\s*\\n")).map { it.replace('\n', ' ') }
            val tumBaglantilar = ArrayList<Baglanti>()
            val metinler = paragraflar.mapNotNull { p ->
                val (m, b) = satirIci(p); tumBaglantilar += b; m.takeIf { it.isNotBlank() }
            }
            if (metinler.isNotEmpty()) sonuc.add(Parca.Alinti(metinler.joinToString("\n\n"), tumBaglantilar))
        }
        fun listeBitir() {
            if (liste.isEmpty()) return
            // Girinti miktarları sıralanıp seviyeye çevrilir (2 ya da 4 boşluk fark etmez)
            val seviyeler = liste.map { it.derinlik }.distinct().sorted()
            val ogeler = liste.mapNotNull { h ->
                val (m, b) = satirIci(h.metin.toString().trim())
                if (m.isBlank()) null
                else ListeOgesi(seviyeler.indexOf(h.derinlik).coerceIn(0, 3), m, h.sirali, b)
            }
            // Çoğunlukla sayfa içi bağlantılardan oluşan liste = içindekiler; biz kendimiz gösteriyoruz
            val capaSayisi = liste.count { h ->
                Regex("^\\[[^\\]]*\\]\\(#[^)]*\\)\\s*$").matches(h.metin.toString().trim())
            }
            val icindekiler = liste.size >= 2 && capaSayisi * 10 >= liste.size * 6
            if (ogeler.isNotEmpty() && !icindekiler) sonuc.add(Parca.Liste(ogeler))
            liste = ArrayList()
        }
        fun hepsiniBitir() { paragrafBitir(); alintiBitir(); listeBitir() }

        var i = 0
        while (i < satirlar.size) {
            val satir = satirlar[i]
            val bos = satir.isBlank()

            // Kod bloğu
            val f = FENCE.matchEntire(satir)
            if (f != null) {
                hepsiniBitir()
                val cit = f.groupValues[1]
                val dil = f.groupValues[2]
                val girinti = satir.length - satir.trimStart().length
                val kod = ArrayList<String>()
                i++
                while (i < satirlar.size) {
                    val s = satirlar[i]
                    val tr = s.trim()
                    if (tr.startsWith(cit.take(3)) && tr.trim(cit[0]).isEmpty()) break
                    kod.add(if (girinti > 0 && s.length >= girinti && s.take(girinti).isBlank()) s.drop(girinti) else s)
                    i++
                }
                i++ // kapanış çiti
                val k = kod.joinToString("\n").trimEnd()
                if (k.isNotBlank()) sonuc.add(Parca.Kod(dil, k))
                oncekiBos = false
                continue
            }

            if (bos) {
                paragrafBitir()
                alintiBitir()
                // Liste boş satırdan sonra da sürebilir; bir sonraki satıra bakılarak karar verilir
                oncekiBos = true
                i++
                continue
            }

            // Girintili kod bloğu (4 boşluk), liste içinde değilken
            if (oncekiBos && liste.isEmpty() && paragraf.isEmpty() &&
                (satir.startsWith("    ") || satir.startsWith("\t")) && LISTE.matchEntire(satir) == null
            ) {
                hepsiniBitir()
                val kod = ArrayList<String>()
                while (i < satirlar.size && (satirlar[i].startsWith("    ") || satirlar[i].startsWith("\t") || satirlar[i].isBlank())) {
                    kod.add(satirlar[i].removePrefix("\t").let { if (it.startsWith("    ")) it.drop(4) else it })
                    i++
                }
                val k = kod.joinToString("\n").trim('\n').trimEnd()
                if (k.isNotBlank()) sonuc.add(Parca.Kod("", k))
                oncekiBos = true
                continue
            }

            // Başlık
            val atx = ATX.matchEntire(satir)
            if (atx != null) {
                hepsiniBitir()
                val (m, _) = satirIci(atx.groupValues[2])
                if (m.isNotBlank()) sonuc.add(Parca.Baslik(atx.groupValues[1].length, m))
                oncekiBos = false; i++; continue
            }
            if (paragraf.size == 1 && liste.isEmpty() && (SETEXT1.matches(satir) || SETEXT2.matches(satir))) {
                val (m, _) = satirIci(paragraf[0].trim())
                paragraf.clear()
                if (m.isNotBlank()) sonuc.add(Parca.Baslik(if (SETEXT1.matches(satir)) 1 else 2, m))
                oncekiBos = false; i++; continue
            }
            if (HR.matches(satir)) {
                hepsiniBitir(); oncekiBos = true; i++; continue
            }

            // Tablo
            if (satir.contains('|') && i + 1 < satirlar.size && TABLO_AYRAC.matches(satirlar[i + 1])) {
                hepsiniBitir()
                val tablo = ArrayList<List<String>>()
                tablo.add(hucreler(satir))
                i += 2
                while (i < satirlar.size && satirlar[i].contains('|') && satirlar[i].isNotBlank()) {
                    tablo.add(hucreler(satirlar[i])); i++
                }
                if (tablo.any { r -> r.any { it.isNotBlank() } }) sonuc.add(Parca.Tablo(tablo))
                oncekiBos = false
                continue
            }

            // Alıntı
            val a = ALINTI.matchEntire(satir)
            if (a != null) {
                paragrafBitir(); listeBitir()
                alinti.add(a.groupValues[1])
                oncekiBos = false; i++; continue
            } else if (alinti.isNotEmpty()) {
                // tembel devam satırı
                alinti.add(satir); i++; continue
            }

            // Liste
            val l = LISTE.matchEntire(satir)
            if (l != null && !(paragraf.isNotEmpty() && l.groupValues[2].first().isDigit())) {
                paragrafBitir()
                val girinti = l.groupValues[1].replace("\t", "    ").length
                liste.add(HamListeOgesi(girinti, l.groupValues[2].first().isDigit(), StringBuilder(l.groupValues[3])))
                oncekiBos = false; i++; continue
            }
            if (liste.isNotEmpty()) {
                val girintili = satir.startsWith(" ") || satir.startsWith("\t")
                if (girintili || !oncekiBos) {
                    // liste öğesinin devamı
                    liste.last().metin.append(' ').append(satir.trim())
                    oncekiBos = false; i++; continue
                }
                listeBitir()
            }

            // Düz paragraf
            paragraf.add(satir)
            oncekiBos = false
            i++
        }
        hepsiniBitir()
        return sonuc
    }

    private fun hucreler(satir: String): List<String> {
        var s = satir.trim()
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        return s.split(Regex("(?<!\\\\)\\|")).map { satirIci(it.trim().replace("\\|", "|")).first }
    }

    // ---------------- 3) Satır içi biçim ----------------

    private val BAGLI_RESIM = Regex("\\[\\s*!\\[[^\\]]*\\]\\([^)]*\\)\\s*\\]\\([^)]*\\)")
    private val BAGLI_RESIM_REF = Regex("\\[\\s*!\\[[^\\]]*\\]\\[[^\\]]*\\]\\s*\\](\\([^)]*\\)|\\[[^\\]]*\\])")
    private val RESIM = Regex("!\\[[^\\]]*\\](\\([^)]*\\)|\\[[^\\]]*\\])")
    private val LINK = Regex("\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)")
    private val REF_LINK = Regex("\\[([^\\]]+)\\]\\[[^\\]]*\\]")
    private val OTOLINK = Regex("<(https?://[^>\\s]+)>")
    private val REF_TANIM = Regex("^\\s*\\[[^\\]]+\\]:\\s*\\S+.*$")

    /** Satır içi Markdown'ı düz yazıya çevirir ve bağlantıları ayrıca toplar. */
    fun satirIci(ham: String): Pair<String, List<Baglanti>> {
        if (REF_TANIM.matches(ham)) return "" to emptyList()
        val baglantilar = ArrayList<Baglanti>()
        var t = ham
        t = BAGLI_RESIM.replace(t, "")
        t = BAGLI_RESIM_REF.replace(t, "")
        t = RESIM.replace(t, "")
        t = OTOLINK.replace(t) { baglantilar.add(Baglanti(it.groupValues[1], it.groupValues[1])); it.groupValues[1] }
        t = LINK.replace(t) { m ->
            val yazi = m.groupValues[1].trim()
            val adres = m.groupValues[2]
            if (yazi.isNotEmpty() && !adres.startsWith("#")) baglantilar.add(Baglanti(sadelestir(yazi), adres))
            yazi
        }
        t = REF_LINK.replace(t) { it.groupValues[1] }
        t = sadelestir(t)
        return t to baglantilar.distinctBy { it.adres }
    }

    private fun sadelestir(s: String): String {
        var t = s
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        t = t.replace(Regex("__(.+?)__"), "$1")
        t = t.replace(Regex("~~(.+?)~~"), "$1")
        t = t.replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        t = t.replace(Regex("(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])"), "$1")
        t = t.replace(Regex("`+([^`]+)`+"), "$1")
        t = t.replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>])"), "$1")
        t = t.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
        return t
    }

    /** "[A](#a) | [B](#b)" gibi sadece sayfa içi bağlantı ve ayraçtan oluşan satırlar */
    private fun sadeceBaglantiListesi(ham: String, temiz: String): Boolean {
        val linkler = LINK.findAll(ham).toList()
        if (linkler.isEmpty()) return false
        if (!linkler.all { it.groupValues[2].startsWith("#") }) return false
        var kalan = ham
        linkler.forEach { kalan = kalan.replace(it.value, "") }
        return kalan.none { it.isLetterOrDigit() } && temiz.isNotBlank()
    }

    // ---------------- 4) Bölümlere ayırma ----------------

    fun bolumle(parcalar: List<Parca>, repoAdi: String?): Readme {
        var liste = parcalar
        var baslik: String? = null

        // İlk parça bir başlıksa ve öncesinde içerik yoksa bu README'nin adıdır
        val ilk = liste.firstOrNull()
        if (ilk is Parca.Baslik && (ilk.seviye == 1 || (repoAdi != null && benzer(ilk.metin, repoAdi)))) {
            baslik = ilk.metin
            liste = liste.drop(1)
        }

        val basliklar = liste.filterIsInstance<Parca.Baslik>()
        val ustSeviye = basliklar.minOfOrNull { it.seviye } ?: 2

        val bolumler = ArrayList<Bolum>()
        var mevcutBaslik = ""
        var mevcutSeviye = 0
        var mevcut = ArrayList<Parca>()

        fun bitir() {
            if (mevcut.isEmpty() && mevcutBaslik.isEmpty()) return
            val tur = if (mevcutBaslik.isEmpty()) BolumTuru.GIRIS else RepoAnaliz.bolumTuru(mevcutBaslik)
            if (mevcut.isNotEmpty() || tur != BolumTuru.GIRIS) {
                bolumler.add(Bolum(mevcutBaslik, mevcutSeviye, tur, mevcut))
            }
            mevcut = ArrayList()
        }

        for (p in liste) {
            if (p is Parca.Baslik && p.seviye <= ustSeviye) {
                bitir()
                mevcutBaslik = p.metin
                mevcutSeviye = p.seviye
            } else {
                mevcut.add(p)
            }
        }
        bitir()

        // Hiç başlık yoksa ve README adı da yoksa: tamamı giriş
        return Readme(baslik, bolumler.filter { it.parcalar.isNotEmpty() })
    }

    private fun benzer(a: String, b: String): Boolean {
        fun n(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return n(a) == n(b) || n(a).contains(n(b))
    }
}
