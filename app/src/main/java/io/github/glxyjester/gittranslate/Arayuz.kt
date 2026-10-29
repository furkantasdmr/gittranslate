package io.github.glxyjester.gittranslate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Kodla arayüz kurmak için küçük yardımcılar ve GitHub'a benzeyen açık/koyu renkler. */
class Arayuz(val a: Activity) {

    val koyu = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val zemin = if (koyu) Color.rgb(13, 17, 23) else Color.rgb(255, 255, 255)
    val kart = if (koyu) Color.rgb(22, 27, 34) else Color.rgb(246, 248, 250)
    val cizgi = if (koyu) Color.rgb(48, 54, 61) else Color.rgb(209, 217, 224)
    val yazi = if (koyu) Color.rgb(230, 237, 243) else Color.rgb(31, 35, 40)
    val sonuk = if (koyu) Color.rgb(145, 152, 161) else Color.rgb(89, 99, 110)
    val vurgu = if (koyu) Color.rgb(68, 147, 248) else Color.rgb(9, 105, 218)
    val yesil = if (koyu) Color.rgb(35, 134, 54) else Color.rgb(31, 136, 61)
    val uyari = if (koyu) Color.rgb(210, 153, 34) else Color.rgb(154, 103, 0)
    val kodZemin = if (koyu) Color.rgb(1, 4, 9) else Color.rgb(239, 242, 245)

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    fun dpf(v: Int) = v * a.resources.displayMetrics.density

    fun yaziGorunumu(
        m: CharSequence = "", boyut: Float = 15f, kalin: Boolean = false, renk: Int = yazi,
        ust: Int = 0, alt: Int = 0, secilebilir: Boolean = false,
    ) = TextView(a).apply {
        text = m
        textSize = boyut
        setTextColor(renk)
        if (kalin) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
        setTextIsSelectable(secilebilir)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ust); bottomMargin = dp(alt) }
    }

    fun kolon(yatayPay: Int = 0) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(yatayPay), 0, dp(yatayPay), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun kart(ust: Int = 12) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dpf(12)
            setColor(kart)
            setStroke(dp(1), cizgi)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ust) }
    }

    fun kartBasligi(m: String) = yaziGorunumu(m, 17f, kalin = true, alt = 6)

    fun dokunulabilir(v: View, kose: Int = 10, renk: Int? = null, kenar: Boolean = false) {
        val sekil = GradientDrawable().apply {
            cornerRadius = dpf(kose)
            setColor(renk ?: Color.TRANSPARENT)
            if (kenar) setStroke(dp(1), cizgi)
        }
        val maske = GradientDrawable().apply { cornerRadius = dpf(kose); setColor(Color.WHITE) }
        v.background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 128, 128, 128)), sekil, maske)
        v.isClickable = true
        v.isFocusable = true
    }

    fun dugme(m: String, ana: Boolean = true, ust: Int = 10, tikla: () -> Unit) = TextView(a).apply {
        text = m
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ana) Color.WHITE else yazi)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        dokunulabilir(this, 10, if (ana) yesil else kart, kenar = !ana)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ust) }
        setOnClickListener { tikla() }
    }

    fun baglantiYazisi(m: String, ust: Int = 6, tikla: () -> Unit) = yaziGorunumu(m, 14f, renk = vurgu, ust = ust).apply {
        setPadding(dp(4), dp(6), dp(4), dp(6))
        dokunulabilir(this, 6)
        setOnClickListener { tikla() }
    }

    fun ac(adres: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(adres)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, adres, Toast.LENGTH_LONG).show()
        }
    }

    /** targetSdk 35'te uygulama kenardan kenara çizilir: durum/gezinme çubukları için pay bırak. */
    fun cubukPayi(v: View, ustPay: Boolean = true, altPay: Boolean = true) {
        val sol = v.paddingLeft; val ust = v.paddingTop; val sag = v.paddingRight; val alt = v.paddingBottom
        v.setOnApplyWindowInsetsListener { gorunum, ic ->
            val (u, b) = if (Build.VERSION.SDK_INT >= 30) {
                val s = ic.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                s.top to s.bottom
            } else {
                @Suppress("DEPRECATION")
                ic.systemWindowInsetTop to ic.systemWindowInsetBottom
            }
            gorunum.setPadding(sol, ust + if (ustPay) u else 0, sag, alt + if (altPay) b else 0)
            ic
        }
        v.requestApplyInsets()
    }
}
