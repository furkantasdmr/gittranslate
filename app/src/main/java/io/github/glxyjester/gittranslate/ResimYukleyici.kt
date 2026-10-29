package io.github.glxyjester.gittranslate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** README resimleri için küçük, bağımlılıksız yükleyici (bellek önbellekli, büyük resimleri küçültür). */
object ResimYukleyici {
    private val havuz = Executors.newFixedThreadPool(2)
    private val ana = Handler(Looper.getMainLooper())
    private val onbellek = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private const val EN_FAZLA_BAYT = 12 * 1024 * 1024

    fun yukle(adres: String, enGenis: Int, sonuc: (Bitmap?) -> Unit) {
        onbellek.get(adres)?.let { sonuc(it); return }
        havuz.execute {
            val bmp = try { indir(adres, enGenis) } catch (_: Throwable) { null }
            ana.post {
                if (bmp != null) onbellek.put(adres, bmp)
                sonuc(bmp)
            }
        }
    }

    private fun indir(adres: String, enGenis: Int): Bitmap? {
        val b = URL(adres).openConnection() as HttpURLConnection
        b.connectTimeout = 10000
        b.readTimeout = 15000
        b.instanceFollowRedirects = true
        b.setRequestProperty("User-Agent", "GitTranslate-Android")
        try {
            if (b.responseCode != 200) return null
            val tur = b.contentType.orEmpty()
            if (tur.contains("svg") || tur.contains("html")) return null // Android SVG çizemez
            val veri = b.inputStream.use { girdi ->
                val cikti = java.io.ByteArrayOutputStream()
                val tampon = ByteArray(16 * 1024)
                while (true) {
                    val n = girdi.read(tampon); if (n < 0) break
                    cikti.write(tampon, 0, n)
                    if (cikti.size() > EN_FAZLA_BAYT) return null
                }
                cikti.toByteArray()
            }
            val olcu = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(veri, 0, veri.size, olcu)
            if (olcu.outWidth <= 0) return null
            var oran = 1
            while (olcu.outWidth / (oran * 2) >= enGenis) oran *= 2
            return BitmapFactory.decodeByteArray(veri, 0, veri.size, BitmapFactory.Options().apply { inSampleSize = oran })
        } finally {
            b.disconnect()
        }
    }
}
