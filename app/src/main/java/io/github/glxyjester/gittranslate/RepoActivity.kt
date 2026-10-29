package io.github.glxyjester.gittranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * GitHub uygulamasının "Paylaş" menüsünden açılır. GitHub'ın üstünde, alttan açılan bir panelde
 * repoyu sade bölümlerle ve kullanıcının dilinde gösterir.
 */
class RepoActivity : Activity() {

    companion object {
        const val MOD = "mod"
        /** Balondan açılınca: sadece README, çevrilmiş */
        const val MOD_README = "readme"
    }

    private var readmeModu = false

    private lateinit var ui: Arayuz
    private lateinit var panel: LinearLayout
    private lateinit var icerik: LinearLayout
    private lateinit var baslikYazisi: TextView
    private lateinit var dilDugmesi: TextView
    private lateinit var kaydirma: ScrollView

    private var hedef = "en"
    private var sahip = ""
    private var ad = ""
    private var bilgi: RepoBilgisi? = null
    private var orijinalGoster = false
    private var kuyruk = CeviriKuyrugu()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Arayuz(this)
        GitHubApi.baslat(this)
        hedef = Ayarlar.hedefDil(this)
        kabukKur()
        niyetiIsle(intent)
    }

    override fun onConfigurationChanged(yeni: android.content.res.Configuration) {
        super.onConfigurationChanged(yeni)
        // Döndürünce sayfa baştan yüklenmesin; sadece panel yüksekliği güncellensin
        panel.layoutParams = panel.layoutParams.apply { height = (resources.displayMetrics.heightPixels * 0.92f).toInt() }
    }

    override fun onNewIntent(yeni: Intent) {
        super.onNewIntent(yeni)
        setIntent(yeni)
        niyetiIsle(yeni)
    }

    // ---------------- Kabuk: karartma + alttan panel ----------------

    private fun kabukKur() {
        val kok = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }
        }
        val yukseklik = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // dokunma karartmaya geçmesin
            background = GradientDrawable().apply {
                val r = ui.dpf(18)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(ui.zemin)
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, yukseklik, Gravity.BOTTOM)
        }

        // Tutamaç
        panel.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = ui.dpf(3); setColor(ui.cizgi) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8)
            }
        })

        // Üst satır: başlık, dil, kapat
        val ustSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        baslikYazisi = ui.yaziGorunumu("", 17f, kalin = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        }
        dilDugmesi = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ui.vurgu)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            ui.dokunulabilir(this, 14, kenar = true)
            setOnClickListener { dilSec() }
        }
        val kapat = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(ui.sonuk)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.close)
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { leftMargin = ui.dp(4) }
            ui.dokunulabilir(this, 22)
            setOnClickListener { finish() }
        }
        ustSatir.addView(baslikYazisi)
        ustSatir.addView(dilDugmesi)
        ustSatir.addView(kapat)
        panel.addView(ustSatir)
        panel.addView(View(this).apply {
            setBackgroundColor(ui.cizgi)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1))
        })

        icerik = ui.kolon(16).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)) }
        kaydirma = ScrollView(this).apply {
            addView(icerik)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        panel.addView(kaydirma)
        ui.cubukPayi(icerik, ustPay = false, altPay = true)

        kok.addView(panel)
        setContentView(kok)
        dilDugmesiniGuncelle()

        panel.translationY = yukseklik.toFloat()
        panel.animate().translationY(0f).setDuration(260).start()
    }

    private fun dilDugmesiniGuncelle() {
        dilDugmesi.text = "🌐 " + hedef.uppercase(Locale.ROOT)
    }

    private fun dilSec() {
        val diller = Ayarlar.DILLER
        AlertDialog.Builder(this)
            .setTitle(R.string.main_lang)
            .setItems(diller.map { it.second }.toTypedArray()) { _, i ->
                hedef = diller[i].first
                Ayarlar.hedefDilKaydet(this, hedef)
                dilDugmesiniGuncelle()
                bilgi?.let { sayfaYenile(it) }
            }
            .show()
    }

    // ---------------- Veri ----------------

    private fun niyetiIsle(n: Intent) {
        readmeModu = n.getStringExtra(MOD) == MOD_README
        val metin = when (n.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                n.getStringExtra(Intent.EXTRA_TEXT), n.getStringExtra(Intent.EXTRA_SUBJECT),
            ).joinToString(" ")
            Intent.ACTION_VIEW -> n.dataString
            else -> n.getStringExtra(Intent.EXTRA_TEXT)
        }
        when (val s = RepoAnaliz.baglantiAyikla(metin)) {
            is RepoAnaliz.BaglantiSonucu.Repo -> { sahip = s.sahip; ad = s.ad; duzeltmeDenendi = false; yukle() }
            is RepoAnaliz.BaglantiSonucu.KisiSayfasi -> {
                baslikYazisi.text = s.sahip
                mesajGoster(getString(R.string.err_user_page, s.sahip), tekrar = false)
            }
            RepoAnaliz.BaglantiSonucu.Yok -> mesajGoster(getString(R.string.err_no_repo), tekrar = false)
        }
    }

    /** Ad yanlış okunduysa bir kez sahibin repo listesinden düzeltmeyi dene */
    private var duzeltmeDenendi = false

    private fun bulunamadi() {
        if (duzeltmeDenendi) { mesajGoster(getString(R.string.err_not_found), tekrar = true); return }
        duzeltmeDenendi = true
        GitHubApi.repoAdlari(sahip) { adlar ->
            if (isFinishing || isDestroyed) return@repoAdlari
            val dogru = RepoTespit.enYakin(ad, adlar)
            if (dogru != null && dogru != ad) { ad = dogru; yukle() }
            else mesajGoster(getString(R.string.err_not_found), tekrar = true)
        }
    }

    private fun yukle() {
        baslikYazisi.text = "$sahip / $ad"
        icerik.removeAllViews()
        icerik.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(60), 0, 0)
            addView(ProgressBar(this@RepoActivity))
            addView(ui.yaziGorunumu(getString(R.string.loading), 15f, renk = ui.sonuk, ust = 12).apply { gravity = Gravity.CENTER })
        })
        GitHubApi.getir(sahip, ad, tam = !readmeModu) { b, h ->
            if (isFinishing || isDestroyed) return@getir
            when {
                b != null -> { bilgi = b; sayfaYenile(b) }
                h is ApiHatasi.Bulunamadi -> bulunamadi()
                h is ApiHatasi.Sinir -> mesajGoster(getString(R.string.err_limit, h.dakika), tekrar = true)
                h is ApiHatasi.Ag -> mesajGoster(getString(R.string.err_network, h.mesaj), tekrar = true)
            }
        }
    }

    private fun mesajGoster(m: String, tekrar: Boolean) {
        icerik.removeAllViews()
        icerik.addView(ui.yaziGorunumu(m, 16f, ust = 40).apply { gravity = Gravity.CENTER })
        if (tekrar) icerik.addView(ui.dugme(getString(R.string.retry), ust = 20) { yukle() })
        icerik.addView(ui.dugme(getString(R.string.close), ana = false) { finish() })
    }

    // ---------------- Sayfa ----------------

    private fun sayfaYenile(b: RepoBilgisi) = if (readmeModu) readmeSayfasiKur(b) else sayfaKur(b)

    /** Balondan açılan sade sayfa: kısa açıklama + README'nin tamamı, kullanıcının dilinde. */
    private fun readmeSayfasiKur(b: RepoBilgisi) {
        kuyruk = CeviriKuyrugu()
        orijinalGoster = false
        baslikYazisi.text = "${b.sahip} / ${b.ad}"
        icerik.removeAllViews()

        val ust = ui.kart(ust = 14)
        ust.addView(ui.kartBasligi("ℹ️ " + getString(R.string.sec_about)))
        b.aciklama?.takeIf { it.isNotBlank() }?.let { ust.addView(cevrilen(it, 15.5f, kalin = true)) }
            ?: ust.addView(ui.yaziGorunumu(getString(R.string.no_description), 14f, renk = ui.sonuk))
        val bilgiler = ArrayList<String>()
        bilgiler += "★ " + yildizYaz(b.yildiz)
        b.anaDil?.let { bilgiler += it }
        b.guncellenme?.let { bilgiler += getString(R.string.updated, onceYaz(it)) }
        ust.addView(ui.yaziGorunumu(bilgiler.joinToString("  ·  "), 13f, renk = ui.sonuk, ust = 6))
        if (b.konular.isNotEmpty()) {
            ust.addView(ui.yaziGorunumu("🏷️ " + b.konular.take(8).joinToString("  ·  "), 13f, renk = ui.vurgu, ust = 6))
        }
        b.anaSayfa?.let { adres ->
            ust.addView(ui.baglantiYazisi("🌐 " + adres.removePrefix("https://").removePrefix("http://")) {
                ui.ac(if (adres.startsWith("http")) adres else "https://$adres")
            })
        }
        if (b.arsiv) ust.addView(ui.yaziGorunumu(getString(R.string.archived), 14f, renk = ui.uyari, ust = 8))
        if (b.sinirli) ust.addView(ui.yaziGorunumu(getString(R.string.limited_note), 13f, renk = ui.uyari, ust = 6))
        icerik.addView(ust)

        val readme = b.readme?.let { Markdown.ayristir(it, b.ad) }
        val k = ui.kart()
        k.addView(ui.kartBasligi("📖 README"))
        if (readme == null || readme.bolumler.isEmpty()) {
            k.addView(ui.yaziGorunumu(getString(R.string.no_readme), 15f, renk = ui.sonuk))
        } else {
            // Uzun README'lerde ilk ~6000 harf açık, gerisi başlığa dokununca açılır (hızlı kalsın)
            var toplam = 0
            readme.bolumler.forEach { bol ->
                val acik = toplam < 6000
                when {
                    bol.tur == BolumTuru.GIRIS -> parcalariCiz(k, bol.parcalar, b)
                    acik -> {
                        k.addView(cevrilen(bol.baslik, 18f, kalin = true, ust = 20))
                        parcalariCiz(k, bol.parcalar, b)
                    }
                    else -> katlanir(k, bol.baslik, false) { govde -> parcalariCiz(govde, bol.parcalar, b) }
                }
                toplam += boyut(bol)
            }
        }
        icerik.addView(k)

        b.surum?.let { surumKarti(it, b) }

        icerik.addView(ui.dugme(getString(R.string.btn_more_info), ana = false, ust = 14) {
            readmeModu = false
            if (b.tam) sayfaKur(b) else yukle()
        })
        altDugmeler(b)
        kaydirma.scrollTo(0, 0)
    }

    private fun sayfaKur(b: RepoBilgisi) {
        kuyruk = CeviriKuyrugu()
        orijinalGoster = false
        baslikYazisi.text = "${b.sahip} / ${b.ad}"
        icerik.removeAllViews()
        val readme = b.readme?.let { Markdown.ayristir(it, b.ad) }

        ozetKarti(b, readme)
        turKarti(b, readme)
        indirmeKarti(b)

        if (readme == null) {
            icerik.addView(ui.kart().apply { addView(ui.yaziGorunumu(getString(R.string.no_readme), 15f, renk = ui.sonuk)) })
        } else {
            val kurulum = readme.bolumler.filter { it.tur == BolumTuru.KURULUM }
            val kullanim = readme.bolumler.filter { it.tur == BolumTuru.KULLANIM }
            val ozellik = readme.bolumler.filter { it.tur == BolumTuru.OZELLIKLER }

            bolumKarti(getString(R.string.sec_install), kurulum, b, acik = true,
                bosMesaj = getString(R.string.no_install_found))
            if (kullanim.isNotEmpty()) bolumKarti(getString(R.string.sec_usage), kullanim, b, acik = true)
            if (ozellik.isNotEmpty()) bolumKarti(getString(R.string.sec_features), ozellik, b,
                acik = ozellik.sumOf { boyut(it) } < 2500)

            val digerleri = readme.bolumler.filter { it.tur == BolumTuru.DIGER || it.tur == BolumTuru.GIRIS }
                .filter { it.tur != BolumTuru.GIRIS || girisinFazlasiVar(it) }
            if (digerleri.isNotEmpty()) bolumKarti(getString(R.string.sec_more), digerleri, b, acik = false, altBasliklarAyri = true)

            val ek = readme.bolumler.filter { it.tur == BolumTuru.EK }
            if (ek.isNotEmpty()) bolumKarti(getString(R.string.sec_extra), ek, b, acik = false, altBasliklarAyri = true)
        }

        dosyaKarti(b)
        altDugmeler(b)
        kaydirma.scrollTo(0, 0)
    }

    private fun boyut(bolum: Bolum): Int = bolum.parcalar.sumOf {
        when (it) {
            is Parca.Paragraf -> it.metin.length
            is Parca.Liste -> it.ogeler.sumOf { o -> o.metin.length }
            is Parca.Kod -> it.kod.length
            is Parca.Alinti -> it.metin.length
            is Parca.Tablo -> it.satirlar.size * 40
            is Parca.Baslik -> it.metin.length
            is Parca.Resim -> 300
        }
    }

    private fun girisinFazlasiVar(g: Bolum) =
        g.parcalar.any { it !is Parca.Paragraf && it !is Parca.Alinti } || g.parcalar.size > 3

    private fun ozetKarti(b: RepoBilgisi, readme: Readme?) {
        val k = ui.kart(ust = 14)
        k.addView(ui.kartBasligi(getString(R.string.sec_what)))

        val aciklama = b.aciklama?.trim().orEmpty()
        val ozet = readme?.ozet().orEmpty()
        if (aciklama.isNotEmpty()) k.addView(cevrilen(aciklama, 16f, kalin = true))
        if (ozet.isNotEmpty() && !benzer(ozet, aciklama)) k.addView(cevrilen(ozet, 15f, ust = 8))
        if (aciklama.isEmpty() && ozet.isEmpty()) k.addView(ui.yaziGorunumu(getString(R.string.no_description), 15f, renk = ui.sonuk))

        val bilgiler = ArrayList<String>()
        bilgiler += "★ " + yildizYaz(b.yildiz)
        b.anaDil?.let { bilgiler += it }
        b.guncellenme?.let { bilgiler += getString(R.string.updated, onceYaz(it)) }
        k.addView(ui.yaziGorunumu(bilgiler.joinToString("  ·  "), 13f, renk = ui.sonuk, ust = 10))

        if (b.arsiv) k.addView(ui.yaziGorunumu(getString(R.string.archived), 14f, renk = ui.uyari, ust = 8))
        if (b.fork) k.addView(ui.yaziGorunumu(getString(R.string.fork), 14f, renk = ui.sonuk, ust = 6))
        b.anaSayfa?.let { adres ->
            k.addView(ui.baglantiYazisi(getString(R.string.homepage, adres.removePrefix("https://").removePrefix("http://"))) {
                ui.ac(if (adres.startsWith("http")) adres else "https://$adres")
            })
        }
        icerik.addView(k)
    }

    private fun turKarti(b: RepoBilgisi, readme: Readme?) {
        val turler = RepoAnaliz.projeTurleri(
            b.kok.orEmpty().map { it.ad }, b.anaDil, b.konular, b.surum?.dosyalar?.map { it.ad }.orEmpty(), b.ad, b.readme,
        )
        val k = ui.kart()
        k.addView(ui.kartBasligi(getString(R.string.sec_type)))
        val telefon = when (RepoAnaliz.telefonUyumu(turler)) {
            TelefonUyumu.KULLANILIR -> R.string.phone_yes
            TelefonUyumu.OKUNUR -> R.string.phone_read
            TelefonUyumu.BILGISAYAR -> R.string.phone_no
        }
        k.addView(ui.yaziGorunumu(getString(telefon), 15f, kalin = true, alt = 4))
        if (turler.isEmpty()) k.addView(ui.yaziGorunumu(getString(R.string.type_unknown), 14f, renk = ui.sonuk, ust = 4))
        turler.forEach { k.addView(ui.yaziGorunumu(getString(turYazisi(it)), 14f, ust = 6)) }

        val spdx = b.lisansSpdx
        val lisansAdi = spdx?.takeIf { it != "NOASSERTION" } ?: b.lisansAdi ?: ""
        val lisans = when (RepoAnaliz.lisansTuru(spdx)) {
            LisansTuru.SERBEST -> getString(R.string.license_free, lisansAdi)
            LisansTuru.PAYLASIMLI -> getString(R.string.license_copyleft, lisansAdi)
            LisansTuru.YOK -> getString(R.string.license_none)
            LisansTuru.DIGER -> getString(R.string.license_other, b.lisansAdi ?: "?")
        }
        k.addView(ui.yaziGorunumu("⚖️ $lisans", 14f, renk = ui.sonuk, ust = 10))
        icerik.addView(k)
    }

    private fun turYazisi(t: ProjeTuru) = when (t) {
        ProjeTuru.ANDROID_UYGULAMA -> R.string.type_android_app
        ProjeTuru.WINDOWS_PROGRAM -> R.string.type_windows
        ProjeTuru.MAC_PROGRAM -> R.string.type_mac
        ProjeTuru.LINUX_PROGRAM -> R.string.type_linux
        ProjeTuru.PYTHON -> R.string.type_python
        ProjeTuru.NODE -> R.string.type_node
        ProjeTuru.ANDROID_KAYNAK -> R.string.type_android_source
        ProjeTuru.RUST -> R.string.type_rust
        ProjeTuru.GO -> R.string.type_go
        ProjeTuru.JAVA -> R.string.type_java
        ProjeTuru.DOTNET -> R.string.type_dotnet
        ProjeTuru.CPP -> R.string.type_cpp
        ProjeTuru.RUBY -> R.string.type_ruby
        ProjeTuru.PHP -> R.string.type_php
        ProjeTuru.FLUTTER -> R.string.type_flutter
        ProjeTuru.SWIFT -> R.string.type_swift
        ProjeTuru.WEB_SITESI -> R.string.type_web
        ProjeTuru.DOCKER -> R.string.type_docker
        ProjeTuru.JUPYTER -> R.string.type_jupyter
        ProjeTuru.LISTE -> R.string.type_list
        ProjeTuru.VERI -> R.string.type_data
    }

    private fun indirmeKarti(b: RepoBilgisi) {
        val s = b.surum ?: return
        if (s.dosyalar.isEmpty()) return
        val k = ui.kart()
        k.addView(ui.kartBasligi(getString(R.string.sec_downloads)))
        k.addView(ui.yaziGorunumu(getString(R.string.latest_release, s.ad), 13f, renk = ui.sonuk))
        indirmeDugmeleri(k, s)
        icerik.addView(k)
    }

    /** Son sürüm: adı, tarihi, çevrilmiş notları ve indirme düğmeleri */
    private fun surumKarti(s: Surum, b: RepoBilgisi) {
        val k = ui.kart()
        k.addView(ui.kartBasligi("🚀 " + getString(R.string.sec_release)))
        val alt = listOfNotNull(s.ad.ifBlank { s.etiket }, s.tarih?.let { onceYaz(it) }).joinToString("  ·  ")
        k.addView(ui.yaziGorunumu(alt, 14f, kalin = true, renk = ui.sonuk))
        val notlar = s.notlar.takeIf { it.isNotBlank() }?.let { Markdown.ayristir(it, null) }
        if (notlar != null && notlar.bolumler.isNotEmpty()) {
            val hepsi = notlar.bolumler.flatMap { bol ->
                (if (bol.tur != BolumTuru.GIRIS) listOf<Parca>(Parca.Baslik(4, bol.baslik)) else emptyList()) + bol.parcalar
            }
            val uzun = notlar.bolumler.sumOf { boyut(it) } > 1500
            if (uzun) katlanir(k, getString(R.string.release_notes), false, baslikCevir = false) { g -> parcalariCiz(g, hepsi, b) }
            else parcalariCiz(k, hepsi, b)
        }
        indirmeDugmeleri(k, s)
        k.addView(ui.baglantiYazisi(getString(R.string.all_releases), ust = 8) {
            ui.ac("https://github.com/${b.sahip}/${b.ad}/releases")
        })
        icerik.addView(k)
    }

    private fun indirmeDugmeleri(k: LinearLayout, s: Surum) {
        fun oncelik(ad: String): Int {
            val a = ad.lowercase()
            return when {
                a.endsWith(".apk") -> 0
                a.endsWith(".exe") || a.endsWith(".msi") -> 1
                a.endsWith(".dmg") || a.endsWith(".pkg") -> 2
                a.endsWith(".appimage") || a.endsWith(".deb") -> 3
                else -> 4
            }
        }
        s.dosyalar.sortedBy { oncelik(it.ad) }.take(6).forEach { d ->
            val ikon = when (oncelik(d.ad)) { 0 -> "📱"; 1 -> "🪟"; 2 -> "🍎"; 3 -> "🐧"; else -> "📦" }
            val mb = if (d.boyut > 0) String.format(Locale.ROOT, "  (%.1f MB)", d.boyut / 1_048_576.0) else ""
            k.addView(ui.dugme("$ikon  ${d.ad}$mb", ana = oncelik(d.ad) == 0, ust = 8) { ui.ac(d.adres) })
        }
        if (s.dosyalar.size > 6) k.addView(ui.baglantiYazisi("+${s.dosyalar.size - 6} …") { ui.ac(s.adres) })
    }

    // ---------------- README bölümleri ----------------

    /**
     * @param altBasliklarAyri true ise her bölüm kendi başlığıyla ayrı ayrı açılıp kapanır
     */
    private fun bolumKarti(
        baslik: String, bolumler: List<Bolum>, b: RepoBilgisi, acik: Boolean,
        bosMesaj: String? = null, altBasliklarAyri: Boolean = false,
    ) {
        val k = ui.kart()
        k.addView(ui.kartBasligi(baslik))
        if (bolumler.isEmpty()) {
            bosMesaj?.let { k.addView(ui.yaziGorunumu(it, 14f, renk = ui.sonuk)) }
            icerik.addView(k); return
        }
        if (altBasliklarAyri) {
            bolumler.forEach { bol ->
                val ad = bol.baslik.ifEmpty { b.ad }
                katlanir(k, ad, acik) { govde -> parcalariCiz(govde, bol.parcalar, b) }
            }
        } else {
            bolumler.forEachIndexed { i, bol ->
                // Bölümün kendi (README'deki) başlığını küçük bir alt başlık olarak göster
                if (bolumler.size > 1 || !benzer(bol.baslik, baslik)) {
                    k.addView(cevrilen(bol.baslik, 15f, kalin = true, renk = ui.sonuk, ust = if (i == 0) 0 else 14))
                }
                if (acik) parcalariCiz(k, bol.parcalar, b)
                else katlanir(k, getString(R.string.show), false, baslikCevir = false) { govde -> parcalariCiz(govde, bol.parcalar, b) }
            }
        }
        icerik.addView(k)
    }

    /** Başlığa dokununca açılan kutu. İçerik ilk açılışta çizilir (uzun README'ler hızlı kalsın). */
    private fun katlanir(kap: LinearLayout, baslik: String, acik: Boolean, baslikCevir: Boolean = true, ciz: (LinearLayout) -> Unit) {
        val satir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(2) }
            ui.dokunulabilir(this, 8)
        }
        val ok = ui.yaziGorunumu(if (acik) "▾" else "▸", 16f, renk = ui.vurgu).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val yazi = (if (baslikCevir) cevrilen(baslik, 15f, kalin = true) else ui.yaziGorunumu(baslik, 15f, kalin = true)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        satir.addView(ok); satir.addView(yazi)
        val govde = ui.kolon().apply {
            setPadding(ui.dp(6), 0, 0, ui.dp(6))
            visibility = if (acik) View.VISIBLE else View.GONE
        }
        var cizildi = false
        if (acik) { ciz(govde); cizildi = true }
        satir.setOnClickListener {
            val ac = govde.visibility != View.VISIBLE
            if (ac && !cizildi) {
                kuyruk.oncelikli { ciz(govde) }
                cizildi = true
            }
            govde.visibility = if (ac) View.VISIBLE else View.GONE
            ok.text = if (ac) "▾" else "▸"
        }
        kap.addView(satir)
        kap.addView(govde)
    }

    private fun parcalariCiz(kap: LinearLayout, parcalar: List<Parca>, b: RepoBilgisi) {
        for (p in parcalar) when (p) {
            is Parca.Baslik -> kap.addView(cevrilen(p.metin, if (p.seviye <= 2) 16f else 15f, kalin = true, ust = 12))
            is Parca.Paragraf -> {
                kap.addView(cevrilen(p.metin, 15f, ust = 8))
                baglantilariCiz(kap, p.baglantilar, b)
            }
            is Parca.Alinti -> {
                val c = cevrilen(p.metin, 14.5f, renk = ui.sonuk, ust = 8).apply {
                    setPadding(ui.dp(12), ui.dp(4), 0, ui.dp(4))
                }
                val sar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = ui.dp(8) }
                    addView(View(this@RepoActivity).apply {
                        setBackgroundColor(ui.cizgi)
                        layoutParams = LinearLayout.LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
                    })
                    c.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(c)
                }
                kap.addView(sar)
                baglantilariCiz(kap, p.baglantilar, b)
            }
            is Parca.Liste -> {
                val tumBaglantilar = ArrayList<Baglanti>()
                var sayac = 0
                p.ogeler.forEach { o ->
                    if (o.derinlik == 0) sayac++
                    val isaret = when { o.derinlik > 0 -> "◦"; o.sirali -> "$sayac."; else -> "•" }
                    val satir = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(ui.dp(4 + o.derinlik * 16), ui.dp(4), 0, 0)
                    }
                    satir.addView(ui.yaziGorunumu(isaret, 15f, renk = ui.sonuk).apply {
                        layoutParams = LinearLayout.LayoutParams(ui.dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    satir.addView(cevrilen(o.metin, 15f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    kap.addView(satir)
                    tumBaglantilar += o.baglantilar
                }
                baglantilariCiz(kap, tumBaglantilar, b)
            }
            is Parca.Kod -> kap.addView(kodKutusu(p))
            is Parca.Tablo -> kap.addView(tablo(p))
            is Parca.Resim -> resimCiz(kap, p, b)
        }
    }

    private fun baglantilariCiz(kap: LinearLayout, baglantilar: List<Baglanti>, b: RepoBilgisi) {
        val gercek = baglantilar.mapNotNull { bg ->
            RepoAnaliz.adresCoz(bg.adres, b.sahip, b.ad, b.dal)?.let { bg.metin to it }
        }.distinctBy { it.second }.take(6)
        gercek.forEach { (m, adres) ->
            kap.addView(ui.baglantiYazisi("🔗 $m", ust = 2) { ui.ac(adres) })
        }
    }

    /** README'deki ekran görüntüsü. Yüklenemezse sessizce kaybolur; dokununca tarayıcıda açılır. */
    private fun resimCiz(kap: LinearLayout, r: Parca.Resim, b: RepoBilgisi) {
        val adres = RepoAnaliz.resimAdresi(r.adres, b.sahip, b.ad, b.dal) ?: return
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = r.aciklama
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(10) }
            minimumHeight = ui.dp(40)
            background = GradientDrawable().apply { cornerRadius = ui.dpf(8); setColor(Color.argb(20, 128, 128, 128)) }
            setOnClickListener { ui.ac(adres) }
        }
        kap.addView(iv)
        ResimYukleyici.yukle(adres, resources.displayMetrics.widthPixels) { bmp ->
            if (isDestroyed) return@yukle
            if (bmp == null) iv.visibility = View.GONE
            else { iv.background = null; iv.minimumHeight = 0; iv.setImageBitmap(bmp) }
        }
    }

    private fun kodKutusu(k: Parca.Kod): View {
        val kutu = ui.kolon().apply {
            background = GradientDrawable().apply {
                cornerRadius = ui.dpf(8); setColor(ui.kodZemin); setStroke(ui.dp(1), ui.cizgi)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(8) }
        }
        val ust = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(4), 0)
        }
        ust.addView(ui.yaziGorunumu(k.dil.ifEmpty { "</>" }, 12f, renk = ui.sonuk).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ust.addView(TextView(this).apply {
            text = "📋 " + getString(R.string.btn_copy)
            textSize = 13f
            setTextColor(ui.vurgu)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            ui.dokunulabilir(this, 6)
            setOnClickListener {
                val pano = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                pano.setPrimaryClip(ClipData.newPlainText("kod", k.kod))
                Toast.makeText(this@RepoActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        })
        kutu.addView(ust)
        val satirlar = k.kod.lines()
        val gosterilen = if (satirlar.size > 60) satirlar.take(60).joinToString("\n") + "\n…" else k.kod
        val kod = TextView(this).apply {
            text = gosterilen
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(ui.yazi)
            setTextIsSelectable(true)
            setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(12))
        }
        kutu.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(kod) })
        return kutu
    }

    private fun tablo(t: Parca.Tablo): View {
        val enFazla = 12
        val izgara = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sutun = t.satirlar.maxOf { it.size }
        t.satirlar.take(enFazla + 1).forEachIndexed { i, r ->
            val satir = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (i == 0) setBackgroundColor(ui.cizgi) else if (i % 2 == 0) setBackgroundColor(Color.argb(18, 128, 128, 128))
            }
            for (j in 0 until sutun) {
                val h = r.getOrElse(j) { "" }
                val v = if (h.any { it.isLetter() } && h.length > 1) cevrilen(h, 13.5f, kalin = i == 0) else ui.yaziGorunumu(h, 13.5f, kalin = i == 0)
                v.layoutParams = LinearLayout.LayoutParams(ui.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)
                v.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                satir.addView(v)
            }
            izgara.addView(satir)
        }
        val sar = ui.kolon().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(8) }
            background = GradientDrawable().apply { cornerRadius = ui.dpf(8); setStroke(ui.dp(1), ui.cizgi) }
            setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }
        sar.addView(HorizontalScrollView(this).apply { addView(izgara) })
        if (t.satirlar.size > enFazla + 1) {
            sar.addView(ui.yaziGorunumu(getString(R.string.more_rows, t.satirlar.size - enFazla - 1), 13f, renk = ui.sonuk).apply {
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            })
        }
        return sar
    }

    // ---------------- Dosyalar ve alt düğmeler ----------------

    private fun dosyaKarti(b: RepoBilgisi) {
        val onemli = b.kok.orEmpty().mapNotNull { o -> RepoAnaliz.dosyaTuru(o.ad, o.klasor)?.let { o to it } }
            .sortedWith(compareBy({ !it.first.klasor }, { it.second.ordinal }))
            .take(14)
        if (onemli.isEmpty()) return
        val k = ui.kart()
        k.addView(ui.kartBasligi(getString(R.string.sec_files)))
        onemli.forEach { (o, tur) ->
            val satir = ui.kolon().apply {
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                ui.dokunulabilir(this, 8)
                setOnClickListener {
                    ui.ac("https://github.com/${b.sahip}/${b.ad}/${if (o.klasor) "tree" else "blob"}/${b.dal}/${o.ad}")
                }
            }
            satir.addView(ui.yaziGorunumu((if (o.klasor) "📁 " else "📄 ") + o.ad, 14.5f, kalin = true))
            satir.addView(ui.yaziGorunumu(getString(dosyaYazisi(tur)), 13.5f, renk = ui.sonuk, ust = 2))
            k.addView(satir)
        }
        icerik.addView(k)
    }

    private fun dosyaYazisi(t: DosyaTuru) = when (t) {
        DosyaTuru.README -> R.string.file_readme
        DosyaTuru.LISANS -> R.string.file_license
        DosyaTuru.DEGISIKLIKLER -> R.string.file_changes
        DosyaTuru.KATKI -> R.string.file_contrib
        DosyaTuru.BELGELER -> R.string.file_docs
        DosyaTuru.ORNEKLER -> R.string.file_examples
        DosyaTuru.KAYNAK -> R.string.file_source
        DosyaTuru.TEST -> R.string.file_tests
        DosyaTuru.DERLENMIS -> R.string.file_dist
        DosyaTuru.BETIKLER -> R.string.file_scripts
        DosyaTuru.GITHUB -> R.string.file_github
        DosyaTuru.DOCKER -> R.string.file_docker
        DosyaTuru.BAGIMLILIK -> R.string.file_deps
        DosyaTuru.AYAR -> R.string.file_config
        DosyaTuru.VERI -> R.string.file_data
        DosyaTuru.RESIMLER -> R.string.file_images
    }

    private fun altDugmeler(b: RepoBilgisi) {
        val repoAdresi = "https://github.com/${b.sahip}/${b.ad}"
        icerik.addView(ui.dugme(getString(R.string.btn_github), ust = 18) { ui.ac(repoAdresi) })
        icerik.addView(ui.dugme(getString(R.string.btn_zip), ana = false) {
            ui.ac("$repoAdresi/archive/refs/heads/${b.dal}.zip")
        })
        lateinit var degistir: TextView
        degistir = ui.dugme(getString(R.string.btn_original), ana = false) {
            orijinalGoster = !orijinalGoster
            kuyruk.hepsiniGoster(orijinalGoster)
            degistir.text = getString(if (orijinalGoster) R.string.btn_translated else R.string.btn_original)
        }
        icerik.addView(degistir)
        icerik.addView(ui.yaziGorunumu(getString(R.string.translated_to, Ayarlar.dilAdi(hedef)), 12f, renk = ui.sonuk, ust = 12)
            .apply { gravity = Gravity.CENTER })
        // Yeni sürüm varsa küçük bir bağlantı
        val yer = ui.kolon()
        icerik.addView(yer)
        Guncelleyici.kontrol(this, zorla = false) { y ->
            if (y == null || isDestroyed) return@kontrol
            yer.addView(ui.baglantiYazisi("🔄 " + getString(R.string.upd_panel_link, y.etiket), ust = 6) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }.apply { gravity = Gravity.CENTER })
        }
    }

    // ---------------- Çeviri ----------------

    /** Orijinal yazıyla gösterilir, çevirisi gelince yerine konur. */
    private fun cevrilen(m: String, boyut: Float, kalin: Boolean = false, renk: Int = ui.yazi, ust: Int = 0): TextView {
        val t = ui.yaziGorunumu(m, boyut, kalin, renk, ust = ust, secilebilir = false)
        kuyruk.ekle(t, m)
        return t
    }

    /** Ekrana sırayla eklenen yazıları yukarıdan aşağı, aynı anda en fazla 3 tane çevirir. */
    private inner class CeviriKuyrugu {
        private inner class Is(val gorunum: TextView, val orijinal: String) { var ceviri: String? = null }

        /** Ekrandaki bütün yazılar (orijinal/çeviri düğmesi için) */
        private val tumu = ArrayList<Is>()
        /** Henüz çevrilmemiş olanlar, sırayla */
        private val isler = ArrayList<Is>()
        private var sira = 0
        private var calisan = 0
        private var ekNoktasi = -1
        private val calistigiHedef = hedef

        fun ekle(t: TextView, m: String) {
            val i = Is(t, m)
            tumu.add(i)
            // Arka planda hazırlandıysa beklemeden göster
            val hazir = CeviriMotoru.onbellekte(m, calistigiHedef)
            if (hazir != null) {
                i.ceviri = hazir.ifEmpty { null }
                if (i.ceviri != null && !orijinalGoster) t.text = i.ceviri
                return
            }
            if (ekNoktasi >= 0) isler.add(ekNoktasi++, i) else isler.add(i)
            t.alpha = 0.55f
            calistir()
        }

        /** Kullanıcının yeni açtığı bölüm sıranın önüne geçsin */
        fun oncelikli(blok: () -> Unit) {
            ekNoktasi = sira
            try { blok() } finally { ekNoktasi = -1 }
            calistir()
        }

        private fun calistir() {
            if (ekNoktasi >= 0) return
            while (calisan < 3 && sira < isler.size) {
                val i = isler[sira++]
                calisan++
                CeviriMotoru.cevir(i.orijinal, calistigiHedef) { c ->
                    calisan--
                    if (kuyruk !== this@CeviriKuyrugu || isDestroyed) return@cevir
                    i.ceviri = c
                    i.gorunum.alpha = 1f
                    if (c != null && !orijinalGoster) i.gorunum.text = c
                    calistir()
                }
            }
        }

        fun hepsiniGoster(orijinal: Boolean) {
            tumu.forEach { it.gorunum.text = if (orijinal || it.ceviri == null) it.orijinal else it.ceviri }
        }
    }

    // ---------------- Küçük yardımcılar ----------------

    private fun benzer(a: String, b: String): Boolean {
        fun n(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = n(a); val y = n(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (x.length > 20 && y.contains(x.take(40))) || (y.length > 20 && x.contains(y.take(40)))
    }

    private fun yildizYaz(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    private fun onceYaz(zaman: Long): String {
        val dk = ((System.currentTimeMillis() - zaman) / 60_000).coerceAtLeast(0)
        return when {
            dk < 2 -> getString(R.string.ago_now)
            dk < 60 -> getString(R.string.ago_min, dk.toInt())
            dk < 60 * 24 -> getString(R.string.ago_hour, (dk / 60).toInt())
            dk < 60 * 24 * 30 -> getString(R.string.ago_day, (dk / (60 * 24)).toInt())
            dk < 60 * 24 * 365 -> getString(R.string.ago_month, (dk / (60 * 24 * 30)).toInt())
            else -> getString(R.string.ago_year, (dk / (60 * 24 * 365)).toInt())
        }
    }
}
