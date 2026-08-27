# ---------------------------------------------------------------------------------------------
# karoo-weather R8 rules.
#
# Release builds set isMinifyEnabled = true. A stripped serializer or a stripped Glance layout
# class fails only at runtime, only in release, and usually only on the rider's device — so these
# rules are deliberately generous rather than minimal.
# ---------------------------------------------------------------------------------------------

# --- Karoo SDK ---------------------------------------------------------------------------------
# The SDK serialises its models across the AIDL Binder reflectively, both directions.
-keep class io.hammerhead.karooext.** { *; }
-keep,includedescriptorclasses class io.hammerhead.karooext.**$$serializer { *; }
-keepclassmembers class io.hammerhead.karooext.** {
    *** Companion;
}
-keepclasseswithmembers class io.hammerhead.karooext.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Our extension service and activity are named from the manifest.
-keep class io.github.glandais.karoo.weather.WeatherExtension { *; }
-keep class io.github.glandais.karoo.weather.MainActivity { *; }

# --- kotlinx.serialization ---------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Our own @Serializable DTOs (WeatherSettings, the Open-Meteo response, the forecast cache).
-keep,includedescriptorclasses class io.github.glandais.karoo.weather.**$$serializer { *; }
-keepclassmembers class io.github.glandais.karoo.weather.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.glandais.karoo.weather.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Coroutines --------------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# --- Glance / RemoteViews ----------------------------------------------------------------------
# Glance resolves its generated layouts and its RemoteViews translators reflectively; stripping
# them turns every graphical data field into a blank box.
-keep class androidx.glance.** { *; }
-keep class androidx.glance.appwidget.** { *; }
-keep class androidx.glance.appwidget.protobuf.** { *; }
-keepclassmembers class * extends android.widget.RemoteViews {
    <init>(...);
}
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-dontwarn androidx.glance.**

# --- Compose -----------------------------------------------------------------------------------
-dontwarn androidx.compose.**
