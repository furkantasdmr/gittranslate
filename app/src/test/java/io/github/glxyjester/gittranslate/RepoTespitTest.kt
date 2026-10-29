package io.github.glxyjester.gittranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoTespitTest {

    private val dp = 2.625f
    private val h = 2400

    @Test
    fun baslikCifti_kesikAd() {
        // Kullanıcının ekran görüntüsündeki GitHub üst çubuğu
        val yazilar = listOf(
            EkranYazisi("10:55", 80, 40, 170, 80),
            EkranYazisi("modood", 140, 125, 260, 160),
            EkranYazisi("…tive-divisions-of-China", 140, 168, 660, 215),
            EkranYazisi("Home", 70, 1870, 140, 1900),
        )
        assertEquals(TespitSonucu("modood", "tive-divisions-of-China", TespitSonucu.Kesik.BASI),
            RepoTespit.bul(yazilar, h, dp))
    }

    @Test
    fun baglantiVeSahipAd() {
        assertEquals(TespitSonucu("a", "b"),
            RepoTespit.bul(listOf(EkranYazisi("Open https://github.com/a/b", 0, 900, 10, 950)), h, dp))
        assertEquals(TespitSonucu("yt-dlp", "yt-dlp"),
            RepoTespit.bul(listOf(EkranYazisi("yt-dlp / yt-dlp", 0, 300, 10, 350)), h, dp))
    }

    @Test
    fun listedeKesinDegil() {
        val yazilar = listOf(
            EkranYazisi("torvalds/linux", 40, 400, 600, 440),
            EkranYazisi("yt-dlp/yt-dlp", 40, 700, 600, 740),
        )
        assertEquals(TespitSonucu("torvalds", "linux", kesin = false), RepoTespit.bul(yazilar, h, dp))
    }

    @Test
    fun arayuzYazilariSahipSayilmaz() {
        val yazilar = listOf(
            EkranYazisi("Home", 40, 120, 200, 160),
            EkranYazisi("Explore", 40, 170, 200, 210),
        )
        assertNull(RepoTespit.bul(yazilar, h, dp))
    }

    @Test
    fun enYakin() {
        val adlar = listOf("china-division", "Administrative-divisions-of-China", "go-chinese")
        // Kullanıcının ekran görüntüsündeki durum: sonu kesik okunmuş
        assertEquals("Administrative-divisions-of-China", RepoTespit.enYakin("Administrative-divisions-of-Chin", adlar))
        assertEquals("Administrative-divisions-of-China", RepoTespit.enYakin("administrative-divisions-of-chlna", adlar))
        assertEquals("go-chinese", RepoTespit.enYakin("go-chinese", adlar))
        assertNull(RepoTespit.enYakin("tamamen-baska", adlar))
    }

    @Test
    fun kesikEslestirme() {
        val adlar = listOf("china-division", "Administrative-divisions-of-China", "go-chinese")
        assertEquals("Administrative-divisions-of-China",
            RepoTespit.kesikAdiEslestir("tive-divisions-of-China", TespitSonucu.Kesik.BASI, adlar))
        assertEquals("china-division", RepoTespit.kesikAdiEslestir("china-div", TespitSonucu.Kesik.SONU, adlar))
        assertNull(RepoTespit.kesikAdiEslestir("yok", TespitSonucu.Kesik.BASI, adlar))
    }
}
