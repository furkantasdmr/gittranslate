package com.glxyjester.githubcevirmen

/*
 * Yapay zekâ kullanmadan, GitHub'ın standart bilgilerinden kurallarla çıkarım yapar:
 * proje türü, lisansın sade anlamı, dosya/klasörlerin ne işe yaradığı, README bölümlerinin türü.
 * Android'e bağımlı değildir (birim testli).
 */

enum class ProjeTuru {
    ANDROID_UYGULAMA, WINDOWS_PROGRAM, MAC_PROGRAM, LINUX_PROGRAM,
    PYTHON, NODE, ANDROID_KAYNAK, RUST, GO, JAVA, DOTNET, CPP, RUBY, PHP, FLUTTER, SWIFT,
    WEB_SITESI, DOCKER, JUPYTER, LISTE, VERI,
}

enum class TelefonUyumu { KULLANILIR, OKUNUR, BILGISAYAR }

enum class LisansTuru { SERBEST, PAYLASIMLI, YOK, DIGER }

enum class DosyaTuru {
    README, LISANS, DEGISIKLIKLER, KATKI, BELGELER, ORNEKLER, KAYNAK, TEST, DERLENMIS,
    BETIKLER, GITHUB, DOCKER, BAGIMLILIK, AYAR, VERI, RESIMLER,
}

object RepoAnaliz {

    // ---------------- Repo bağlantısını bul ----------------

    private val AYRILMIS = setOf(
        "settings", "orgs", "topics", "search", "explore", "sponsors", "marketplace", "notifications",
        "login", "logout", "join", "about", "features", "pricing", "collections", "trending", "issues",
        "pulls", "new", "organizations", "apps", "site", "security", "enterprise", "codespaces", "copilot",
    )

    sealed class BaglantiSonucu {
        data class Repo(val sahip: String, val ad: String) : BaglantiSonucu()
        data class KisiSayfasi(val sahip: String) : BaglantiSonucu()
        object Yok : BaglantiSonucu()
    }

    /** Paylaşılan yazının içinden github.com/sahip/repo bilgisini çıkarır. "sahip/repo" da kabul edilir. */
    fun baglantiAyikla(metin: String?): BaglantiSonucu {
        if (metin.isNullOrBlank()) return BaglantiSonucu.Yok
        val m = Regex("(?i)github\\.com/([A-Za-z0-9-]{1,39})(?:/([A-Za-z0-9._-]{1,100}))?").find(metin)
        if (m != null) {
            val sahip = m.groupValues[1]
            val ad = m.groupValues[2].removeSuffix(".git")
            if (sahip.lowercase() in AYRILMIS) return BaglantiSonucu.Yok
            return if (ad.isEmpty()) BaglantiSonucu.KisiSayfasi(sahip) else BaglantiSonucu.Repo(sahip, ad)
        }
        val kisa = Regex("^\\s*([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})\\s*$").find(metin)
        if (kisa != null) return BaglantiSonucu.Repo(kisa.groupValues[1], kisa.groupValues[2].removeSuffix(".git"))
        return BaglantiSonucu.Yok
    }

    // ---------------- README bölüm türü ----------------

