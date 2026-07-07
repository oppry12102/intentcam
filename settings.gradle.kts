pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Huawei AppGallery Connect gradle plugin lives here, not
        // on the standard Gradle plugin portal.  Without this the
        // `id("com.huawei.agconnect")` plugin lookup fails.
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ── HMS ML Kit repository (Huawei offline OCR + model download) ──
        // Required for `com.huawei.hms:ml-computer-vision-ocr` and the
        // Chinese language pack `ml-computer-vision-ocr-cn-model`.
        // Google ML Kit / ML Kit Text Recognition are NOT used here.
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "IntentCam"
include(":app")
include(":shared")
