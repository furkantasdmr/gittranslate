package io.github.glxyjester.gittranslate

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * GitHub uygulaması açıkken ekran kenarında bir balon gösterir.
 *
 *  - Bir repo sayfası açılınca repo adı ekrandan bulunur ve [OnHazirlik] arka planda README'yi,
 *    açıklamayı ve son sürüm notlarını indirip çevirir. Bu sırada balon gri kalır, çevresinde
 *    ilerleme halkası dolar; her şey çevrilince balon renklenir.
 *  - Balona dokununca [RepoActivity] çevrilmiş hâliyle anında açılır.
 *
 * Kararlılık: her geri çağırma hata korumalı (bazı telefonlar çöken erişilebilirlik servisini
 * tamamen kapatıyor), aynı anda tek arama, zaman aşımları, klavye açıkken gizlenme.
 */
class BalonServisi : AccessibilityService() {

    companion object {
        const val GITHUB = "com.github.android"
        private const val ETIKET = "GitTranslate"
        private const val TERCIH = "balon"
        /** Repo ekranda bu kadar süre kalınca arka planda hazırlık başlar (listede kaydırırken değil) */
        private const val BEKLEME = 600L
    }

    private val ana = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var balon: FrameLayout? = null
    private var resim: ImageView? = null
    private var halka: Halka? = null
    private var params: WindowManager.LayoutParams? = null
    private var githubOnde = false
    private var klavyeAcik = false

    private var sonRepo: TespitSonucu? = null
    private var sonYontem = ""
    private var sonYaziSayisi = 0

