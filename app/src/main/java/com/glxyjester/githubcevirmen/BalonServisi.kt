package com.glxyjester.githubcevirmen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * GitHub uygulaması açıkken sağ kenarda bir balon gösterir. Balona dokununca o an açık olan
 * reponun README'si, kullanıcının dilinde bir panelde açılır ([RepoActivity]).
 *
 * Hangi repoda olunduğu önce ekrandaki yazılardan (erişilebilirlik ağacı), olmazsa dokunulduğu anda
 * ekranın üst kısmının görüntüsünden (OCR) bulunur.
 */
class BalonServisi : AccessibilityService() {

    companion object {
        const val GITHUB = "com.github.android"
    }

    private val ana = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var balon: TextView? = null
    private var balonParams: WindowManager.LayoutParams? = null
    private var githubOnde = false

    /** Son bulunan repo ve bulunma şekli (teşhis için) */
    private var sonRepo: TespitSonucu? = null
    private var sonYontem = ""
    private var sonYaziSayisi = 0

    private val okuyucu by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val yurutucu = Executors.newSingleThreadExecutor()

    private var planZamani = 0L
    private val taramaGorevi = Runnable { planZamani = 0L; agactanBul() }
    private val onDurumGorevi = Runnable { onDurumu() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        onDurumu()
    }

    override fun onAccessibilityEvent(olay: AccessibilityEvent?) {
        olay ?: return
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

    override fun onDestroy() {
        ana.removeCallbacksAndMessages(null)
        balonuKaldir()
        yurutucu.shutdownNow()
        super.onDestroy()
    }

    // ---------------- GitHub önde mi? ----------------

    private fun aktifPaket(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun githubKokleri(): List<AccessibilityNodeInfo> {
        val liste = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val k = w.root ?: continue
                if (k.packageName?.toString() == GITHUB) liste.add(k)
            }
        } catch (_: Exception) {}
        if (liste.isEmpty()) {
            try { rootInActiveWindow?.takeIf { it.packageName?.toString() == GITHUB }?.let { liste.add(it) } } catch (_: Exception) {}
        }
        return liste
    }

