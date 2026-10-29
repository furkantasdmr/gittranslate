package io.github.glxyjester.gittranslate

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Uygulama içinden güncelleme. Yeni sürümü GitHub Releases'tan indirip Android'in
 * PackageInstaller'ı ile kurar. Uygulama kendi kendini güncellediği için Android'in
 * "kısıtlı ayar" engeli tekrar çıkmaz ve erişilebilirlik izni açık kalır.
 */
object Guncelleyici {

    /** Release'lerin bulunduğu repo (ad değişirse GitHub eski adresi yönlendirir) */
    const val REPO = "glxyjester/gittranslate"
    private const val DOSYA = "GitTranslate.apk"

    data class Yeni(val kod: Long, val etiket: String, val adres: String, val boyut: Long)

    private val ana = Handler(Looper.getMainLooper())
    private val is_ = Executors.newSingleThreadExecutor()

    /** Son kontrolde bulunan yeni sürüm (yoksa null) */
    var bilinen: Yeni? = null
        private set
    private var sonKontrol = 0L

    fun mevcutKod(c: Context): Long {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun mevcutAd(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    /** @param zorla false ise en fazla 3 saatte bir GitHub'a sorar */
    fun kontrol(c: Context, zorla: Boolean, sonuc: (Yeni?) -> Unit) {
        val simdi = System.currentTimeMillis()
        if (!zorla && simdi - sonKontrol < 3 * 60 * 60 * 1000L) { sonuc(bilinen); return }
        val mevcut = try { mevcutKod(c) } catch (_: Exception) { Long.MAX_VALUE }
        is_.execute {
            val yeni = try {
                val b = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                b.connectTimeout = 10000; b.readTimeout = 15000
                b.setRequestProperty("Accept", "application/vnd.github+json")
                b.setRequestProperty("User-Agent", "GitTranslate-Android")
                try {
                    if (b.responseCode != 200) null else {
                        val o = JSONObject(b.inputStream.bufferedReader().use { it.readText() })
                        val etiket = o.optString("tag_name")
                        val dosyalar = o.optJSONArray("assets")
                        var adres = ""; var boyut = 0L; var bilgiAdresi = ""
                        if (dosyalar != null) for (i in 0 until dosyalar.length()) {
                            val d = dosyalar.getJSONObject(i)
                            when (d.optString("name")) {
                                DOSYA -> { adres = d.optString("browser_download_url"); boyut = d.optLong("size") }
                                "surum.json" -> bilgiAdresi = d.optString("browser_download_url")
                            }
                        }
                        // Derleme numarası (versionCode) surum.json'da; yoksa eski düzen: etiketin son sayısı
                        val kod = bilgiAdresi.takeIf { it.isNotEmpty() }?.let { derlemeNo(it) }
                            ?: etiket.substringAfterLast('.').toLongOrNull() ?: 0L
                        if (kod > mevcut && adres.isNotEmpty()) Yeni(kod, etiket, adres, boyut) else null
                    }
                } finally { b.disconnect() }
            } catch (_: Exception) { bilinen }
            ana.post {
                sonKontrol = simdi
                bilinen = yeni
                sonuc(yeni)
            }
        }
    }

    private fun derlemeNo(adres: String): Long? = try {
        val b = URL(adres).openConnection() as HttpURLConnection
        b.connectTimeout = 10000; b.readTimeout = 10000; b.instanceFollowRedirects = true
        b.setRequestProperty("User-Agent", "GitTranslate-Android")
        try {
            if (b.responseCode != 200) null
            else JSONObject(b.inputStream.bufferedReader().use { it.readText() }).optLong("kod").takeIf { it > 0 }
        } finally { b.disconnect() }
    } catch (_: Exception) { null }

    /** Bilinmeyen kaynaktan kurma izni gerekiyorsa ayar sayfasını açar ve false döner. */
    fun izinVarMi(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()) return true
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        } catch (_: Exception) {}
        return false
    }

    /**
     * İndirir ve kurulumu başlatır. Android sonunda "Güncellensin mi?" diye bir kez sorar.
     * @param ilerleme 0-100 indirme yüzdesi, -1 = kuruluyor
     */
    fun kur(a: Activity, y: Yeni, ilerleme: (Int) -> Unit, hata: (String) -> Unit) {
        val c = a.applicationContext
        is_.execute {
            try {
                val hedef = File(c.cacheDir, "guncelleme.apk")
                val b = URL(y.adres).openConnection() as HttpURLConnection
                b.connectTimeout = 15000; b.readTimeout = 30000
                b.instanceFollowRedirects = true
                b.setRequestProperty("User-Agent", "GitTranslate-Android")
                try {
                    if (b.responseCode != 200) throw IllegalStateException("HTTP ${b.responseCode}")
                    val toplam = b.contentLengthLong.takeIf { it > 0 } ?: y.boyut
                    var inen = 0L; var sonYuzde = -1
                    b.inputStream.use { girdi ->
                        hedef.outputStream().use { cikti ->
                            val tampon = ByteArray(64 * 1024)
                            while (true) {
                                val n = girdi.read(tampon); if (n < 0) break
                                cikti.write(tampon, 0, n); inen += n
                                val yuzde = if (toplam > 0) (inen * 100 / toplam).toInt() else 0
                                if (yuzde != sonYuzde) { sonYuzde = yuzde; ana.post { ilerleme(yuzde) } }
                            }
                        }
                    }
                    if (toplam > 0 && inen != toplam) throw IllegalStateException("eksik indi ($inen/$toplam)")
                } finally { b.disconnect() }

                ana.post { ilerleme(-1) }
                val kurucu = c.packageManager.packageInstaller
                val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(c.packageName)
                    // Android 12+: uygulama kendini güncelliyorsa onay ekranı hiç çıkmayabilir
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = kurucu.createSession(p)
                kurucu.openSession(id).use { oturum ->
                    oturum.openWrite("gittranslate", 0, hedef.length()).use { cikti ->
                        hedef.inputStream().use { it.copyTo(cikti) }
                        oturum.fsync(cikti)
                    }
                    val bayrak = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val bekleyen = PendingIntent.getBroadcast(c, id, Intent(c, GuncellemeAlici::class.java), bayrak)
                    oturum.commit(bekleyen.intentSender)
                }
            } catch (e: Exception) {
                ana.post { hata(e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

/** Kurulum sonucu: onay gerekiyorsa Android'in onay ekranını açar, hata olursa söyler. */
class GuncellemeAlici : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val onay = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                onay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(onay) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"
                Toast.makeText(c, c.getString(R.string.upd_failed, m), Toast.LENGTH_LONG).show()
            }
        }
    }
}
