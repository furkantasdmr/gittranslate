plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions her derlemede numarayı artırır; böylece yeni APK eskisinin üstüne kurulur.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.glxyjester.githubcevirmen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glxyjester.githubcevirmen"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Kişisel kullanım: ayrı bir imza anahtarı yerine standart debug imzası
            signingConfig = signingConfigs.getByName("debug")
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
    // Yazıyı ekran görüntüsünden okumak için (Çince + Latin), model APK'nın içinde
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
