package com.glxyjester.githubcevirmen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun oku(ad: String) = javaClass.classLoader!!.getResource(ad)!!.readText()

    @Test
    fun cinceReadme_ozetVeBolumler() {
        val r = Markdown.ayristir(oku("cince.md"), "Administrative-divisions-of-China")
        assertEquals("Administrative-divisions-of-China", r.baslik)
        // Rozetler atılmış, ilk cümle özet olmalı
        assertTrue(r.ozet(), r.ozet().startsWith("中华人民共和国行政区划"))
        assertFalse(r.ozet().contains("shields.io"))
        val basliklar = r.bolumler.map { it.baslik }
        assertTrue(basliklar.toString(), basliklar.containsAll(listOf("数据来源", "数据下载", "数据库支持")))
        // "数据下载" bölümünde tablo var
        val indirme = r.bolumler.first { it.baslik == "数据下载" }
        val tablo = indirme.parcalar.filterIsInstance<Parca.Tablo>().first()
        assertEquals(6, tablo.satirlar.size)
        assertEquals("provinces.json", tablo.satirlar[1][1])
        assertEquals(BolumTuru.KURULUM, indirme.tur)
        // Alıntı + liste
        val kaynak = r.bolumler.first { it.baslik == "数据来源" }
        assertTrue(kaynak.parcalar.any { it is Parca.Alinti })
        val liste = kaynak.parcalar.filterIsInstance<Parca.Liste>().first()
        assertTrue(liste.ogeler.any { it.derinlik == 1 })
        assertTrue(liste.ogeler.any { o -> o.baglantilar.any { it.adres.contains("stats.gov.cn") } })
    }

    @Test
    fun ingilizceReadme_htmlKodVeIcindekiler() {
        val r = Markdown.ayristir(oku("ingilizce.md"), "yt-dlp")
        assertTrue(r.ozet(), r.ozet().startsWith("yt-dlp is a feature-rich command-line audio/video downloader"))
        assertFalse(r.ozet().contains("<"))
        val kurulum = r.ilk(BolumTuru.KURULUM)
        assertNotNull(r.bolumler.map { it.baslik }.toString(), kurulum)
        assertEquals("INSTALLATION", kurulum!!.baslik)
        // Kod bloğunun içindeki "# ..." satırları başlık sanılmamalı
        assertFalse(r.bolumler.any { it.baslik.startsWith("To update to nightly") })
        assertTrue(r.bolumler.flatMap { it.parcalar }.any { it is Parca.Kod })
        assertNotNull(r.ilk(BolumTuru.KULLANIM))
        // İçindekiler listesi (sadece #bağlantılar) atılmış olmalı
        val giris = r.giris!!
        assertFalse(giris.parcalar.any { p -> p is Parca.Liste && p.ogeler.any { it.metin == "INSTALLATION" } })
    }

    @Test
    fun satirIci() {
        val (m, b) = Markdown.satirIci("**Kalın** ve [bağlantı](https://x.com) ile `kod` ![resim](a.png)")
        assertEquals("Kalın ve bağlantı ile kod", m)
        assertEquals(listOf(Baglanti("bağlantı", "https://x.com")), b)
        assertEquals("snake_case_ad", Markdown.satirIci("snake_case_ad").first)
    }

    @Test
    fun htmlBasliklarVeBaglantilar() {
        val r = Markdown.ayristir(
            """
            <h1 align="center">Proje</h1>
            <p align="center"><img src="logo.png"></p>
            <p>Bir <a href="https://ornek.com">araç</a> &amp; kütüphane.</p>

            ## Usage
            ```bash
            ## bu başlık değil
            run --x
            ```
            """.trimIndent(),
            "proje",
        )
        assertEquals("Proje", r.baslik)
        assertEquals("Bir araç & kütüphane.", r.ozet())
        val kullanim = r.ilk(BolumTuru.KULLANIM)!!
        assertEquals("## bu başlık değil\nrun --x", (kullanim.parcalar.single() as Parca.Kod).kod)
    }

    @Test
    fun resimlerRozetlerHaric() {
        val r = Markdown.ayristir(
            "# X\n\n[![CI](https://github.com/a/b/actions/workflows/ci.yml/badge.svg)](x)\n\n" +
                "![Ekran](docs/shot.png)\n\n<p align=\"center\"><img src=\"https://x.com/demo.gif\" alt=\"demo\"></p>\n\nMetin ![küçük](i.png) burada.\n",
            "x",
        )
        val resimler = r.bolumler.flatMap { it.parcalar }.filterIsInstance<Parca.Resim>()
        assertEquals(listOf(Parca.Resim("Ekran", "docs/shot.png"), Parca.Resim("demo", "https://x.com/demo.gif")), resimler)
        assertEquals("Metin burada.", r.ozet())
    }

    @Test
    fun setextVeGirintiliKod() {
        val r = Markdown.ayristir("Başlık\n======\n\nMetin.\n\nInstall\n-------\n\n    pip install x\n", null)
        assertEquals("Başlık", r.baslik)
        val k = r.ilk(BolumTuru.KURULUM)!!
        assertEquals("pip install x", (k.parcalar.single() as Parca.Kod).kod)
    }
}