    private val KURULUM = listOf(
        "install", "setup", "set up", "getting started", "get started", "quick start", "quickstart", "download",
        "requirements", "prerequisite", "build", "compile", "deploy",
        "kurulum", "yükle", "başlarken",
        "安装", "下载", "快速开始", "快速上手", "部署", "编译", "开始使用", "環境", "安裝", "下載",
        "インストール", "導入", "はじめに", "セットアップ", "설치", "시작하기",
        "установка", "начало работы", "instalación", "instalacion", "installation", "instalação", "installazione",
    )
    private val KULLANIM = listOf(
        "usage", "how to use", "how to", "use", "example", "examples", "tutorial", "guide", "demo", "run",
        "kullanım", "nasıl kullanılır", "örnek",
        "使用", "用法", "示例", "例子", "教程", "运行", "使い方", "利用方法", "使用方法", "사용법", "사용",
        "использование", "пример", "uso", "utilisation", "utilizzo", "verwendung", "beispiel",
    )
    private val OZELLIKLER = listOf(
        "feature", "highlights", "what is", "about", "introduction", "overview", "why",
        "özellik", "hakkında", "nedir",
        "功能", "特性", "特点", "简介", "介绍", "概述", "機能", "特徴", "概要", "기능", "소개",
        "возможности", "особенности", "características", "fonctionnalités", "funktionen",
    )
    private val EK = listOf(
        "license", "licence", "contribut", "changelog", "change log", "credits", "acknowledg", "thanks",
        "sponsor", "donate", "donation", "support", "author", "maintainer", "star history", "contact",
        "code of conduct", "security", "faq", "roadmap", "todo", "contributors", "community",
        "lisans", "katkı", "teşekkür", "bağış",
        "许可", "协议", "贡献", "致谢", "鸣谢", "感谢", "赞助", "捐赠", "联系", "更新日志", "作者",
        "ライセンス", "貢献", "謝辞", "라이선스", "기여", "лицензия", "благодарности",
    )

    fun bolumTuru(baslik: String): BolumTuru {
        val b = baslik.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        fun uyar(liste: List<String>) = liste.any { k ->
            if (k.length <= 4 && k.all { it in 'a'..'z' }) Regex("(^| )$k( |s |$)").containsMatchIn("$b ") else b.contains(k)
        }
        return when {
            uyar(EK) -> BolumTuru.EK
            uyar(KURULUM) -> BolumTuru.KURULUM
            uyar(KULLANIM) -> BolumTuru.KULLANIM
            uyar(OZELLIKLER) -> BolumTuru.OZELLIKLER
            else -> BolumTuru.DIGER
        }
    }

    // ---------------- Proje türü ----------------

    fun projeTurleri(
        kokDosyalar: List<String>,
        anaDil: String?,
        konular: List<String>,
        surumDosyalari: List<String>,
        repoAdi: String,
        readme: String?,
    ): List<ProjeTuru> {
        val adlar = kokDosyalar.map { it.lowercase() }.toSet()
        fun var_(vararg a: String) = a.any { it in adlar }
        fun uzanti(vararg u: String) = adlar.any { d -> u.any { d.endsWith(it) } }
        val varliklar = surumDosyalari.map { it.lowercase() }
        val dil = anaDil?.lowercase()
        val sonuc = LinkedHashSet<ProjeTuru>()

        if (varliklar.any { it.endsWith(".apk") }) sonuc += ProjeTuru.ANDROID_UYGULAMA
        if (varliklar.any { it.endsWith(".exe") || it.endsWith(".msi") || (it.contains("win") && it.endsWith(".zip")) })
            sonuc += ProjeTuru.WINDOWS_PROGRAM
        if (varliklar.any { it.endsWith(".dmg") || it.endsWith(".pkg") || ((it.contains("mac") || it.contains("darwin")) && (it.endsWith(".zip") || it.endsWith(".tar.gz"))) })
            sonuc += ProjeTuru.MAC_PROGRAM
        if (varliklar.any { it.endsWith(".appimage") || it.endsWith(".deb") || it.endsWith(".rpm") || it.endsWith(".flatpak") })
            sonuc += ProjeTuru.LINUX_PROGRAM

        val ad = repoAdi.lowercase()
        val listeMi = ad.startsWith("awesome") || konular.any { it.startsWith("awesome") || it == "list" || it == "resources" }
        if (listeMi) sonuc += ProjeTuru.LISTE

        if (var_("pubspec.yaml")) sonuc += ProjeTuru.FLUTTER
        if ((var_("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") &&
                (dil == "kotlin" || dil == "java" || adlar.contains("app"))) && ProjeTuru.FLUTTER !in sonuc)
            sonuc += ProjeTuru.ANDROID_KAYNAK
        if (var_("pyproject.toml", "setup.py", "requirements.txt", "setup.cfg", "pipfile", "environment.yml") || dil == "python")
            sonuc += ProjeTuru.PYTHON
        if (uzanti(".ipynb") || dil == "jupyter notebook") sonuc += ProjeTuru.JUPYTER
        if (var_("package.json") || dil == "javascript" || dil == "typescript") sonuc += ProjeTuru.NODE
        if (var_("cargo.toml") || dil == "rust") sonuc += ProjeTuru.RUST
        if (var_("go.mod") || dil == "go") sonuc += ProjeTuru.GO
        if ((var_("pom.xml") || dil == "java") && ProjeTuru.ANDROID_KAYNAK !in sonuc) sonuc += ProjeTuru.JAVA
        if (uzanti(".sln", ".csproj") || dil == "c#") sonuc += ProjeTuru.DOTNET
        if (var_("cmakelists.txt", "meson.build", "configure") || dil == "c" || dil == "c++") sonuc += ProjeTuru.CPP
        if (var_("gemfile") || dil == "ruby") sonuc += ProjeTuru.RUBY
        if (var_("composer.json") || dil == "php") sonuc += ProjeTuru.PHP
        if (var_("package.swift") || uzanti(".xcodeproj") || dil == "swift") sonuc += ProjeTuru.SWIFT
        if (var_("dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")) sonuc += ProjeTuru.DOCKER
        if ((var_("index.html") || dil == "html" || dil == "css") && sonuc.none { it in KOD_TURLERI }) sonuc += ProjeTuru.WEB_SITESI

        val veriBaglantisi = readme?.let { Regex("\\.(json|csv|sqlite|db|xlsx|parquet|geojson)\\b", RegexOption.IGNORE_CASE).findAll(it).count() } ?: 0
        if (veriBaglantisi >= 4 || var_("data", "dataset", "datasets")) sonuc += ProjeTuru.VERI

        return sonuc.toList()
    }

