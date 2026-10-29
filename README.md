# GitHub Çevirmen · Repoyu anlat

**En kolay kullanım:** Uygulamada balonu bir kez aç. GitHub'da bir reponun içindeyken sağdaki 📖 balonuna dokun, README senin dilinde açılsın. Balon hangi repoda olduğunu ekrandaki başlıktan (gerekirse ekran görüntüsünden) bulur; README'yi GitHub'dan çekip internetten çevirir.

GitHub'da bir repoya girdin ama ne işe yaradığını, nasıl kurulduğunu, nereye bakman gerektiğini anlamadın mı? Hele README Çince ya da İngilizceyse?

Bu küçük Android uygulaması GitHub uygulamasının **Paylaş** menüsüne bir seçenek ekler: **Repoyu anlat**. Dokununca GitHub'dan çıkmadan, üstte açılan bir panelde repo **senin dilinde ve sade bölümlerle** gösterilir:

- **Bu repo ne işe yarar?** Açıklama ve README'nin girişi, çevrilmiş hâliyle. Yıldız sayısı, son güncelleme, arşiv uyarısı.
- **Ne tür bir proje?** Telefonda mı kullanılır, bilgisayarda mı? Python mu, Android uygulaması mı, sadece bir liste mi? Lisans ne izin veriyor?
- **Hazır indirmeler:** Son sürümdeki APK / EXE / DMG dosyaları, tek dokunuşla.
- **Kurulum** ve **Kullanım:** README'den otomatik bulunur. Komutlar çevrilmez, **Kopyala** düğmesiyle alınır.
- **README'nin geri kalanı:** Bölüm bölüm, açılıp kapanır.
- **Önemli dosya ve klasörler:** `docs/`, `examples/`, `LICENSE` gibi dosyaların ne olduğu.

## İlkeler

- **Yapay zekâ yok.** Her şey GitHub'ın standart bilgilerinden kurallarla çıkarılır, sonra çevrilir.
- **Ücretsiz, hesap ve anahtar gerektirmez.** GitHub'ın herkese açık API'si kullanılır (saatte ~15 repo).
- **Çeviri telefonun içinde yapılır** (Google ML Kit). Dil paketi inemezse metin Google Çeviri'ye gönderilerek çevrilir.
- Arayüz Türkçe ve İngilizce. Çeviri dili 19 dilden seçilebilir, varsayılanı telefonun dilidir.

## Kurulum

1. [Releases](../../releases/latest) bölümünden `GitHub-Cevirmen.apk` dosyasını indirip kur.
2. GitHub uygulamasında bir repo aç → **Paylaş** → **Repoyu anlat**.

İstersen uygulamayı açıp bağlantı da yapıştırabilirsin.

## Kod

| Dosya | Ne yapar |
|---|---|
| `RepoActivity.kt` | Paylaş menüsünden açılan panel ve tüm bölümler |
| `GitHubApi.kt` | Repo bilgisi, README, kök dosyalar, son sürüm (4 istek) |
| `Markdown.kt` | README'yi başlık/paragraf/liste/kod/tablo parçalarına ve bölümlere ayırır |
| `RepoAnaliz.kt` | Proje türü, lisansın anlamı, dosya açıklamaları, bölüm türleri (kurallarla) |
| `CeviriMotoru.kt` | Dil tanıma + çeviri, önbellek |
| `MainActivity.kt` | Açılış ekranı |

`Markdown.kt` ve `RepoAnaliz.kt` Android'den bağımsızdır, `app/src/test` altında birim testleri var.
Yeni bir dil için bölüm anahtar kelimesi eklemek istersen `RepoAnaliz.kt` içindeki listelere eklemen yeter.

## Derleme

Bilgisayar gerekmez: `main` dalına her gönderimde GitHub Actions testleri çalıştırır, APK'yı derler ve yeni bir Release yayınlar.

> Not: APK standart debug imzasıyla imzalanır. Yeni sürüm "uygulama yüklenmedi" derse önce eskisini kaldır.
