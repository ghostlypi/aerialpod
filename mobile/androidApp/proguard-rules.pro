# kotlinx.serialization keeps its generated serializers via companion objects
# that R8 cannot see are used. Losing one turns every snapshot into a
# SerializationException at runtime and nowhere else.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.aerialpod.** {
    *** Companion;
}
-keepclasseswithmembers class org.aerialpod.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.aerialpod.**$$serializer { *; }

# SQLDelight's generated schema is reached reflectively by the Android driver.
-keep class org.aerialpod.core.db.** { *; }

# OkHttp / Okio ship their own consumer rules; these silence the platform
# classes they reference on JVMs we never run on.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