class RepoAnalizTest {

    @Test
    fun baglanti() {
        assertEquals(RepoAnaliz.BaglantiSonucu.Repo("modood", "Administrative-divisions-of-China"),
            RepoAnaliz.baglantiAyikla("Check this out https://github.com/modood/Administrative-divisions-of-China"))
        assertEquals(RepoAnaliz.BaglantiSonucu.Repo("a", "b.js"), RepoAnaliz.baglantiAyikla("https://github.com/a/b.js.git"))
        assertEquals(RepoAnaliz.BaglantiSonucu.Repo("a", "b"), RepoAnaliz.baglantiAyikla("github.com/a/b/blob/main/README.md"))
        assertEquals(RepoAnaliz.BaglantiSonucu.KisiSayfasi("torvalds"), RepoAnaliz.baglantiAyikla("https://github.com/torvalds"))
        assertEquals(RepoAnaliz.BaglantiSonucu.Yok, RepoAnaliz.baglantiAyikla("https://github.com/settings/profile"))
        assertEquals(RepoAnaliz.BaglantiSonucu.Repo("x", "y"), RepoAnaliz.baglantiAyikla(" x/y "))
        assertEquals(RepoAnaliz.BaglantiSonucu.Yok, RepoAnaliz.baglantiAyikla("merhaba"))
    }

    @Test
    fun bolumTurleri() {
        assertEquals(BolumTuru.KURULUM, RepoAnaliz.bolumTuru("Installation"))
        assertEquals(BolumTuru.KURULUM, RepoAnaliz.bolumTuru("🚀 Getting Started"))
        assertEquals(BolumTuru.KURULUM, RepoAnaliz.bolumTuru("安装"))
        assertEquals(BolumTuru.KULLANIM, RepoAnaliz.bolumTuru("Usage"))
        assertEquals(BolumTuru.KULLANIM, RepoAnaliz.bolumTuru("使用方法"))
        assertEquals(BolumTuru.KULLANIM, RepoAnaliz.bolumTuru("How to use"))
        assertEquals(BolumTuru.EK, RepoAnaliz.bolumTuru("License"))
        assertEquals(BolumTuru.EK, RepoAnaliz.bolumTuru("Contributing"))
        assertEquals(BolumTuru.OZELLIKLER, RepoAnaliz.bolumTuru("Features"))
        assertEquals(BolumTuru.DIGER, RepoAnaliz.bolumTuru("数据库支持"))
        assertEquals(BolumTuru.DIGER, RepoAnaliz.bolumTuru("Because reasons"))
    }

    @Test
    fun projeTurleri() {
        val t = RepoAnaliz.projeTurleri(
            listOf(".eslintrc.js", "LICENSE", "README.md", "dist", "lib", "package.json", "test"),
            "JavaScript", emptyList(), emptyList(), "Administrative-divisions-of-China",
            "provinces.json cities.json areas.json streets.csv villages.csv",
        )
        assertTrue(t.toString(), ProjeTuru.NODE in t)
        assertTrue(t.toString(), ProjeTuru.VERI in t)
        val apk = RepoAnaliz.projeTurleri(listOf("app", "build.gradle.kts"), "Kotlin", emptyList(), listOf("uygulama.apk"), "x", null)
        assertTrue(ProjeTuru.ANDROID_UYGULAMA in apk && ProjeTuru.ANDROID_KAYNAK in apk)
        assertEquals(TelefonUyumu.KULLANILIR, RepoAnaliz.telefonUyumu(apk))
        val liste = RepoAnaliz.projeTurleri(listOf("README.md"), null, listOf("awesome-list"), emptyList(), "awesome-go", null)
        assertEquals(TelefonUyumu.OKUNUR, RepoAnaliz.telefonUyumu(liste))
    }

    @Test
    fun lisans() {
        assertEquals(LisansTuru.SERBEST, RepoAnaliz.lisansTuru("MIT"))
        assertEquals(LisansTuru.SERBEST, RepoAnaliz.lisansTuru("WTFPL"))
        assertEquals(LisansTuru.PAYLASIMLI, RepoAnaliz.lisansTuru("GPL-3.0"))
        assertEquals(LisansTuru.YOK, RepoAnaliz.lisansTuru(null))
        assertEquals(LisansTuru.DIGER, RepoAnaliz.lisansTuru("NOASSERTION"))
    }

    @Test
    fun adres() {
        assertEquals("https://github.com/a/b/blob/main/docs/x.md", RepoAnaliz.adresCoz("./docs/x.md", "a", "b", "main"))
        assertEquals(null, RepoAnaliz.adresCoz("#kurulum", "a", "b", "main"))
        assertEquals("https://x.com", RepoAnaliz.adresCoz("https://x.com", "a", "b", "main"))
    }
}
