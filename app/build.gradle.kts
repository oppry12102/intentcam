plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Huawei AGC plugin — applied via legacy syntax because it's loaded
// via buildscript classpath (see root build.gradle.kts).  Reads
// app/src/main/assets/agconnect-services.json and writes credentials
// into BuildConfig + manifest metadata so MLApplication.initialize()
// can find them; without it HMS 3.x NPEs on getAppId() in local mode.
apply(plugin = "com.huawei.agconnect")

android {
    namespace = "com.example.intentcam"
    compileSdk = 34

    // ── Signing ──
    // Release is currently signed with the SDK debug keystore for
    // local-dev / sideload testing only.  Do NOT distribute this APK
    // to end users — debug keystore has well-known credentials and
    // would not satisfy Play Store key requirements.
    //
    // To switch to a real release key: replace `debugKeystoreConfig`
    // with a real `signingConfigs.create("release") { storeFile = ...;
    // storePassword = ...; keyAlias = ...; keyPassword = ... }`, then
    // point release.signingConfig at it.  See AGP docs.
    signingConfigs {
        create("debugKeystore") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.intentcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // Native libs: arm64-v8a only.  x86 / x86_64 are emulator-only
        // (emulator uses host CPU); armeabi-v7a (32-bit ARM) is < 1% of
        // 2024+ Play Store devices and the ML Kit OCR .so for it adds
        // ~6.8 MB to the APK.  minSdk=26 covers the same device range
        // without paying for the legacy ABI.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debugKeystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Pure-Kotlin / JVM recognition pipeline (ToolUseLoop, LlmClient,
    // ToolImplementations, Models).  The eval in :shared reuses the
    // exact same classes — no parallel implementation to drift.
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // material-icons-core (49 icons) only — we don't use any Icons.Extended,
    // and the extended set adds ~7 MB to the APK.
    implementation("androidx.compose.material:material-icons-core")

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // ── On-device OCR: Huawei HMS ML Kit (offline, Chinese + Latin) ──
    // The auto-OCR path (round-1 pre-pass + per-zoom_in crop) calls
    // OcrEngine.recognize(); the impl is installed in
    // MainActivity.onCreate via installAndroidOcr().
    //
    // * ml-computer-vision-ocr — public OCR AAR (transitively pulls
    //   in ml-computer-vision-ocr-base + ml-computer-vision-cloud,
    //   which holds the actual MLTextAnalyzer / MLText / MLTextBase
    //   classes we use at `com.huawei.hms.mlsdk.text.*`).
    // * ml-computer-vision-ocr-cn-model — Chinese offline model
    //   pack.  The Latin model is bundled in the ocr AAR itself.
    //   Models download on first use via HMS Core Services; the
    //   analyzer transparently downloads on first asyncAnalyseFrame
    //   call when the cache is cold.
    //
    // Version 3.18.1.302 is the latest available on
    // developer.huawei.com/repo as of 2026-03 (confirmed via
    // maven-metadata.xml).
    implementation("com.huawei.hms:ml-computer-vision-ocr:3.18.1.302")
    implementation("com.huawei.hms:ml-computer-vision-ocr-cn-model:3.18.1.302")
}
