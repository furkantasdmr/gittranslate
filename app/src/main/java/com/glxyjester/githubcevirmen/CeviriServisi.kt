package com.glxyjester.githubcevirmen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
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
    private val okuyucu by lazy { EkranOkuyucu(this) }

    // Ekrandan okuma (OCR) durumu
    private var ocrBloklar: List<EkranOkuyucu.Blok> = emptyList()
    private var ocrMesgul = false
    private var ocrKirli = false
    private var ocrPlan = 0L
    private var sonOcr = 0L
    /** Kaydırma/ekran değişince artar; eski görüntüden gelen sonuçlar atılır */
    private var ocrNesil = 0
    private val ocrGorevi = Runnable { ocrCalistir() }

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
                if (paket != packageName) { katman?.temizle(); ocrSifirla() }
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
                ocrSifirla()
                taramaPlanla(350)
                ocrIste(450)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!githubOnde || paket !in Ayarlar.HEDEF_UYGULAMALAR) return
                taramaPlanla(450)
                ocrIste(600)
            }
        }
    }

    /** Öndeki uygulama GitHub mı? Değilse katmanı ve balonu kaldır. */
    private fun onDurumunuKontrolEt() {
        val paket = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (paket == packageName) return // kendi balonumuz, durum değişmedi
        val onde = (paket != null && paket in Ayarlar.HEDEF_UYGULAMALAR) ||
            (paket == null && githubKokleri().isNotEmpty())
        if (onde != githubOnde) {
            githubOnde = onde
            if (onde) pencereleriEkle() else pencereleriKaldir()
        }
        if (onde) { taramaPlanla(200); ocrIste(400) }
    }

    // ---------------- Ekrandan okuma (OCR) ----------------

    private fun ocrSifirla() {
        ocrNesil++
        ocrBloklar = emptyList()
    }

    /** OCR iste. Daha erken planlanmış biri varsa onu bozmaz; iki OCR arası en az 900 ms. */
    private fun ocrIste(gecikme: Long) {
        if (!okuyucu.destekleniyor) return
        ocrKirli = true
        val zaman = maxOf(SystemClock.uptimeMillis() + gecikme, sonOcr + 900)
        if (ocrPlan != 0L && ocrPlan <= zaman) return
        ana.removeCallbacks(ocrGorevi)
        ocrPlan = zaman
        ana.postAtTime(ocrGorevi, zaman)
    }

    private fun ocrCalistir() {
        ocrPlan = 0L
        if (!githubOnde || !acik || katman == null) return
        if (ocrMesgul) return // bitince kirliyse tekrar çalışır
        ocrKirli = false
        ocrMesgul = true
        sonOcr = SystemClock.uptimeMillis()
        val nesil = ocrNesil
        okuyucu.oku(githubPenceresi(), gizle = { g ->
            katman?.visibility = if (g) View.INVISIBLE else View.VISIBLE
        }) { bloklar ->
            ocrMesgul = false
            if (nesil != ocrNesil) {
                ocrKirli = true // arada kaydırıldı, bu görüntü eski
            } else if (bloklar != null) {
                ocrBloklar = bloklar
                taramaPlanla(0)
            }
            if (ocrKirli) ocrIste(300)
        }
    }

    private fun githubPenceresi(): AccessibilityWindowInfo? = try {
        windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() in Ayarlar.HEDEF_UYGULAMALAR
        }
    } catch (_: Exception) { null }

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
    private var sonKokSayisi = 0
    private val adaylar = ArrayList<String>()
    private val agacAlanlari = ArrayList<Rect>()
    private var sonOcrAday = 0

    private fun adayEkle(metin: String, alan: Rect, ogeler: MutableList<CeviriKatmani.Oge>) {
        sonAdaySayisi++
        if (ornekYazi.isEmpty()) ornekYazi = metin.take(40)
        val girdi = if (metin.length > 1500) metin.take(1500) else metin
        if (adaylar.size < 6) adaylar.add(girdi)
        val ceviri = motor.onbellektenAl(girdi, hedef)
        when {
            ceviri == null -> motor.cevir(girdi, hedef) { ana.post { taramaPlanla(120) } }
            ceviri.isNotEmpty() -> ogeler.add(CeviriKatmani.Oge(alan, ceviri))
        }
    }

    /** GitHub'a ait tüm pencerelerin kökleri; olmazsa aktif pencere. */
    private fun githubKokleri(): List<AccessibilityNodeInfo> {
        val liste = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val k = w.root ?: continue
                if (k.packageName?.toString() in Ayarlar.HEDEF_UYGULAMALAR) liste.add(k)
            }
        } catch (_: Exception) {}
        if (liste.isEmpty()) {
            val k = try { rootInActiveWindow } catch (_: Exception) { null }
            if (k != null && k.packageName?.toString() in Ayarlar.HEDEF_UYGULAMALAR) liste.add(k)
        }
        return liste
    }

    private fun tara() {
        planlananZaman = 0L
        val k = katman ?: return
        if (!githubOnde || !acik) { k.temizle(); return }
        val kokler = githubKokleri()
        sonKokSayisi = kokler.size
        if (kokler.isEmpty() && ocrBloklar.isEmpty()) return

        val ekran = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val ogeler = ArrayList<CeviriKatmani.Oge>()
        val sayac = intArrayOf(0)
        sonYazi = 0; sonAdaySayisi = 0; ornekYazi = ""; adaylar.clear(); agacAlanlari.clear()
        for (kok in kokler) dugumuGez(kok, ekran, ogeler, sayac, 0)

        // Ekrandan okunan yazılar: ağaçta zaten olan yerleri tekrar çevirme
        sonOcrAday = 0
        for (b in ocrBloklar) {
            if (agacAlanlari.any { Rect.intersects(it, b.alan) }) continue
            if (!cevrilmeliMi(b.metin)) continue
            sonOcrAday++
            adayEkle(b.metin, b.alan, ogeler)
        }
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

        var metin = d.text?.toString()?.trim().orEmpty()
        if (metin.isEmpty()) {
            val aciklama = d.contentDescription?.toString()?.trim().orEmpty()
            if (aciklama.length >= 8 && aciklama.contains(' ')) metin = aciklama
        }
        if (metin.isEmpty()) return false
        sonYazi++
        if (d.isEditable) return true // yazı kutularına dokunma
        if (!d.isVisibleToUser) return true

        val alan = Rect()
        d.getBoundsInScreen(alan)
        if (alan.isEmpty || !alan.intersect(ekran)) return true
        agacAlanlari.add(Rect(alan))

        if (!cevrilmeliMi(metin)) return true
        adayEkle(metin, alan, ogeler)
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
        ana.removeCallbacks(ocrGorevi)
        ocrPlan = 0L
        ocrSifirla()
        katman?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        balon?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
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

    private var panel: TextView? = null
    private var testSonucu = "deneniyor…"

    private fun teshisGoster() {
        tara()
        ocrIste(0)
        testSonucu = "deneniyor…"
        panelYaz()
        motor.testEt("这是一个用于测试的句子。", hedef) { s -> testSonucu = s; ana.post { panelYaz() } }
    }

    private fun panelYaz() {
        val aktif = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val pencereler = try { windows.mapNotNull { it.root?.packageName?.toString() }.distinct().joinToString() } catch (e: Exception) { "hata" }
        val m = buildString {
            appendLine("GitHub Çevirmen · teşhis (kapatmak için dokun)")
            appendLine()
            appendLine("Aktif pencere: $aktif")
            appendLine("Pencereler: $pencereler")
            if (!acik) appendLine("⚠️ Çeviri KAPALI (balonda Aa). Açmak için balona bir kez dokun.")
            appendLine("Android API: ${Build.VERSION.SDK_INT} · GitHub kökü: $sonKokSayisi · hedef: $hedef")
            appendLine("Düğüm: $sonDugum · yazı: $sonYazi · aday: $sonAdaySayisi · çizilen: $sonCevrilen")
            appendLine("Ekrandan okuma: ${okuyucu.calismaSayisi} kez · blok: ${okuyucu.sonBlokSayisi} · aday: $sonOcrAday · ${okuyucu.sonSure} ms")
            if (okuyucu.sonHata.isNotEmpty()) appendLine("Okuma hatası: ${okuyucu.sonHata}")
            appendLine("Bekleyen: ${motor.bekleyenSayisi} · indirme: ${motor.indirmeSayisi}")
            if (motor.paketHatasi.isNotEmpty()) appendLine("Dil paketi: ${motor.paketHatasi} (internet yedeği kullanılıyor)")
            appendLine("İnternetten çevrilen: ${motor.cevrimiciSayisi}")
            if (motor.sonHata.isNotEmpty()) appendLine("Son hata: ${motor.sonHata}")
            appendLine()
            appendLine("Test (Çince→$hedef): $testSonucu")
            appendLine()
            appendLine("İlk adaylar:")
            if (adaylar.isEmpty()) appendLine("  (yok)")
            adaylar.forEach { appendLine("• ${it.take(35).replace('\n', ' ')}\n    ${motor.durum(it, hedef)}") }
        }
        val p = panel ?: TextView(this).also { t ->
            val d = resources.displayMetrics.density
            t.setTextColor(Color.WHITE)
            t.textSize = 12f
            t.setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            t.background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.argb(235, 13, 17, 23)) }
            t.setOnClickListener { try { wm.removeView(t) } catch (_: Exception) {}; panel = null }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP; y = (90 * d).toInt(); horizontalMargin = 0.03f }
            try { wm.addView(t, lp); panel = t } catch (_: Exception) {}
        }
        p.text = m.trim()
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
                if (acik) { taramaPlanla(50); ocrIste(100) } else katman?.temizle()
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
        okuyucu.kapat()
        super.onDestroy()
    }
}
