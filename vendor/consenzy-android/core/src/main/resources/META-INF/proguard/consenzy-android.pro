# Consenzy Android SDK - consumer R8/ProGuard rules for :core.
# Bundled in the jar, so R8 applies them automatically; consent-sdk-rules.pro in the SDK
# README has the same content for build setups that do not pick up META-INF/proguard.

# kotlinx.serialization: keep generated serializers of the SDK's models.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class in.onfinance.consent.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class in.onfinance.consent.**$$serializer { *; }
-keep class in.onfinance.consent.core.ActingAs$Serializer { *; }
-keep class in.onfinance.consent.core.ConsentStatus$Serializer { *; }
-keep class in.onfinance.consent.core.RequestType$Serializer { *; }
-keep class in.onfinance.consent.core.RequestStatus$Serializer { *; }
-keep class in.onfinance.consent.core.Language$Serializer { *; }

# Public exception types are matched by apps in catch blocks / crash reporting.
-keep class in.onfinance.consent.core.ConsentException { *; }
-keep class * extends in.onfinance.consent.core.ConsentException { *; }

# OkHttp ships its own rules; these silence optional platform warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
