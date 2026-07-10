# ---- OkHttp / okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- Huawei HMS ML Kit (OCR) + AGConnect ----
# HMS loads model/backend classes and reads agconnect-services.json via
# reflection, so R8 must not rename/strip them or the OCR backend fails
# to init at runtime (impl == null, ocr_hit=false on every frame).
-keep class com.huawei.hms.** { *; }
-keep class com.huawei.hmf.** { *; }
-keep class com.huawei.agconnect.** { *; }
-keep interface com.huawei.hms.** { *; }
-dontwarn com.huawei.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Native method bindings (ML Kit .so entry points).
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- Kotlin coroutines ----
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
