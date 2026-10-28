package com.glxyjester.githubcevirmen

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/**
 * Ekranın tamamını kaplayan, dokunmaları geçiren şeffaf katman.
 * Her yabancı yazının tam üstüne, aynı boyutta bir kutu içinde çevirisini çizer.
 */
@SuppressLint("ViewConstructor")
class CeviriKatmani(context: Context) : View(context) {

    data class Oge(val alan: Rect, val ceviri: String)

    private class Cizim(val kutu: RectF, val duzen: StaticLayout, val x: Float, val y: Float)

    private var ogeler: List<Oge> = emptyList()
    private var cizimler: List<Cizim> = emptyList()
    private val konum = IntArray(2)
    private var sonKonumX = Int.MIN_VALUE
    private var sonKonumY = Int.MIN_VALUE

    private val karanlik: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val arkaPlan = Paint(Paint.ANTI_ALIAS_FLAG)
    private val serit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val yazi = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun goster(yeni: List<Oge>) {
        if (yeni == ogeler) return
        ogeler = yeni
        cizimleriHazirla()
        invalidate()
    }

    fun temizle() = goster(emptyList())

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cizimleriHazirla()
        invalidate()
    }

    private fun cizimleriHazirla() {
        getLocationOnScreen(konum)
        sonKonumX = konum[0]; sonKonumY = konum[1]
        val pay = dp(3f)
        val enKucuk = sp(9f)
        val enBuyuk = sp(15f)

        cizimler = ogeler.mapNotNull { o ->
            val r = o.alan
            val genislik = (r.width() - 2 * pay).toInt()
            if (genislik < dp(24f)) return@mapNotNull null

            // Yazıyı kutuya sığana kadar küçült
            val yuksekligeGore = r.height() * 0.62f
            var boyut = yuksekligeGore.coerceIn(enKucuk, enBuyuk)
            var duzen = duzenKur(o.ceviri, boyut, genislik)
            while (duzen.height > r.height() - pay && boyut > enKucuk) {
                boyut = (boyut - sp(1f)).coerceAtLeast(enKucuk)
                duzen = duzenKur(o.ceviri, boyut, genislik)
            }

            val sol = (r.left - konum[0]).toFloat()
            val ust = (r.top - konum[1]).toFloat()
            val yukseklik = maxOf(r.height().toFloat(), duzen.height + 2 * pay)
            val kutu = RectF(sol, ust, sol + r.width(), ust + yukseklik)
            val yaziY = ust + ((r.height() - duzen.height) / 2f).coerceAtLeast(pay)
            Cizim(kutu, duzen, sol + pay, yaziY)
        }
    }

    private fun duzenKur(metin: String, boyut: Float, genislik: Int): StaticLayout {
        val p = TextPaint(yazi).apply { textSize = boyut }
        return StaticLayout.Builder.obtain(metin, 0, metin.length, p, genislik)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cizimler.isEmpty()) return

        // Pencere konumu değiştiyse (ör. döndürme) hizalamayı yenile
        getLocationOnScreen(konum)
        if (konum[0] != sonKonumX || konum[1] != sonKonumY) cizimleriHazirla()

        val koyu = karanlik
        arkaPlan.color = if (koyu) Color.argb(245, 22, 27, 34) else Color.argb(248, 255, 255, 255)
        serit.color = if (koyu) Color.rgb(68, 147, 248) else Color.rgb(31, 111, 235)
        val yaziRengi = if (koyu) Color.rgb(230, 237, 243) else Color.rgb(31, 35, 40)
        val kose = dp(4f)

        for (c in cizimler) {
            canvas.drawRoundRect(c.kutu, kose, kose, arkaPlan)
            // Çevrildiğini belli eden ince mavi şerit
            canvas.drawRect(c.kutu.left, c.kutu.top + kose / 2, c.kutu.left + dp(2f), c.kutu.bottom - kose / 2, serit)
            c.duzen.paint.color = yaziRengi
            canvas.save()
            canvas.translate(c.x + dp(1f), c.y)
            c.duzen.draw(canvas)
            canvas.restore()
        }
    }
}
