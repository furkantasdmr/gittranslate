package io.github.glxyjester.gittranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CeviriListesiTest {

    private fun oku(ad: String) = javaClass.classLoader!!.getResource(ad)!!.readText()

    @Test
    fun cinceReadme() {
        val r = Markdown.ayristir(oku("cince.md"), "Administrative-divisions-of-China")
        val l = CeviriListesi.topla("中华人民共和国行政区划", r, null)
        assertEquals("中华人民共和国行政区划", l.first())
        assertTrue(l.contains("数据来源"))           // bölüm başlığı
        assertTrue(l.any { it.startsWith("根据国务院") }) // alıntı
        assertTrue(l.contains("省级（省份、直辖市、自治区）")) // tablo hücresi
        assertFalse(l.contains("13"))                // sayı hücresi çevrilmez
        assertEquals(l.size, l.toSet().size)         // tekrar yok
    }

    @Test
    fun surumNotlariVeSinir() {
        val notlar = Markdown.ayristir("## What's new\n\n- Faster startup\n- Bug fixes\n\n```\nnpm i x\n```", null)
        val l = CeviriListesi.topla(null, null, notlar)
        assertEquals(listOf("What's new", "Faster startup", "Bug fixes"), l)
        val uzun = Markdown.ayristir((1..50).joinToString("\n\n") { "Paragraph number $it with some words." }, null)
        assertTrue(CeviriListesi.topla(null, uzun, null, enFazlaHarf = 200).size < 10)
    }
}
