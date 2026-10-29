plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede numarayı artırır; böylece yeni APK eskisinin üstüne kurulur.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// İmza anahtarı CI'da hazırlanır (bkz. .github/workflows/apk.yml). Yoksa debug imzası kullanılır.
val imzaDosyasi = System.getenv("IMZA_DOSYASI")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "io.github.glxyjester.gittranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.glxyjester.gittranslate"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
    }

    signingConfigs {
        if (imzaDosyasi != null) create("surum") {
            storeFile = imzaDosyasi
            storePassword = System.getenv("IMZA_SIFRE")
            keyAlias = "gittranslate"
            keyPassword = System.getenv("IMZA_SIFRE")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sabit imza: yeni sürümler eskisinin üstüne kurulur
            signingConfig = signingConfigs.findByName("surum") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Cihaz içinde, internetsiz çalışan Google ML Kit
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Balon: ekrandaki repo adını görüntüden okumak için (Latin harfleri, model APK içinde)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
