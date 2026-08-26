# Karoo Extension
-keep class io.hammerhead.karooext.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.util.KtorDsl
-dontwarn io.ktor.utils.io.core.ByteReadPacket
-dontwarn io.ktor.utils.io.core.Input
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.glandais.karoo.weather.**$$serializer { *; }
-keepclassmembers class io.github.glandais.karoo.weather.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.glandais.karoo.weather.** {
    kotlinx.serialization.KSerializer serializer(...);
}