    private val KOD_TURLERI = setOf(
        ProjeTuru.PYTHON, ProjeTuru.NODE, ProjeTuru.RUST, ProjeTuru.GO, ProjeTuru.JAVA, ProjeTuru.DOTNET,
        ProjeTuru.CPP, ProjeTuru.RUBY, ProjeTuru.PHP, ProjeTuru.FLUTTER, ProjeTuru.SWIFT, ProjeTuru.ANDROID_KAYNAK,
    )

    fun telefonUyumu(turler: List<ProjeTuru>): TelefonUyumu = when {
        ProjeTuru.ANDROID_UYGULAMA in turler -> TelefonUyumu.KULLANILIR
        turler.isEmpty() || turler.all { it == ProjeTuru.LISTE || it == ProjeTuru.WEB_SITESI || it == ProjeTuru.VERI } -> TelefonUyumu.OKUNUR
        else -> TelefonUyumu.BILGISAYAR
    }

    // ---------------- Lisans ----------------

    fun lisansTuru(spdx: String?): LisansTuru {
        val s = spdx?.uppercase() ?: return LisansTuru.YOK
        return when {
            s.isEmpty() -> LisansTuru.YOK
            s == "NOASSERTION" || s == "OTHER" -> LisansTuru.DIGER
            s.startsWith("MIT") || s.startsWith("APACHE") || s.startsWith("BSD") || s == "UNLICENSE" ||
                s == "WTFPL" || s == "ISC" || s == "0BSD" || s.startsWith("CC0") || s == "ZLIB" ||
                s.startsWith("BSL") || s == "CC-BY-4.0" || s == "MIT-0" -> LisansTuru.SERBEST
            s.contains("GPL") || s.startsWith("MPL") || s.startsWith("EPL") || s.startsWith("EUPL") ||
                s.startsWith("CC-BY-SA") || s.startsWith("OSL") -> LisansTuru.PAYLASIMLI
            else -> LisansTuru.DIGER
        }
    }

