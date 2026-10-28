package com.glxyjester.githubcevirmen

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Kurulum ve ayar ekranı. Kodla kuruldu, ayrı XML yok. */
class MainActivity : Activity() {

    private lateinit var durumYazisi: TextView
    private lateinit var kisitliNot: TextView
    private lateinit var indirmeYazisi: TextView
    private var motor: CeviriMotoru? = null

    private val koyu get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val yaziRengi get() = if (koyu) Color.rgb(230, 237, 243) else Color.rgb(31, 35, 40)
    private val sonukRenk get() = if (koyu) Color.rgb(145, 152, 161) else Color.rgb(89, 99, 110)
    private val kartRengi get() = if (koyu) Color.rgb(22, 27, 34) else Color.rgb(246, 248, 250)
    private val cizgiRengi get() = if (koyu) Color.rgb(48, 54, 61) else Color.rgb(209, 217, 224)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kolon = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        kolon.addView(yazi("GitHub Çevirmen", 26f, kalin = true))
        kolon.addView(yazi(
            "GitHub uygulamasında Çince, İngilizce ya da başka bir dilde yazı gördüğünde, " +
                "onu seçtiğin dile çevirip tam üstüne yazar. Çeviri telefonunun içinde yapılır.",
            15f, renk = sonukRenk, ust = 6,
        ))

        // 1) Servis durumu
        kolon.addView(baslik("1. Servisi aç"))
        val kart1 = kart()
        durumYazisi = yazi("", 15f, kalin = true)
        kart1.addView(durumYazisi)
        kart1.addView(yazi(
            "Ayarlarda \"İndirilen uygulamalar\" altındaki GitHub Çevirmen'i aç.",
            14f, renk = sonukRenk, ust = 4,
        ))
        kart1.addView(dugme("Erişilebilirlik ayarlarını aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        kisitliNot = yazi(
            "Gri görünüyor ya da \"Kısıtlı ayar\" diyorsa: aşağıdaki düğmeyle uygulama bilgisine git, " +
                "sağ üstteki ⋮ menüsünden \"Kısıtlanmış ayarlara izin ver\"i seç, sonra tekrar dene.",
            14f, renk = sonukRenk, ust = 12,
        )
        kart1.addView(kisitliNot)
        val bilgiDugmesi = dugme("Uygulama bilgisini aç") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        kart1.addView(bilgiDugmesi)
        if (Build.VERSION.SDK_INT < 33) {
            kisitliNot.visibility = View.GONE
            bilgiDugmesi.visibility = View.GONE
        }
        kolon.addView(kart1)

        // 2) Dil
        kolon.addView(baslik("2. Hangi dile çevrilsin?"))
        val kart2 = kart()
        val diller = Ayarlar.DILLER
        val secici = Spinner(this)
        secici.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, diller.map { it.second })
        secici.setSelection(diller.indexOfFirst { it.first == Ayarlar.hedefDil(this) }.coerceAtLeast(0))
        secici.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, konum: Int, id: Long) {
                Ayarlar.prefs(this@MainActivity).edit().putString(Ayarlar.HEDEF_DIL, diller[konum].first).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        kart2.addView(secici)

        val anahtar = Switch(this).apply {
            text = "Çeviri açık"
            textSize = 15f
            setTextColor(yaziRengi)
            isChecked = Ayarlar.acikMi(this@MainActivity)
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, acik ->
                Ayarlar.prefs(this@MainActivity).edit().putBoolean(Ayarlar.ACIK, acik).apply()
            }
        }
        kart2.addView(anahtar)
        kart2.addView(yazi(
            "GitHub'da sağ kenarda küçük mavi bir balon çıkar. Dokununca çeviri açılır/kapanır, " +
                "basılı tutup sürükleyerek yerini değiştirebilirsin.",
            14f, renk = sonukRenk, ust = 4,
        ))
        kolon.addView(kart2)

        // 3) Dil paketleri
        kolon.addView(baslik("3. Dil paketlerini indir (önerilir)"))
        val kart3 = kart()
        kart3.addView(yazi(
            "Çince, Japonca, Korece, Rusça ve İngilizce paketlerini şimdi indirirsen GitHub'da beklemeden çevirir. " +
                "Her biri yaklaşık 30 MB, Wi-Fi'de indirmek iyi olur. İndirmezsen ilk gördüğünde kendisi indirir.",
            14f, renk = sonukRenk,
        ))
        indirmeYazisi = yazi("", 14f, kalin = true, ust = 8)
        kart3.addView(indirmeYazisi)
        kart3.addView(dugme("Şimdi indir") { paketleriIndir() })
        kolon.addView(kart3)

        kolon.addView(dugme("GitHub'ı aç", ust = 24) {
            val niyet = packageManager.getLaunchIntentForPackage("com.github.android")
            if (niyet != null) startActivity(niyet)
            else Toast.makeText(this, "GitHub uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { addView(kolon) })
    }

    override fun onResume() {
        super.onResume()
        val acik = servisAcikMi()
        durumYazisi.text = if (acik) "✅ Servis açık" else "⚪ Servis kapalı"
        durumYazisi.setTextColor(if (acik) Color.rgb(26, 127, 55) else yaziRengi)
    }

    override fun onDestroy() {
        motor?.kapat()
        super.onDestroy()
    }

    private fun servisAcikMi(): Boolean {
        val acikOlanlar = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val biz = "$packageName/${CeviriServisi::class.java.name}"
        val kisa = "$packageName/.${CeviriServisi::class.java.simpleName}"
        return acikOlanlar.split(':').any { it.equals(biz, true) || it.equals(kisa, true) }
    }

    private fun paketleriIndir() {
        val hedef = Ayarlar.hedefDil(this)
        val kaynaklar = Ayarlar.SIK_KAYNAK_DILLER.filter { it != hedef }
        val m = motor ?: CeviriMotoru().also { motor = it }
        var biten = 0
        var hata = 0
        indirmeYazisi.text = "İndiriliyor… 0/${kaynaklar.size}"
        kaynaklar.forEach { kaynak ->
            m.cevirmenAl(kaynak, hedef) { c ->
                biten++
                if (c == null) hata++
                indirmeYazisi.text = when {
                    biten < kaynaklar.size -> "İndiriliyor… $biten/${kaynaklar.size}"
                    hata == 0 -> "✅ Hepsi hazır (${Ayarlar.dilAdi(hedef)} için)"
                    else -> "⚠️ ${kaynaklar.size - hata}/${kaynaklar.size} indi. İnternetini kontrol edip tekrar dene."
                }
            }
        }
    }

    // ---------------- Küçük arayüz yardımcıları ----------------

    private fun yazi(m: String, boyut: Float, kalin: Boolean = false, renk: Int = yaziRengi, ust: Int = 0) =
        TextView(this).apply {
            text = m
            textSize = boyut
            setTextColor(renk)
            if (kalin) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ust) }
        }

    private fun baslik(m: String) = yazi(m, 17f, kalin = true, ust = 26)

    private fun kart() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(kartRengi)
            setStroke(dp(1), cizgiRengi)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
    }

    private fun dugme(m: String, ust: Int = 12, tikla: () -> Unit) = Button(this).apply {
        text = m
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (koyu) Color.rgb(35, 134, 54) else Color.rgb(31, 136, 61))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
        ).apply { topMargin = dp(ust) }
        setOnClickListener { tikla() }
    }
}