    /** Aynı repo bir süre ekranda kaldı mı */
    private var aday: TespitSonucu? = null
    private var adayZamani = 0L
    private var bosZamani = 0L
    private var sonArkaOkuma = 0L
    private var arkaOkumaSuruyor = false
    /** Son repo ekran yazısından mı (sık kontrol edilir) yoksa ekran görüntüsünden mi bulundu */
    private var sonKaynakGoruntu = false
    private var bosGoruntuSayisi = 0
    /** Ekran bir süre sabit kalınca (kaydırma/geçiş bitince) bir kez ekrandan oku */
    private val yerlesmeGorevi = Runnable { guvenli { if (githubOnde && (sonRepo == null || sonKaynakGoruntu)) arkaPlandaOku(zorla = true) } }

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
            OnHazirlik.dinleyici = { guvenli { balonuGuncelle() } }
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
                if (githubOnde && olay.packageName?.toString() == GITHUB) {
                    taramaPlanla(450)
                    ana.removeCallbacks(yerlesmeGorevi)
                    ana.postDelayed(yerlesmeGorevi, 1000)
                }
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
            OnHazirlik.dinleyici = null
            ana.removeCallbacksAndMessages(null)
            balonuKaldir()
            yurutucu.shutdownNow()
        }
        super.onDestroy()
    }

    // ---------------- GitHub önde mi? ----------------

    private fun aktifPaket(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun pencereler(): List<AccessibilityWindowInfo> = try { windows ?: emptyList() } catch (_: Exception) { emptyList() }

    private fun githubPenceresi(): AccessibilityWindowInfo? = pencereler().firstOrNull { w ->
        w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (try { w.root?.packageName?.toString() } catch (_: Exception) { null }) == GITHUB
    }

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
                ana.removeCallbacks(yerlesmeGorevi)
                ana.postDelayed(yerlesmeGorevi, 900)
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
        if (t != null) tespitIsle(t, goruntu = false)
        else if (!sonKaynakGoruntu) tespitIsle(null, goruntu = false)
        // Ekran yazısında yoksa ekran görüntüsünden bakılır; o sonuç ekran yazısının "boş" demesiyle silinmez
        if (t == null) arkaPlandaOku(zorla = false)
        return t
    }

    /** Bulunan repoyu kaydet; aynı repo bir süre ekranda kalırsa arka planda hazırlığa başla. */
    private fun tespitIsle(t: TespitSonucu?, goruntu: Boolean) {
        val simdi = SystemClock.uptimeMillis()
        if (t == null) {
            if (goruntu) {
                // Ekran görüntüsünde iki kez üst üste repo yoksa başka bir sayfaya geçilmiştir
                if (++bosGoruntuSayisi >= 2) { sonRepo = null; aday = null; sonKaynakGoruntu = false }
            } else {
                if (bosZamani == 0L) bosZamani = simdi
                // Kısa süreli boşlukları (kaydırma, animasyon) yok say; uzun sürerse balon griye döner
                if (simdi - bosZamani > 1500) { sonRepo = null; aday = null }
            }
            balonuGuncelle()
            return
        }
        bosZamani = 0L
        if (goruntu) bosGoruntuSayisi = 0
        sonRepo = t
        sonKaynakGoruntu = goruntu
        sonYontem = getString(if (goruntu) R.string.method_screenshot else R.string.method_screen_text)
        if (t.kesin) {
            if (goruntu) {
                // Ekran görüntüsü zaten ekran durulunca alınıyor: beklemeden başla
                aday = t; OnHazirlik.baslat(this, t)
            } else {
                val ayni = aday?.let { it.sahip.equals(t.sahip, true) && it.ad.equals(t.ad, true) } == true
                if (!ayni) { aday = t; adayZamani = simdi; taramaPlanla(BEKLEME + 50) }
                else if (simdi - adayZamani >= BEKLEME) OnHazirlik.baslat(this, t)
                else taramaPlanla(BEKLEME - (simdi - adayZamani) + 50)
            }
        }
        balonuGuncelle()
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

    /**
     * Ekran yazısında repo yoksa ekranın görüntüsünden arka planda okur.
     * Android 14+: sadece GitHub penceresinin görüntüsü (balon görüntüye girmez).
     * Android 11-13: tam ekran görüntüsü; balon gizlenmez (titreme olmasın), "文A" yazısı repo adına benzemez.
     * Kaydırırken en fazla 2,5 sn'de bir; ekran durulunca ([zorla]) hemen.
     */
    private fun arkaPlandaOku(zorla: Boolean) {
        if (Build.VERSION.SDK_INT < 30 || arkaOkumaSuruyor || mesgul) return
        if (aktifPaket() != GITHUB) return // kendi panelimiz ya da başka bir şey öndeyse okuma
        val simdi = SystemClock.uptimeMillis()
        if (simdi - sonArkaOkuma < (if (zorla) 900 else 2500)) return
        sonArkaOkuma = simdi
        arkaOkumaSuruyor = true
        var bitti = false
        val tek: (TespitSonucu?) -> Unit = { t ->
            if (!bitti) {
                bitti = true; arkaOkumaSuruyor = false
                if (githubOnde) guvenli { tespitIsle(t, goruntu = true) }
            }
        }
        ana.postDelayed({ tek(null) }, 5000)
        val w = githubPenceresi()
        if (Build.VERSION.SDK_INT >= 34 && w != null) pencereGoruntusu(w, tek)
        else guvenli { takeScreenshot(Display.DEFAULT_DISPLAY, yurutucu, geriCagirma(0, 0, tek)) }
    }

    @TargetApi(34)
    private fun pencereGoruntusu(w: AccessibilityWindowInfo, sonuc: (TespitSonucu?) -> Unit) {
        val sinir = Rect().also { w.getBoundsInScreen(it) }
        guvenli {
            takeScreenshotOfWindow(w.id, yurutucu, geriCagirma(sinir.left, sinir.top, sonuc))
        }
    }

    /** Dokunulunca: Android 11+ tam ekran görüntüsü (balon bir an gizlenir). Sonuç en fazla 5 sn'de döner. */
    private fun goruntudenBul(sonuc: (TespitSonucu?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { sonuc(null); return }
        var bitti = false
        val tek: (TespitSonucu?) -> Unit = { t -> if (!bitti) { bitti = true; balon?.visibility = View.VISIBLE; sonuc(t) } }
        ana.postDelayed({ tek(null) }, 5000)
        val w = githubPenceresi()
        if (Build.VERSION.SDK_INT >= 34 && w != null) { pencereGoruntusu(w, tek); return }
        tamEkranGoruntusu(tek)
    }

    @TargetApi(30)
    private fun tamEkranGoruntusu(sonuc: (TespitSonucu?) -> Unit) {
        balon?.visibility = View.INVISIBLE // kendi balonumuz görüntüye girmesin
        ana.postDelayed({
            guvenli { takeScreenshot(Display.DEFAULT_DISPLAY, yurutucu, geriCagirma(0, 0, sonuc)) }
        }, 90)
    }

    @TargetApi(30)
    private fun geriCagirma(ofsX: Int, ofsY: Int, sonuc: (TespitSonucu?) -> Unit) = object : TakeScreenshotCallback {
        override fun onSuccess(r: ScreenshotResult) {
            val bmp = try {
                val tampon = r.hardwareBuffer
                val donanim = Bitmap.wrapHardwareBuffer(tampon, r.colorSpace)
                val yazilim = donanim?.copy(Bitmap.Config.ARGB_8888, false)
                donanim?.recycle(); tampon.close()
                // Sadece üst %35: başlık çubuğu burada
                yazilim?.let { b ->
                    val k = Bitmap.createBitmap(b, 0, 0, b.width, (b.height * 0.35f).toInt().coerceAtLeast(1))
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
                                    EkranYazisi(l.text, k.left + ofsX, k.top + ofsY, k.right + ofsX, k.bottom + ofsY)
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
    }

    // ---------------- Balona dokunulunca ----------------

    private fun balonaDokunuldu() {
        val simdi = SystemClock.uptimeMillis()
        if (mesgul || simdi - sonAcilis < 800) return
        balon?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val t = agactanBul() ?: sonRepo

        // Arka planda hazırlandıysa (ya da hazırlanıyorsa) doğrudan aç: ad zaten düzeltildi
        t?.let { OnHazirlik.cozulmusAd(it) }?.let { (s, a) -> panelAc(s, a); return }
        if (t != null && t.kesik == TespitSonucu.Kesik.YOK) { panelAc(t.sahip, t.ad); return }

        // Ekran yazısında yoksa ya da ad kesikse: ekrandan oku
        mesgulOl(true)
        goruntudenBul { g ->
            guvenli {
                val bulunan = g ?: t
                if (bulunan == null) {
                    mesgulOl(false)
                    Toast.makeText(this, R.string.bubble_no_repo, Toast.LENGTH_LONG).show()
                    return@guvenli
                }
                if (g != null) tespitIsle(g, goruntu = true)
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
        val h = OnHazirlik.hal
        val m = if (r != null) {
            val durum = when {
                OnHazirlik.cozulmusAd(r) == null || h == null -> ""
                h.durum == OnHazirlik.Durum.HAZIR -> "\n✅ " + getString(R.string.bubble_ready)
                h.durum == OnHazirlik.Durum.HAZIRLANIYOR -> "\n⏳ " + getString(R.string.bubble_preparing, (h.ilerleme * 100).toInt())
                else -> "\n⚠️ " + getString(R.string.bubble_prep_failed)
            }
            getString(R.string.bubble_detected, "${r.sahip}/${r.ad}", sonYontem) + durum
        } else getString(R.string.bubble_not_detected, sonYaziSayisi)
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    // ---------------- Balon ----------------

    private fun boyut() = (54 * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun balonEkle() {
        val pencere = wm ?: return
        val d = resources.displayMetrics.density
        val b = boyut()
        val kap = FrameLayout(this).apply {
            elevation = 6 * d
            contentDescription = getString(R.string.bubble_desc)
        }
        val res = ImageView(this).apply { setImageResource(R.drawable.ic_balon) }
        val hl = Halka(this).apply { visibility = View.GONE }
        kap.addView(res, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        kap.addView(hl, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
        val uzunBasma = Runnable { if (!surukle) { basZaman = -1; teshis() } }
        kap.setOnTouchListener { _, e ->
            try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        basX = e.rawX; basY = e.rawY; ilkX = p.x; ilkY = p.y
                        surukle = false; basZaman = SystemClock.uptimeMillis()
                        kap.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
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
                        kap.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
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
            pencere.addView(kap, p)
            balon = kap; resim = res; halka = hl; params = p
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

    /**
     * Gri: repo yok ya da hazırlanıyor (halka doluyor). Renkli: bu repo tamamen çevrildi.
     */
    private fun balonuGuncelle() {
        val v = balon ?: return
        if (mesgul) return
        val r = sonRepo
        val h = OnHazirlik.hal
        val buRepo = r != null && OnHazirlik.cozulmusAd(r) != null && h != null
        val hazir = buRepo && h!!.durum == OnHazirlik.Durum.HAZIR
        val hazirlaniyor = buRepo && h!!.durum == OnHazirlik.Durum.HAZIRLANIYOR

        resim?.colorFilter = if (hazir) null else griFiltre
        v.alpha = if (r != null) 1f else 0.6f
        halka?.let { hl ->
            hl.visibility = if (hazirlaniyor) View.VISIBLE else View.GONE
            if (hazirlaniyor) hl.ilerleme = h!!.ilerleme
        }
    }

    private fun balonuKaldir() {
        nabiz?.cancel(); nabiz = null
        mesgul = false
        balon?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        balon = null; resim = null; halka = null; params = null
        sonRepo = null; aday = null; sonKaynakGoruntu = false; bosGoruntuSayisi = 0
    }

    /** Balonun çevresinde dolan ilerleme halkası */
    private class Halka(c: Context) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val iz = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = Color.argb(70, 20, 20, 20)
        }
        private val dolu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = Color.rgb(232, 50, 31); strokeCap = Paint.Cap.ROUND
        }
        private val kutu = RectF()

        var ilerleme = 0f
            set(v) {
                val yeni = v.coerceIn(0f, 1f)
                if (yeni == field) return
                // Yumuşak geçiş
                ValueAnimator.ofFloat(field, yeni).apply {
                    duration = 180
                    addUpdateListener { gosterilen = it.animatedValue as Float; invalidate() }
                    start()
                }
                field = yeni
            }
        private var gosterilen = 0f

        override fun onDraw(canvas: Canvas) {
            val p = dolu.strokeWidth / 2 + 1
            kutu.set(p, p, width - p, height - p)
            canvas.drawArc(kutu, 0f, 360f, false, iz)
            canvas.drawArc(kutu, -90f, 360f * gosterilen.coerceAtLeast(0.03f), false, dolu)
        }
    }
}
