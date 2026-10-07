# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.feedvibe.app.**$$serializer { *; }
-keepclassmembers class com.feedvibe.app.** { *** Companion; }
-keepclasseswithmembers class com.feedvibe.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Credential Manager / Google Identity
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Puente JavaScript del reproductor de YouTube
-keepclassmembers class com.feedvibe.app.ui.PlayerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
