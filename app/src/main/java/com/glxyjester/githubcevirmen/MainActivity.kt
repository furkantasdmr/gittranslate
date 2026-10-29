package com.glxyjester.githubcevirmen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Açılış ekranı: nasıl kullanılır, bağlantı yapıştırma, çeviri dili, dil paketleri. */
class MainActivity : Activity() {

    private lateinit var ui: Arayuz
    private lateinit var paketYazisi: TextView
    private lateinit var balonDurumu: TextView
    private lateinit var pilDugmesi: TextView

    override fun onResume() {
        super.onResume()
        val acik = servisAcikMi()
        balonDurumu.text = getString(if (acik) R.string.main_bubble_on else R.string.main_bubble_off)
        balonDurumu.setTextColor(if (acik) ui.yesil else ui.yazi)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        pilDugmesi.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun servisAcikMi(): Boolean {
        val liste = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val tam = "$packageName/${BalonServisi::class.java.name}"
        val kisa = "$packageName/.${BalonServisi::class.java.simpleName}"
        return liste.split(':').any { it.equals(tam, true) || it.equals(kisa, true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Arayuz(this)
        GitHubApi.baslat(this)
        window.decorView.setBackgroundColor(ui.zemin)

        val kolon = ui.kolon().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)) }

        kolon.addView(ui.yaziGorunumu(getString(R.string.app_name), 26f, kalin = true))
        kolon.addView(ui.yaziGorunumu(getString(R.string.main_intro), 15f, renk = ui.sonuk, ust = 6))

        // GitHub'da balon (erişilebilirlik servisi)
        val balon = ui.kart(ust = 18)
        balon.addView(ui.kartBasligi(getString(R.string.main_bubble_title)))
        balon.addView(ui.yaziGorunumu(getString(R.string.main_bubble_desc), 14.5f, renk = ui.sonuk))
        balonDurumu = ui.yaziGorunumu("", 15f, kalin = true, ust = 10)
        balon.addView(balonDurumu)
        balon.addView(ui.dugme(getString(R.string.main_bubble_btn)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            balon.addView(ui.yaziGorunumu(getString(R.string.main_restricted), 13.5f, renk = ui.sonuk, ust = 10))
            balon.addView(ui.dugme(getString(R.string.main_app_info), ana = false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        pilDugmesi = ui.dugme(getString(R.string.main_battery_btn), ana = false) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        balon.addView(pilDugmesi)
        kolon.addView(balon)

        // Nasıl kullanılır
        val nasil = ui.kart()
        nasil.addView(ui.kartBasligi(getString(R.string.main_how_title)))
        listOf(R.string.main_how_1, R.string.main_how_2, R.string.main_how_3).forEach {
            nasil.addView(ui.yaziGorunumu(getString(it), 15f, ust = 4))
        }
        nasil.addView(ui.dugme(getString(R.string.main_open_github), ust = 14) {
            val n = packageManager.getLaunchIntentForPackage("com.github.android")
            if (n != null) startActivity(n) else ui.ac("https://github.com")
        })
        kolon.addView(nasil)

        // Bağlantı yapıştır
        val yapistir = ui.kart()
        val giris = EditText(this).apply {
            hint = getString(R.string.main_paste_hint)
            textSize = 15f
            setTextColor(ui.yazi)
            setHintTextColor(ui.sonuk)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        fun anlat(metin: String) {
            startActivity(Intent(this, RepoActivity::class.java).setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, metin))
        }
        giris.setOnEditorActionListener { _, _, _ -> anlat(giris.text.toString()); true }
        yapistir.addView(giris)
        yapistir.addView(ui.dugme(getString(R.string.main_explain)) { anlat(giris.text.toString()) })
        yapistir.addView(ui.baglantiYazisi(getString(R.string.main_try), ust = 8) {
            anlat("https://github.com/modood/Administrative-divisions-of-China")
        })
        kolon.addView(yapistir)

        // Çeviri dili
        val dil = ui.kart()
        dil.addView(ui.kartBasligi(getString(R.string.main_lang)))
        val diller = Ayarlar.DILLER
        val secici = Spinner(this)
        secici.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, diller.map { it.second })
        secici.setSelection(diller.indexOfFirst { it.first == Ayarlar.hedefDil(this) }.coerceAtLeast(0))
        secici.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, konum: Int, id: Long) {
                Ayarlar.hedefDilKaydet(this@MainActivity, diller[konum].first)
                paketYazisi.text = ""
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        dil.addView(secici)
        kolon.addView(dil)

        // Dil paketleri
        val paket = ui.kart()
        paket.addView(ui.kartBasligi(getString(R.string.main_packs_title)))
        paket.addView(ui.yaziGorunumu(getString(R.string.main_packs_desc), 14f, renk = ui.sonuk))
        paketYazisi = ui.yaziGorunumu("", 14f, kalin = true, ust = 8)
        paket.addView(paketYazisi)
        paket.addView(ui.dugme(getString(R.string.main_packs_btn), ana = false) { paketleriIndir() })
        kolon.addView(paket)

        val kaydirma = ScrollView(this).apply { addView(kolon) }
        ui.cubukPayi(kolon)
        setContentView(kaydirma)
    }

    private fun paketleriIndir() {
        val hedef = Ayarlar.hedefDil(this)
        val kaynaklar = Ayarlar.SIK_KAYNAK_DILLER.filter { it != hedef }
        var biten = 0
        var basarili = 0
        paketYazisi.text = getString(R.string.packs_progress, 0, kaynaklar.size)
        kaynaklar.forEach { k ->
            CeviriMotoru.cevirmenAl(k, hedef) { c ->
                biten++
                if (c != null) basarili++
                paketYazisi.text = when {
                    biten < kaynaklar.size -> getString(R.string.packs_progress, biten, kaynaklar.size)
                    basarili == kaynaklar.size -> getString(R.string.packs_done, Ayarlar.dilAdi(hedef))
                    else -> getString(R.string.packs_partial, basarili, kaynaklar.size)
                }
            }
        }
    }
}
