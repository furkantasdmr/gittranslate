package com.glxyjester.githubcevirmen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import android.annotation.TargetApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Bazı uygulamalar (GitHub'ın README görünümü gibi) yazıyı Android'e metin olarak vermiyor,
 * ekrana resim gibi çiziyor. Bu sınıf ekranın görüntüsünü alıp yazıları ML Kit ile
 * telefonun içinde okur (OCR). Çince okuyucu Latin harflerini de okur.
 */
class EkranOkuyucu(private val servis: AccessibilityService) {

    data class Blok(val alan: Rect, val metin: String)

    private val ana = Handler(Looper.getMainLooper())
    private val yurutucu = Executors.newSingleThreadExecutor()
    private val tanima = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    var sonHata = ""
        private set
    var sonSure = 0L
        private set
    var sonBlokSayisi = 0
        private set
    var calismaSayisi = 0
        private set

    val destekleniyor: Boolean get() = Build.VERSION.SDK_INT >= 30

    /**
     * @param pencere GitHub penceresi (Android 14+ sadece onun görüntüsünü alır, kendi katmanımız karışmaz)
     * @param gizle Android 11-13'te tam ekran görüntüsü alınırken kendi çevirilerimizi gizlemek için
     */
    fun oku(pencere: AccessibilityWindowInfo?, gizle: (Boolean) -> Unit, sonuc: (List<Blok>?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            sonHata = "Ekrandan okuma için Android 11 veya üstü gerekli"
            sonuc(null); return
        }
        calismaSayisi++
        val baslangic = SystemClock.uptimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 34 && pencere != null) {
                val sinir = Rect().also { pencere.getBoundsInScreen(it) }
                pencereGoruntusu(pencere.id, sinir, baslangic, sonuc)
            } else {
                gizle(true)
                // Katmanın ekrandan kalkması için bir kare bekle
                ana.postDelayed({ tamEkranGoruntusu(gizle, baslangic, sonuc) }, 80)
            }
        } catch (e: Exception) {
            gizle(false)
            sonHata = "Görüntü alınamadı: ${e.message}"
            sonuc(null)
        }
    }

    @TargetApi(34)
    private fun pencereGoruntusu(id: Int, sinir: Rect, baslangic: Long, sonuc: (List<Blok>?) -> Unit) {
        servis.takeScreenshotOfWindow(id, yurutucu, geriCagirma(sinir.left, sinir.top, {}, baslangic, sonuc))
    }

    @TargetApi(30)
    private fun tamEkranGoruntusu(gizle: (Boolean) -> Unit, baslangic: Long, sonuc: (List<Blok>?) -> Unit) {
        try {
            servis.takeScreenshot(Display.DEFAULT_DISPLAY, yurutucu, geriCagirma(0, 0, gizle, baslangic, sonuc))
        } catch (e: Exception) {
            gizle(false)
            sonHata = "Görüntü alınamadı: ${e.message}"
            sonuc(null)
        }
    }

    @TargetApi(30)
    private fun geriCagirma(
        ofsX: Int, ofsY: Int, gizle: (Boolean) -> Unit, baslangic: Long, sonuc: (List<Blok>?) -> Unit,
    ) = object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
            val bmp: Bitmap? = try {
                val tampon = r.hardwareBuffer
                val donanim = Bitmap.wrapHardwareBuffer(tampon, r.colorSpace)
                val kopya = donanim?.copy(Bitmap.Config.ARGB_8888, false)
                donanim?.recycle()
                tampon.close()
                kopya
            } catch (e: Exception) {
                sonHata = "Görüntü çevrilemedi: ${e.message}"; null
            }
            ana.post {
                gizle(false)
                if (bmp == null) { sonuc(null); return@post }
                tanima.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { yazi ->
                        val bloklar = yazi.textBlocks.mapNotNull { b ->
                            val kutu = b.boundingBox ?: return@mapNotNull null
                            val satirlar = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
                            if (satirlar.isEmpty()) return@mapNotNull null
                            val cjk = satirlar.any { s -> s.any { cjkMi(it) } }
                            val metin = satirlar.joinToString(if (cjk) "" else " ")
                            kutu.offset(ofsX, ofsY)
                            Blok(kutu, metin)
                        }
                        sonBlokSayisi = bloklar.size
                        sonSure = SystemClock.uptimeMillis() - baslangic
                        sonHata = ""
                        sonuc(bloklar)
                    }
                    .addOnFailureListener { e ->
                        sonHata = "Yazı okunamadı: ${e.message}"
                        sonuc(null)
                    }
                    .addOnCompleteListener { bmp.recycle() }
            }
        }

        override fun onFailure(kod: Int) {
            ana.post {
                gizle(false)
                sonHata = when (kod) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "çok sık görüntü istendi"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "pencere korumalı"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "görüntü izni yok (servisi kapatıp aç)"
                    else -> "görüntü hatası kodu $kod"
                }
                sonuc(null)
            }
        }
    }

    private fun cjkMi(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    fun kapat() {
        tanima.close()
        yurutucu.shutdownNow()
    }
}
