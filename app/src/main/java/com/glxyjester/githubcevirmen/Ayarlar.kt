package com.glxyjester.githubcevirmen

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

object Ayarlar {
    private const val DOSYA = "ayarlar"
    private const val HEDEF_DIL = "hedef_dil"

    /** Seçilebilen çeviri dilleri: (ML Kit kodu, kendi dilindeki adı) */
    val DILLER = listOf(
        TranslateLanguage.TURKISH to "Türkçe",
        TranslateLanguage.ENGLISH to "English",
        TranslateLanguage.GERMAN to "Deutsch",
        TranslateLanguage.FRENCH to "Français",
        TranslateLanguage.SPANISH to "Español",
        TranslateLanguage.PORTUGUESE to "Português",
        TranslateLanguage.ITALIAN to "Italiano",
        TranslateLanguage.DUTCH to "Nederlands",
        TranslateLanguage.POLISH to "Polski",
        TranslateLanguage.RUSSIAN to "Русский",
        TranslateLanguage.UKRAINIAN to "Українська",
        TranslateLanguage.ARABIC to "العربية",
        TranslateLanguage.PERSIAN to "فارسی",
        TranslateLanguage.HINDI to "हिन्दी",
        TranslateLanguage.INDONESIAN to "Bahasa Indonesia",
        TranslateLanguage.VIETNAMESE to "Tiếng Việt",
        TranslateLanguage.JAPANESE to "日本語",
        TranslateLanguage.KOREAN to "한국어",
        TranslateLanguage.CHINESE to "中文",
    )

    /** "Şimdi indir" ile önceden indirilen, GitHub'da en sık görülen kaynak diller */
    val SIK_KAYNAK_DILLER = listOf(
        TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE, TranslateLanguage.JAPANESE,
        TranslateLanguage.KOREAN, TranslateLanguage.RUSSIAN,
    )

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(DOSYA, Context.MODE_PRIVATE)

    /** Seçilmemişse telefonun dili (destekleniyorsa), yoksa İngilizce */
    fun hedefDil(c: Context): String {
        prefs(c).getString(HEDEF_DIL, null)?.let { return it }
        val cihaz = Locale.getDefault().language.let { if (it == "iw") "he" else if (it == "in") "id" else it }
        return DILLER.firstOrNull { it.first == cihaz }?.first ?: TranslateLanguage.ENGLISH
    }

    fun hedefDilKaydet(c: Context, kod: String) = prefs(c).edit().putString(HEDEF_DIL, kod).apply()

    fun dilAdi(kod: String): String = DILLER.firstOrNull { it.first == kod }?.second ?: kod
}
