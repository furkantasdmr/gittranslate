package io.github.glxyjester.gittranslate

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * GitHub uygulaması açıkken ekran kenarında bir balon gösterir. Balona dokununca o an açık olan
 * reponun README'si, kullanıcının dilinde bir panelde açılır ([RepoActivity]).
 *
 * Hangi repoda olunduğu önce ekrandaki yazılardan (erişilebilirlik ağacı), olmazsa dokunulduğu anda
 * ekranın üst kısmının görüntüsünden (OCR) bulunur.
 *
 * Kararlılık: her geri çağırma hata korumalı (bazı telefonlar çöken erişilebilirlik servisini
 * tamamen kapatıyor), aynı anda tek arama, zaman aşımları, klavye açıkken gizlenme.
 */
class BalonServisi : AccessibilityService() {

    companion object {
        const val GITHUB = "com.github.android"
        private const val ETIKET = "GitTranslate"
        private const val TERCIH = "balon"
    }

    private val ana = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var balon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var githubOnde = false
    private var klavyeAcik = false

    private var sonRepo: TespitSonucu? = null
    private var sonYontem = ""
    private var sonYaziSayisi = 0

    /** Dokunma sonrası arama sürüyor mu (çift dokunuşu engeller) */
    private var mesgul = false
    private var sonAcilis = 0L

    private val okuyucu by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val yurutucu = Executors.newSingleThreadExecutor()

    private var planZamani = 0L
    private val taramaGorevi = Runnable { planZamani = 0L; guvenli { agactanBul() } }
    private val onDurumGorevi = Runnable { guvenli { onDurumu() } }
    private var nabiz: ValueAnimator? = null

