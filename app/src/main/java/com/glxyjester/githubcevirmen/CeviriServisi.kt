package com.glxyjester.githubcevirmen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

/**
 * GitHub uygulaması öndeyken ekrandaki yazıları okur, yabancı olanları çevirip
 * [CeviriKatmani] ile tam üstlerine yazar. Sağ kenardaki küçük balon çeviriyi açıp kapatır.
 */
class CeviriServisi : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val ana = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val motor = CeviriMotoru()

    private var katman: CeviriKatmani? = null
    private var balon: TextView? = null
    private var balonParams: WindowManager.LayoutParams? = null

    private var githubOnde = false
    private var acik = true
    private var hedef = "tr"

    private val taramaGorevi = Runnable { tara() }
    private val onDurumGorevi = Runnable { onDurumunuKontrolEt() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        acik = Ayarlar.acikMi(this)
        hedef = Ayarlar.hedefDil(this)
        Ayarlar.prefs(this).registerOnSharedPreferenceChangeListener(this)
        motor.indirmeDurumuDegisti = { ana.post { balonuGuncelle() } }
        onDurumunuKontrolEt()
    }

    override fun onAccessibilityEvent(olay: AccessibilityEvent?) {
        olay ?: return
        val paket = olay.packageName?.toString()
        when (olay.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // GitHub içinde yeni ekrana geçildi: eski çeviriler yanlış yerde kalmasın
                if (paket != packageName) katman?.temizle()
                ana.removeCallbacks(onDurumGorevi)
                ana.postDelayed(onDurumGorevi, 150)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                ana.removeCallbacks(onDurumGorevi)
                ana.postDelayed(onDurumGorevi, 150)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (!githubOnde || paket !in Ayarlar.HEDEF_UYGULAMALAR) return
                // Kayarken eski kutular yanlış yerde kalmasın: gizle, durunca yeniden çiz
                katman?.temizle()
                taramaPlanla(350)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!githubOnde || paket !in Ayarlar.HEDEF_UYGULAMALAR) return
                taramaPlanla(450)
            }
        }
    }

    /** Öndeki uygulama GitHub mı? Değilse katmanı ve balonu kaldır. */
    private fun onDurumunuKontrolEt() {
        val paket = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (paket == packageName) return // kendi balonumuz, durum değişmedi
        val onde = paket != null && paket in Ayarlar.HEDEF_UYGULAMALAR
        if (onde != githubOnde) {
            githubOnde = onde
            if (onde) pencereleriEkle() else pencereleriKaldir()
        }
        if (onde) taramaPlanla(200)
    }

    /** Zaten daha erken bir tarama planlıysa onu bozma; böylece sürekli güncellenen ekranda tarama hiç gecikmez. */
    private var planlananZaman = 0L

    private fun taramaPlanla(gecikme: Long) {
        val zaman = SystemClock.uptimeMillis() + gecikme
        if (planlananZaman != 0L && planlananZaman <= zaman) return
        ana.removeCallbacks(taramaGorevi)
        planlananZaman = zaman
        ana.postAtTime(taramaGorevi, zaman)
    }

    // ---------------- Ekran taraması ----------------

    // Teşhis: balona basılı tutunca gösterilir
    private var sonDugum = 0
    private var sonYazi = 0
    private var sonCevrilen = 0
    private var sonAdaySayisi = 0
    private var sonTaramaZamani = 0L
    private var ornekYazi = ""

    private fun tara() {
        planlananZaman = 0L
        val k = katman ?: return
        if (!githubOnde || !acik) { k.temizle(); return }
        val kok = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (kok.packageName?.toString() !in Ayarlar.HEDEF_UYGULAMALAR) return

        val ekran = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val ogeler = ArrayList<CeviriKatmani.Oge>()
        val sayac = intArrayOf(0)
        sonYazi = 0; sonAdaySayisi = 0; ornekYazi = ""
        dugumuGez(kok, ekran, ogeler, sayac, 0)
        sonDugum = sayac[0]
        sonCevrilen = ogeler.size
        sonTaramaZamani = System.currentTimeMillis()
        k.goster(ogeler)
    }

    /** @return bu düğüm ya da altındakilerden biri yazı içeriyorsa true */
    private fun dugumuGez(
        d: AccessibilityNodeInfo,
        ekran: Rect,
        ogeler: MutableList<CeviriKatmani.Oge>,
        sayac: IntArray,
        derinlik: Int,
    ): Boolean {
        if (derinlik > 100 || sayac[0] > 2500) return false
        sayac[0]++

        // Önce çocuklar: en içteki yazıyı tercih et ki aynı metin iki kez çizilmesin
        var altindaYaziVar = false
        for (i in 0 until d.childCount) {
            val c = d.getChild(i) ?: continue
            if (dugumuGez(c, ekran, ogeler, sayac, derinlik + 1)) altindaYaziVar = true
        }
        if (altindaYaziVar) return true

        val metin = d.text?.toString()?.trim().orEmpty()
        if (metin.isEmpty()) return false
        sonYazi++
        if (d.isEditable) return true // yazı kutularına dokunma
        if (!d.isVisibleToUser) return true

        val alan = Rect()
        d.getBoundsInScreen(alan)
        if (alan.isEmpty || !alan.intersect(ekran)) return true

        if (!cevrilmeliMi(metin)) return true
        sonAdaySayisi++
        if (ornekYazi.isEmpty()) ornekYazi = metin.take(40)
        val girdi = if (metin.length > 1500) metin.take(1500) else metin

        val ceviri = motor.onbellektenAl(girdi, hedef)
        when {
            ceviri == null -> motor.cevir(girdi, hedef) { ana.post { taramaPlanla(120) } }
            ceviri.isNotEmpty() -> ogeler.add(CeviriKatmani.Oge(alan, ceviri))
        }
        return true
    }

    /** Kod, dosya yolu, sayı gibi çevrilmeye değmeyecek yazıları ele. */
    private fun cevrilmeliMi(m: String): Boolean {
        val cjk = m.any { Character.UnicodeScript.of(it.code).let { s ->
            s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA ||
                s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL ||
                s == Character.UnicodeScript.CYRILLIC || s == Character.UnicodeScript.ARABIC
        } }
        if (cjk) return true
        val harf = m.count { it.isLetter() }
        if (harf < 3) return false
        // Boşluksuz ve / . _ içeren şeyler genelde dosya adı, paket adı, link
        if (!m.contains(' ') && (m.contains('/') || m.contains('.') || m.contains('_') || m.contains('@'))) return false
        // Tek kelimelik kısa Latin yazılarda dil tanıma güvenilmez
        if (!m.contains(' ') && m.length < 6) return false
        return true
    }

    // ---------------- Pencereler ----------------

    private fun pencereleriEkle() {
        if (katman == null) {
            val v = CeviriKatmani(this)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try { wm.addView(v, p); katman = v } catch (_: Exception) {}
        }
        if (balon == null) balonEkle()
    }

    private fun pencereleriKaldir() {
        ana.removeCallbacks(taramaGorevi)
        katman?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        balon?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        katman = null
        balon = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun balonEkle() {
        val d = resources.displayMetrics.density
        val boyut = (44 * d).toInt()
        val t = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            elevation = 6 * d
            contentDescription = "Çeviriyi aç veya kapat"
        }
        val p = WindowManager.LayoutParams(
            boyut, boyut,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (6 * d).toInt()
            y = Ayarlar.prefs(this@CeviriServisi).getInt(Ayarlar.BALON_Y, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }

        // Dokun: aç/kapat. Basılı tutup sürükle: yukarı/aşağı taşı.
        var baslangicY = 0f
        var ilkY = 0
        var surukleniyor = false
        var basmaZamani = 0L
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    baslangicY = e.rawY; ilkY = p.y; surukleniyor = false
                    basmaZamani = SystemClock.uptimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val fark = e.rawY - baslangicY
                    if (abs(fark) > 12 * d) surukleniyor = true
                    if (surukleniyor) {
                        p.y = (ilkY + fark).toInt().coerceIn(0, resources.displayMetrics.heightPixels - boyut)
                        try { wm.updateViewLayout(t, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (surukleniyor) {
                        Ayarlar.prefs(this).edit().putInt(Ayarlar.BALON_Y, p.y).apply()
                    } else if (SystemClock.uptimeMillis() - basmaZamani > 600) {
                        teshisGoster()
                    } else {
                        Ayarlar.prefs(this).edit().putBoolean(Ayarlar.ACIK, !acik).apply()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(t, p)
            balon = t
            balonParams = p
            balonuGuncelle()
        } catch (_: Exception) {}
    }

    private fun teshisGoster() {
        tara()
        val ms = if (sonTaramaZamani == 0L) "hiç" else "${(System.currentTimeMillis() - sonTaramaZamani) / 1000} sn önce"
        val m = buildString {
            appendLine("Son tarama: $ms")
            appendLine("Düğüm: $sonDugum · yazı: $sonYazi · aday: $sonAdaySayisi · çizilen: $sonCevrilen")
            appendLine("Bekleyen çeviri: ${motor.bekleyenSayisi} · indirme: ${motor.indirmeSayisi}")
            if (ornekYazi.isNotEmpty()) appendLine("Örnek: $ornekYazi")
            if (motor.sonHata.isNotEmpty()) append("Hata: ${motor.sonHata}")
        }
        Toast.makeText(this, m.trim(), Toast.LENGTH_LONG).show()
    }

    private fun balonuGuncelle() {
        val t = balon ?: return
        val renk = if (acik) Color.rgb(31, 111, 235) else Color.rgb(110, 118, 129)
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(renk)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(90, 255, 255, 255))
        }
        t.alpha = if (acik) 0.95f else 0.7f
        t.text = when {
            motor.indirmeSayisi > 0 -> "⏳"
            motor.sonHata.isNotEmpty() -> "!"
            acik -> hedef.uppercase()
            else -> "Aa"
        }
    }

    // ---------------- Ayar değişiklikleri ----------------

    override fun onSharedPreferenceChanged(p: SharedPreferences?, anahtar: String?) {
        when (anahtar) {
            Ayarlar.ACIK -> {
                acik = Ayarlar.acikMi(this)
                balonuGuncelle()
                if (acik) taramaPlanla(50) else katman?.temizle()
            }
            Ayarlar.HEDEF_DIL -> {
                hedef = Ayarlar.hedefDil(this)
                katman?.temizle()
                balonuGuncelle()
                taramaPlanla(50)
            }
        }
    }

    override fun onInterrupt() {
        katman?.temizle()
    }

    override fun onDestroy() {
        Ayarlar.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        ana.removeCallbacksAndMessages(null)
        pencereleriKaldir()
        motor.kapat()
        super.onDestroy()
    }
}
