package com.glxyjester.githubcevirmen

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage

/** Uygulamanın küçük ayar deposu. */
object Ayarlar {
    const val DOSYA = "ayarlar"
    const val HEDEF_DIL = "hedef_dil"
    const val ACIK = "acik"
    const val BALON_Y = "balon_y"

    /** Çevirinin çalışacağı uygulamalar. İstersen buraya başka paket adları ekleyebilirsin. */
    val HEDEF_UYGULAMALAR = setOf("com.github.android")

    /** Ekranda seçilebilen hedef diller: (ML Kit kodu, görünen ad) */
    val DILLER = listOf(
        TranslateLanguage.TURKISH to "Türkçe",
        TranslateLanguage.ENGLISH to "English",
        TranslateLanguage.GERMAN to "Deutsch",
        TranslateLanguage.FRENCH to "Français",
        TranslateLanguage.SPANISH to "Español",
        TranslateLanguage.RUSSIAN to "Русский",
        TranslateLanguage.ARABIC to "العربية",
        TranslateLanguage.JAPANESE to "日本語",
    )

    /** "Şimdi indir" düğmesinin önceden indirdiği, GitHub'da en sık görülen kaynak diller. */
    val SIK_KAYNAK_DILLER = listOf(
        TranslateLanguage.CHINESE,
        TranslateLanguage.JAPANESE,
        TranslateLanguage.KOREAN,
        TranslateLanguage.RUSSIAN,
        TranslateLanguage.ENGLISH,
    )

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(DOSYA, Context.MODE_PRIVATE)

    fun hedefDil(c: Context): String =
        prefs(c).getString(HEDEF_DIL, TranslateLanguage.TURKISH) ?: TranslateLanguage.TURKISH

    fun acikMi(c: Context): Boolean = prefs(c).getBoolean(ACIK, true)

    fun dilAdi(kod: String): String = DILLER.firstOrNull { it.first == kod }?.second ?: kod
}