    private inline fun guvenli(blok: () -> Unit) {
        try { blok() } catch (e: Throwable) { Log.w(ETIKET, "hata", e) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        guvenli {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            GitHubApi.baslat(this)
            onDurumu()
        }
    }

    override fun onAccessibilityEvent(olay: AccessibilityEvent?) {
        olay ?: return
        guvenli { olayIsle(olay) }
    }

    private fun olayIsle(olay: AccessibilityEvent) {
        when (olay.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                ana.removeCallbacks(onDurumGorevi)
                ana.postDelayed(onDurumGorevi, 120)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (githubOnde && olay.packageName?.toString() == GITHUB) taramaPlanla(500)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(yeni: Configuration) {
        super.onConfigurationChanged(yeni)
        // Döndürmeden sonra balon ekran dışında kalmasın
        guvenli { ana.postDelayed({ guvenli { kenaraYerlestir(animasyon = false) } }, 300) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        guvenli { balonuKaldir() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        guvenli {
            ana.removeCallbacksAndMessages(null)
            balonuKaldir()
            yurutucu.shutdownNow()
        }
        super.onDestroy()
    }

    // ---------------- GitHub önde mi? ----------------

    private fun aktifPaket(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun pencereler(): List<AccessibilityWindowInfo> = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }

    private fun githubKokleri(): List<AccessibilityNodeInfo> {
        val liste = ArrayList<AccessibilityNodeInfo>()
        for (w in pencereler()) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val k = try { w.root } catch (_: Exception) { null } ?: continue
            if (k.packageName?.toString() == GITHUB) liste.add(k)
        }
        if (liste.isEmpty()) {
            try { rootInActiveWindow?.takeIf { it.packageName?.toString() == GITHUB }?.let { liste.add(it) } } catch (_: Exception) {}
        }
        return liste
    }

    private fun onDurumu() {
        val p = aktifPaket()
        klavyeAcik = pencereler().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        when {
            p == packageName -> balon?.visibility = View.GONE // kendi panelimiz açık
            p == GITHUB || (p == null && githubKokleri().isNotEmpty()) -> {
                githubOnde = true
                if (balon == null) balonEkle()
                balon?.visibility = if (klavyeAcik) View.GONE else View.VISIBLE
                taramaPlanla(150)
            }
            p != null && p != "com.android.systemui" -> {
                githubOnde = false
                balonuKaldir()
            }
        }
    }

    private fun taramaPlanla(gecikme: Long) {
        val z = SystemClock.uptimeMillis() + gecikme
        if (planZamani != 0L && planZamani <= z) return // erken planlanmış tarama varsa bozma
        ana.removeCallbacks(taramaGorevi)
        planZamani = z
        ana.postAtTime(taramaGorevi, z)
    }

    // ---------------- Repo bulma: ekran ağacı ----------------

    private fun agactanBul(): TespitSonucu? {
        if (!githubOnde) return null
        val yazilar = ArrayList<EkranYazisi>()
        for (k in githubKokleri()) topla(k, yazilar, 0)
        sonYaziSayisi = yazilar.size
        val dm = resources.displayMetrics
        val t = RepoTespit.bul(yazilar, dm.heightPixels, dm.density)
        sonRepo = t
        sonYontem = if (t != null) getString(R.string.method_screen_text) else ""
        balonuGuncelle()
        return t
    }

    private fun topla(d: AccessibilityNodeInfo, liste: MutableList<EkranYazisi>, derinlik: Int) {
        if (derinlik > 60 || liste.size > 800) return
        val r = Rect()
        d.getBoundsInScreen(r)
        listOfNotNull(d.text?.toString(), d.contentDescription?.toString()).forEach { m ->
            if (m.isNotBlank() && m.length < 300 && !r.isEmpty) liste.add(EkranYazisi(m.trim(), r.left, r.top, r.right, r.bottom))
        }
        for (i in 0 until d.childCount) {
            val c = try { d.getChild(i) } catch (_: Exception) { null } ?: continue
            topla(c, liste, derinlik + 1)
        }
    }

    // ---------------- Repo bulma: ekran görüntüsü (OCR) ----------------

    /** Sonuç her durumda en fazla 5 sn içinde bir kez döner. */
    private fun goruntudenBul(sonuc: (TespitSonucu?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { sonuc(null); return }
        var bitti = false
        val tek: (TespitSonucu?) -> Unit = { t -> if (!bitti) { bitti = true; balon?.visibility = View.VISIBLE; sonuc(t) } }
        ana.postDelayed({ tek(null) }, 5000)
        goruntuAl(tek)
    }

    @TargetApi(30)
    private fun goruntuAl(sonuc: (TespitSonucu?) -> Unit) {
        balon?.visibility = View.INVISIBLE // kendi balonumuz görüntüye girmesin
        ana.postDelayed({
            guvenli {
                takeScreenshot(Display.DEFAULT_DISPLAY, yurutucu, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val bmp = try {
                            val tampon = r.hardwareBuffer
                            val donanim = Bitmap.wrapHardwareBuffer(tampon, r.colorSpace)
                            val yazilim = donanim?.copy(Bitmap.Config.ARGB_8888, false)
                            donanim?.recycle(); tampon.close()
                            // Sadece üst %35: başlık çubuğu burada
                            yazilim?.let { b ->
                                val k = Bitmap.createBitmap(b, 0, 0, b.width, (b.height * 0.35f).toInt())
                                if (k !== b) b.recycle()
                                k
                            }
                        } catch (_: Throwable) { null }
                        ana.post {
                            if (bmp == null) { sonuc(null); return@post }
                            try {
                                okuyucu.process(InputImage.fromBitmap(bmp, 0))
                                    .addOnSuccessListener { yazi ->
                                        guvenli {
                                            val yazilar = yazi.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                                                val k = l.boundingBox ?: return@mapNotNull null
                                                EkranYazisi(l.text, k.left, k.top, k.right, k.bottom)
                                            }
                                            val dm = resources.displayMetrics
                                            sonuc(RepoTespit.bul(yazilar, dm.heightPixels, dm.density))
                                        }
                                    }
                                    .addOnFailureListener { sonuc(null) }
                                    .addOnCompleteListener { bmp.recycle() }
                            } catch (_: Throwable) { sonuc(null) }
                        }
                    }

                    override fun onFailure(kod: Int) { ana.post { sonuc(null) } }
                })
            }
        }, 90)
    }

    // ---------------- Balona dokunulunca ----------------

    private fun balonaDokunuldu() {
        val simdi = SystemClock.uptimeMillis()
        if (mesgul || simdi - sonAcilis < 800) return
        balon?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val t = agactanBul()
        if (t != null && t.kesik == TespitSonucu.Kesik.YOK) { panelAc(t.sahip, t.ad); return }

        // Ağaçta yoksa ya da ad kesikse: ekrandan oku
        mesgulOl(true)
        goruntudenBul { g ->
            guvenli {
                val bulunan = g ?: t
                if (bulunan == null) {
                    mesgulOl(false)
                    Toast.makeText(this, R.string.bubble_no_repo, Toast.LENGTH_LONG).show()
                    return@guvenli
                }
                if (g != null) { sonRepo = g; sonYontem = getString(R.string.method_screenshot) }
                if (bulunan.kesik == TespitSonucu.Kesik.YOK) {
                    mesgulOl(false); panelAc(bulunan.sahip, bulunan.ad); return@guvenli
                }
                var cevaplandi = false
                val devam: (String) -> Unit = { ad -> if (!cevaplandi) { cevaplandi = true; mesgulOl(false); panelAc(bulunan.sahip, ad) } }
                ana.postDelayed({ devam(bulunan.ad) }, 6000)
                GitHubApi.repoAdlari(bulunan.sahip) { adlar ->
                    devam(RepoTespit.kesikAdiEslestir(bulunan.ad, bulunan.kesik, adlar) ?: bulunan.ad)
                }
            }
        }
    }

    private fun panelAc(sahip: String, ad: String) {
        sonAcilis = SystemClock.uptimeMillis()
        guvenli {
            startActivity(
                Intent(this, RepoActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, "https://github.com/$sahip/$ad")
                    .putExtra(RepoActivity.MOD, RepoActivity.MOD_README)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun teshis() {
        balon?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        agactanBul()
        val r = sonRepo
        val m = if (r != null) getString(R.string.bubble_detected, "${r.sahip}/${r.ad}", sonYontem)
        else getString(R.string.bubble_not_detected, sonYaziSayisi)
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    // ---------------- Balon ----------------

    private fun boyut() = (54 * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun balonEkle() {
        val pencere = wm ?: return
        val d = resources.displayMetrics.density
        val b = boyut()
        val t = ImageView(this).apply {
            setImageResource(R.drawable.ic_balon)
            elevation = 6 * d
            contentDescription = getString(R.string.bubble_desc)
        }
        val tercih = getSharedPreferences(TERCIH, MODE_PRIVATE)
        val p = WindowManager.LayoutParams(
            b, b,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = tercih.getInt("y", (resources.displayMetrics.heightPixels * 0.55f).toInt())
            x = if (tercih.getBoolean("sol", false)) kenarPayi() else resources.displayMetrics.widthPixels - b - kenarPayi()
        }

        var basX = 0f; var basY = 0f; var ilkX = 0; var ilkY = 0
        var surukle = false; var basZaman = 0L
        val uzunBasma = Runnable { if (!surukle) { surukle = false; basZaman = -1; teshis() } }
        t.setOnTouchListener { _, e ->
            try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        basX = e.rawX; basY = e.rawY; ilkX = p.x; ilkY = p.y
                        surukle = false; basZaman = SystemClock.uptimeMillis()
                        t.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                        ana.postDelayed(uzunBasma, 650)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - basX; val dy = e.rawY - basY
                        if (!surukle && hypot(dx, dy) > 10 * d) { surukle = true; ana.removeCallbacks(uzunBasma) }
                        if (surukle) {
                            p.x = (ilkX + dx).toInt(); p.y = (ilkY + dy).toInt()
                            guncelle()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ana.removeCallbacks(uzunBasma)
                        t.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        when {
                            surukle -> kenaraYerlestir(animasyon = true)
                            basZaman < 0 -> {} // uzun basma zaten işlendi
                            e.actionMasked == MotionEvent.ACTION_UP -> balonaDokunuldu()
                        }
                    }
                }
            } catch (x: Throwable) { Log.w(ETIKET, "dokunma", x) }
            true
        }
        try {
            pencere.addView(t, p)
            balon = t; params = p
            balonuGuncelle()
            kenaraYerlestir(animasyon = false)
        } catch (x: Throwable) { Log.w(ETIKET, "balon eklenemedi", x) }
    }

    private fun kenarPayi() = (6 * resources.displayMetrics.density).toInt()

    private fun guncelle() {
        val v = balon ?: return; val p = params ?: return
        try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    /** En yakın kenara yapıştır, ekran dışına taşmasın; konumu hatırla. */
    private fun kenaraYerlestir(animasyon: Boolean) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val b = boyut()
        val sol = p.x + b / 2 < dm.widthPixels / 2
        val hedefX = if (sol) kenarPayi() else dm.widthPixels - b - kenarPayi()
        val ust = (40 * dm.density).toInt()
        p.y = p.y.coerceIn(ust, (dm.heightPixels - b - ust).coerceAtLeast(ust))
        getSharedPreferences(TERCIH, MODE_PRIVATE).edit().putInt("y", p.y).putBoolean("sol", sol).apply()
        if (!animasyon || abs(p.x - hedefX) < 4) { p.x = hedefX; guncelle(); return }
        ValueAnimator.ofInt(p.x, hedefX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { p.x = it.animatedValue as Int; guncelle() }
            start()
        }
    }

    private fun mesgulOl(m: Boolean) {
        mesgul = m
        val v = balon ?: return
        nabiz?.cancel(); nabiz = null
        if (m) {
            nabiz = ValueAnimator.ofFloat(1f, 0.45f).apply {
                duration = 450; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { v.alpha = it.animatedValue as Float }
                start()
            }
        } else balonuGuncelle()
    }

    private val griFiltre = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    private fun balonuGuncelle() {
        val v = balon ?: return
        if (mesgul) return
        val bulundu = sonRepo != null
        // Repo bulunduysa renkli ve tam görünür; bulunamadıysa soluk ve gri (yine de dokunulabilir)
        v.colorFilter = if (bulundu) null else griFiltre
        v.alpha = if (bulundu) 1f else 0.6f
    }

    private fun balonuKaldir() {
        nabiz?.cancel(); nabiz = null
        mesgul = false
        balon?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        balon = null; params = null
        sonRepo = null
    }
}
