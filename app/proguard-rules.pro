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

# OkHttp: warnings only; OkHttp does not rely on broad reflection
-dontwarn okhttp3.**
-dontwarn okio.**

# JSoup: keep default constructors only; parsing does not require reflection
-keep class org.jsoup.** { <init>(); }

# ML Kit: keep default constructors only
-keep class com.google.mlkit.** { <init>(); }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { <init>(); }

# TFLite / LiteRT: keep default constructors only
-keep class org.tensorflow.** { <init>(); }
-keep class com.google.ai.edge.litert.** { <init>(); }
-keep class com.google.android.gms.tflite.** { <init>(); }

# AndroidX Security Crypto: keep default constructors only
-keep class androidx.security.crypto.** { <init>(); }

# WebView JavaScript Interface
-keepclassmembers class top.hsyscn.opedrgent.network.WebViewAgent$JsBridge {
    public *;
}

# Keep data classes used in serialization: constructors and fields only
-keepclassmembers class top.hsyscn.opedrgent.model.** { <init>(); <fields>; }
-keepclassmembers class top.hsyscn.opedrgent.network.WebSearchResult { <init>(); <fields>; }
-keepclassmembers class top.hsyscn.opedrgent.network.WebFetchResult { <init>(); <fields>; }
-keepclassmembers class top.hsyscn.opedrgent.network.MapTileFetcher$MapResult { <init>(); <fields>; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
