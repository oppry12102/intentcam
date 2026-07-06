plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pure-JVM JSON.  Matches the org.json.* API the Kotlin sources
    // were written against; on Android, org.json is part of the
    // platform, but on plain JVM we need an explicit dependency.
    implementation("org.json:json:20240303")

    // Coroutines — ToolUseLoop is suspend-based.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // OkHttp — LlmClient uses it for HTTP + SSE.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}

// The eval is a Kotlin `main()` function in this module.  Build and
// run it via `gradle :shared:eval` (registered below).
tasks.register<JavaExec>("eval") {
    description = "Run the eval (calls the real ToolUseLoop + LlmClient)."
    group = "verification"
    mainClass.set("com.example.intentcam.eval.EvalMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Run from the project root so the default relative paths
    // ("profiling/...", "img/rctw") resolve.  Otherwise gradle
    // JavaExec defaults to the subproject's directory (shared/) and
    // the eval aborts with "missing ground truth".
    workingDir = rootDir
    // Default to 20 fixtures, matches the previous Python default.
    args = listOf("--limit", "20")
}