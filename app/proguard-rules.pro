# ===================================================================
# R8 / ProGuard Rules for Jarvis AI
# ===================================================================

# Keep all Jarvis Assistant application classes, services, models, and interfaces
-keep class com.jarvis.assistant.** { *; }
-keepclassmembers class com.jarvis.assistant.** { *; }
-keep interface com.jarvis.assistant.** { *; }

# Keep attributes required for reflection, annotations, and generic signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep classes annotated with @Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep Android view binding & data binding classes
-keep class com.jarvis.assistant.databinding.** { *; }
-keepclassmembers class com.jarvis.assistant.databinding.** { *; }

# OkHttp & Okio rules for network downloading & API calls
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }

# JSON parsing
-keep class org.json.** { *; }
-keepclassmembers class org.json.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }

# Media & Audio
-keep class android.media.** { *; }
-keep class androidx.media.** { *; }

# Firebase & Google Play Services
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
