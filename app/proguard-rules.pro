# The Consenzy SDK ships its own R8 rules (core: META-INF/proguard/consenzy-android.pro, ui: consumer-rules.pro).
# Keep the kotlinx.serialization serializers of this app's own DTOs (session server responses, demo fixtures).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.example.consenzy.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.consenzy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.example.consenzy.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
