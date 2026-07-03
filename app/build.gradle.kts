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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // On-device OCR.  Latin + Chinese bundled models, ~10 MB total.
    // Used as ground-truth text hints fed into the model prompts.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // On-device object detection with classification. Adds ~5 MB to the APK
    // and gives the model a "what is this object" hint (ImageNet-style labels)
    // to ground its scene understanding.
    implementation("com.google.mlkit:object-detection:17.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