    private fun onDurumu() {
        val p = aktifPaket()
        when {
            p == packageName -> balon?.visibility = View.GONE // kendi panelimiz açık
            p == GITHUB || (p == null && githubKokleri().isNotEmpty()) -> {
                githubOnde = true
                if (balon == null) balonEkle()
                balon?.visibility = View.VISIBLE
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

    private fun agactanBul() {
        if (!githubOnde) return
        val yazilar = ArrayList<EkranYazisi>()
        for (k in githubKokleri()) topla(k, yazilar, 0)
        sonYaziSayisi = yazilar.size
        val dm = resources.displayMetrics
        val t = RepoTespit.bul(yazilar, dm.heightPixels, dm.density)
        sonRepo = t
        sonYontem = if (t != null) "ekran yazısı" else ""
        balonuGuncelle()
    }

    private fun topla(d: AccessibilityNodeInfo, liste: MutableList<EkranYazisi>, derinlik: Int) {
        if (derinlik > 60 || liste.size > 800) return
        val r = Rect()
        d.getBoundsInScreen(r)
        listOfNotNull(d.text?.toString(), d.contentDescription?.toString()).forEach { m ->
            if (m.isNotBlank() && m.length < 300 && !r.isEmpty) liste.add(EkranYazisi(m.trim(), r.left, r.top, r.right, r.bottom))
        }
        for (i in 0 until d.childCount) d.getChild(i)?.let { topla(it, liste, derinlik + 1) }
    }

    // ---------------- Repo bulma: ekran görüntüsü (OCR) ----------------

    private fun goruntudenBul(sonuc: (TespitSonucu?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { sonuc(null); return }
        goruntuAl(sonuc)
    }

    @TargetApi(30)
    private fun goruntuAl(sonuc: (TespitSonucu?) -> Unit) {
        balon?.visibility = View.INVISIBLE
        ana.postDelayed({
            try {
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
                        } catch (_: Exception) { null }
                        ana.post {
                            balon?.visibility = View.VISIBLE
                            if (bmp == null) { sonuc(null); return@post }
                            okuyucu.process(InputImage.fromBitmap(bmp, 0))
                                .addOnSuccessListener { yazi ->
                                    val yazilar = yazi.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                                        val k = l.boundingBox ?: return@mapNotNull null
                                        EkranYazisi(l.text, k.left, k.top, k.right, k.bottom)
                                    }
                                    val dm = resources.displayMetrics
                                    sonuc(RepoTespit.bul(yazilar, dm.heightPixels, dm.density))
                                }
                                .addOnFailureListener { sonuc(null) }
                                .addOnCompleteListener { bmp.recycle() }
                        }
                    }

                    override fun onFailure(kod: Int) {
                        ana.post { balon?.visibility = View.VISIBLE; sonuc(null) }
                    }
                })
            } catch (_: Exception) {
                balon?.visibility = View.VISIBLE
                sonuc(null)
            }
        }, 80)
    }

    // ---------------- Balona dokunulunca ----------------

    private fun balonaDokunuldu() {
        agactanBul()
        val t = sonRepo
        if (t != null && t.kesik == TespitSonucu.Kesik.YOK) { panelAc(t.sahip, t.ad); return }
        // Ağaçta yoksa ya da ad kesikse: ekrandan oku
        balon?.text = "…"
        goruntudenBul { g ->
            val bulunan = g ?: t
            if (bulunan == null) {
                balonuGuncelle()
                Toast.makeText(this, R.string.bubble_no_repo, Toast.LENGTH_LONG).show()
                return@goruntudenBul
            }
            if (g != null) { sonRepo = g; sonYontem = "ekran görüntüsü" }
            if (bulunan.kesik == TespitSonucu.Kesik.YOK) { balonuGuncelle(); panelAc(bulunan.sahip, bulunan.ad); return@goruntudenBul }
            GitHubApi.repoAdlari(bulunan.sahip) { adlar ->
                balonuGuncelle()
                val tam = RepoTespit.kesikAdiEslestir(bulunan.ad, bulunan.kesik, adlar) ?: bulunan.ad
                panelAc(bulunan.sahip, tam)
            }
        }
    }

    private fun panelAc(sahip: String, ad: String) {
        startActivity(
            Intent(this, RepoActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, "https://github.com/$sahip/$ad")
                .putExtra(RepoActivity.MOD, RepoActivity.MOD_README)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun teshis() {
        val r = sonRepo
        val m = if (r != null) getString(R.string.bubble_detected, "${r.sahip}/${r.ad}", sonYontem)
        else getString(R.string.bubble_not_detected, sonYaziSayisi)
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    // ---------------- Balon ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun balonEkle() {
        val d = resources.displayMetrics.density
        val boyut = (52 * d).toInt()
        val t = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            elevation = 8 * d
            contentDescription = getString(R.string.bubble_desc)
        }
        val p = WindowManager.LayoutParams(
            boyut, boyut,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * d).toInt()
            y = getSharedPreferences("balon", MODE_PRIVATE).getInt("y", (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        var basY = 0f; var ilkY = 0; var surukle = false; var basZaman = 0L
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { basY = e.rawY; ilkY = p.y; surukle = false; basZaman = SystemClock.uptimeMillis(); t.alpha = 0.7f; true }
                MotionEvent.ACTION_MOVE -> {
                    val fark = e.rawY - basY
                    if (abs(fark) > 12 * d) surukle = true
                    if (surukle) {
                        p.y = (ilkY + fark).toInt().coerceIn(0, resources.displayMetrics.heightPixels - boyut)
                        try { wm.updateViewLayout(t, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    t.alpha = 1f
                    when {
                        surukle -> getSharedPreferences("balon", MODE_PRIVATE).edit().putInt("y", p.y).apply()
                        SystemClock.uptimeMillis() - basZaman > 700 -> teshis()
                        else -> balonaDokunuldu()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { t.alpha = 1f; true }
                else -> false
            }
        }
        try {
            wm.addView(t, p)
            balon = t; balonParams = p
            balonuGuncelle()
        } catch (_: Exception) {}
    }

    private fun balonuGuncelle() {
        val t = balon ?: return
        val bulundu = sonRepo != null
        t.text = "📖"
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (bulundu) Color.rgb(31, 111, 235) else Color.rgb(87, 96, 106))
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(110, 255, 255, 255))
        }
    }

    private fun balonuKaldir() {
        balon?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        balon = null
        sonRepo = null
    }
}
