# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class app.kosh.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.kosh.**$$serializer { *; }

# OkHttp / BouncyCastle optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
