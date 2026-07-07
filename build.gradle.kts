// ── Huawei AppGallery Connect gradle plugin (legacy buildscript path) ──
// AGC isn't published as a proper plugin marker on Gradle Plugin Portal;
// we have to add its classpath via buildscript {} and apply it with the
// `apply(plugin = ...)` syntax in :app.  It reads
// app/src/main/assets/agconnect-services.json at sync time and writes the
// credentials into BuildConfig + manifest metadata so
// MLApplication.initialize() can find them — without it HMS 3.x throws
// NPE on getAppId() even in pure-local OCR.
//
// AGP also has to be on the buildscript classpath so the AGC plugin's
// apply() method can find the Android plugin instance it hooks into for
// variant processing.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("com.huawei.agconnect:agcp:1.9.1.301")
    }
}

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