    // ---------------- Dosya / klasör açıklamaları ----------------

    fun dosyaTuru(ad: String, klasor: Boolean): DosyaTuru? {
        val a = ad.lowercase()
        val kok = a.substringBeforeLast('.', a)
        if (!klasor) {
            if (kok.startsWith("readme")) return DosyaTuru.README
            if (kok == "license" || kok == "licence" || kok == "copying" || kok.startsWith("license-")) return DosyaTuru.LISANS
            if (kok == "changelog" || kok == "changes" || kok == "history" || kok == "releases" || kok == "news") return DosyaTuru.DEGISIKLIKLER
            if (kok == "contributing" || kok == "code_of_conduct") return DosyaTuru.KATKI
            if (a == "dockerfile" || a.startsWith("docker-compose") || a.startsWith("compose.y")) return DosyaTuru.DOCKER
            if (a in setOf("package.json", "requirements.txt", "pyproject.toml", "setup.py", "cargo.toml", "go.mod",
                    "pom.xml", "build.gradle", "build.gradle.kts", "gemfile", "composer.json", "pubspec.yaml", "pipfile",
                    "environment.yml", "cmakelists.txt", "makefile", "package.swift")) return DosyaTuru.BAGIMLILIK
            if (a.endsWith(".sh") || a.endsWith(".bat") || a.endsWith(".ps1") || a.endsWith(".cmd")) return DosyaTuru.BETIKLER
            if (a == ".env.example" || a.endsWith(".example") || a == "config.yml" || a == "config.yaml" || a == "config.json") return DosyaTuru.AYAR
            return null
        }
        return when (a) {
            "docs", "doc", "documentation", "wiki", "manual" -> DosyaTuru.BELGELER
            "examples", "example", "samples", "sample", "demo", "demos", "tutorial", "tutorials" -> DosyaTuru.ORNEKLER
            "src", "lib", "app", "source", "core", "pkg", "internal", "cmd", "packages", "modules" -> DosyaTuru.KAYNAK
            "test", "tests", "spec", "specs", "__tests__", "testing" -> DosyaTuru.TEST
            "dist", "build", "release", "releases", "bin", "out", "output" -> DosyaTuru.DERLENMIS
            "scripts", "script", "tools", "devscripts", "utils" -> DosyaTuru.BETIKLER
            ".github" -> DosyaTuru.GITHUB
            "data", "dataset", "datasets" -> DosyaTuru.VERI
            "assets", "images", "img", "screenshots", "media", "static", "public" -> DosyaTuru.RESIMLER
            "config", "configs", "conf" -> DosyaTuru.AYAR
            else -> null
        }
    }

    /** README'deki resim adresini indirilebilir tam adrese çevirir (göreli yollar raw sunucusundan). */
    fun resimAdresi(adres: String, sahip: String, ad: String, dal: String): String? {
        val a = adres.trim()
        if (a.isEmpty() || a.startsWith("#") || a.startsWith("data:")) return null
        if (a.startsWith("//")) return "https:$a"
        if (a.startsWith("http://") || a.startsWith("https://")) {
            // github.com/.../blob/... resim sayfası değil, ham dosya olsun
            Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(a)?.let {
                return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}"
            }
            return a
        }
        return "https://raw.githubusercontent.com/$sahip/$ad/$dal/" + a.removePrefix("./").removePrefix("/")
    }

    /** Göreli README bağlantısını tam GitHub adresine çevirir; sayfa içi bağlantılar için null. */
    fun adresCoz(adres: String, sahip: String, ad: String, dal: String): String? {
        val a = adres.trim()
        if (a.isEmpty() || a.startsWith("#")) return null
        if (a.startsWith("http://") || a.startsWith("https://") || a.startsWith("mailto:")) return a
        if (a.startsWith("//")) return "https:$a"
        val yol = a.removePrefix("./").removePrefix("/")
        return "https://github.com/$sahip/$ad/blob/$dal/$yol"
    }
}
