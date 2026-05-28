# === Opedrgent ProGuard Rules ===

# Keep line number info for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class top.hsyscn.opedrgent.**$$serializer { *; }
-keepclassmembers class top.hsyscn.opedrgent.** {
    *** Companion;
}
-keepclasseswithmembers class top.hsyscn.opedrgent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# JSoup
-keep class org.jsoup.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }

# TFLite / LiteRT
-keep class org.tensorflow.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.android.gms.tflite.** { *; }

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }

# WebView JavaScript Interface
-keepclassmembers class top.hsyscn.opedrgent.network.WebViewAgent$JsBridge {
    public *;
}

# Keep data classes used in serialization
-keep class top.hsyscn.opedrgent.model.** { *; }
-keep class top.hsyscn.opedrgent.network.WebSearchResult { *; }
-keep class top.hsyscn.opedrgent.network.WebFetchResult { *; }
-keep class top.hsyscn.opedrgent.network.MapTileFetcher$MapResult { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
