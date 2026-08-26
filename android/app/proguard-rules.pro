# Kotlin serialization (Retrofit DTOs)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nuva.assistant.**$$serializer { *; }
-keepclassmembers class com.nuva.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.nuva.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines (R8 safety net; the library ships rules but keep this explicit)
-dontwarn kotlinx.coroutines.**

# Room generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
