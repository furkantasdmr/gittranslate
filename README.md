# GitHub Çevirmen

GitHub Android uygulamasında Çince, İngilizce, Japonca ya da başka bir dilde yazı gördüğünde onu **anında Türkçeye** (veya seçtiğin dile) çevirip yazının tam üstüne yazan küçük bir yardımcı uygulama.

- Sadece GitHub uygulaması açıkken çalışır, başka uygulamalarda hiçbir şey yapmaz.
- Çeviri önce Google ML Kit ile **telefonun içinde** yapılır. Dil paketi inemezse (ör. Google Play Hizmetleri olmayan telefonlarda) yalnızca çevrilecek yazılar Google Çeviri'ye gönderilerek internetten çevrilir.
- Uygulamanın yazıyı metin olarak vermediği yerlerde (ör. README) ekran görüntüsünden yazıyı **telefonun içinde okur (OCR)**. Android 11 veya üstü gerekir.
- Çevrilen kutuların solunda ince mavi bir şerit olur. Kutulara dokunursan dokunma alttaki GitHub öğesine geçer.
- Sağ kenardaki mavi balon: dokun → çeviri aç/kapat, basılı tut ve sürükle → yerini değiştir. ⏳ görürsen dil paketi iniyordur.

## Kurulum

1. Bu reponun **Releases** bölümünden en son `GitHub-Cevirmen.apk` dosyasını telefonuna indir ve kur.
2. Uygulamayı aç, **Erişilebilirlik ayarlarını aç** düğmesine bas, *İndirilen uygulamalar* altında **GitHub Çevirmen**'i aç.
   - Android 13 ve üstünde gri/kısıtlı görünürse: uygulama bilgisi → sağ üst ⋮ → **Kısıtlanmış ayarlara izin ver**, sonra tekrar dene.
3. (Önerilir) **Şimdi indir** ile sık kullanılan dil paketlerini Wi-Fi'de önceden indir.
4. GitHub'ı aç, yabancı bir repoya gir. Bu kadar.

## Nasıl çalışıyor?

| Dosya | Ne yapar |
|---|---|
| `CeviriServisi.kt` | Erişilebilirlik servisi. GitHub öndeyken ekrandaki yazıları ve konumlarını okur. |
| `CeviriMotoru.kt` | Dili tanır (ML Kit Language ID) ve çevirir (ML Kit Translate), sonuçları önbellekte tutar. |
| `CeviriKatmani.kt` | Dokunmaları geçiren şeffaf katman; çevirileri orijinal yazının üstüne çizer. |
| `MainActivity.kt` | Kurulum ve ayar ekranı. |
| `Ayarlar.kt` | Hedef dil, açık/kapalı durumu, çalışılacak uygulama listesi. |

Başka bir uygulamada da çalışsın istersen `Ayarlar.kt` içindeki `HEDEF_UYGULAMALAR` listesine o uygulamanın paket adını ekle.

## Derleme

Bilgisayar gerekmez: `main` dalına her gönderimde GitHub Actions APK'yı derleyip yeni bir Release yayınlar.

> Not: APK standart debug imzasıyla imzalanır ve bu imza her derlemede değişebilir. Yeni sürüm "uygulama yüklenmedi" derse önce eskisini kaldırıp sonra kur.
