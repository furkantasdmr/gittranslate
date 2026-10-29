package io.github.glxyjester.gittranslate

/**
 * README panelinde çevrilecek yazıların listesi, ekranda göründükleri sırayla.
 * Arka plandaki ön hazırlık bu listeyi çevirir; panel aynı yazıları gösterdiği için
 * açıldığında hepsi önbellekte hazır olur. (Android'e bağımlı değildir, birim testli.)
 */
object CeviriListesi {

    /** Tablolarda panelde gösterilen en fazla satır (başlık dahil) */
    const val TABLO_SATIR = 13

    /** Çevrilmeye değer mi: harf içeren, iki harften uzun yazılar (tablo hücreleri için) */
    fun hucreCevrilir(h: String) = h.any { it.isLetter() } && h.length > 1

    fun topla(aciklama: String?, readme: Readme?, surumNotlari: Readme?, enFazlaHarf: Int = 120_000): List<String> {
        val liste = LinkedHashSet<String>()
        fun ekle(m: String?) {
            val t = m?.trim() ?: return
            if (t.isNotEmpty() && t.any { it.isLetter() }) liste.add(t)
        }
        fun parcalar(p: List<Parca>) = p.forEach { x ->
            when (x) {
                is Parca.Baslik -> ekle(x.metin)
                is Parca.Paragraf -> ekle(x.metin)
                is Parca.Alinti -> ekle(x.metin)
                is Parca.Liste -> x.ogeler.forEach { ekle(it.metin) }
                is Parca.Tablo -> x.satirlar.take(TABLO_SATIR).forEach { r -> r.forEach { h -> if (hucreCevrilir(h)) ekle(h) } }
                is Parca.Kod, is Parca.Resim -> {}
            }
        }
        fun belge(r: Readme?) = r?.bolumler?.forEach { b ->
            if (b.tur != BolumTuru.GIRIS) ekle(b.baslik)
            parcalar(b.parcalar)
        }

        ekle(aciklama)
        belge(readme)
        belge(surumNotlari)

        // Çok uzun README'lerde (binlerce satırlık kılavuzlar) bir sınır koy: çeviri servisini boğmayalım
        val sonuc = ArrayList<String>()
        var toplam = 0
        for (m in liste) {
            if (toplam + m.length > enFazlaHarf && sonuc.isNotEmpty()) break
            sonuc.add(m); toplam += m.length
        }
        return sonuc
    }
}
