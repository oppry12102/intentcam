plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.intentcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.intentcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Limit native libs to phone ABIs.  x86 / x86_64 are emulator-only
        // and would add ~25 MB of unused libmlkit_google_ocr_pipeline.so.
        // arm64-v8a + armeabi-v7a covers ~all real devices.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // ML Kit on-device OCR (Chinese + Latin).  Bundled — no Google Play
    // Services dependency, ~5 MB APK overhead.  Drives the `read_text`
    // tool so the model gets verbatim text instead of paraphrasing it
    // (was the main r2_text regression in the 100-fixture eval).
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
