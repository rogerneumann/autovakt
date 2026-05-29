# Hilt — keep generated component classes
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Room — keep DAO interfaces and entity classes so generated code resolves at runtime
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers @androidx.room.Dao class * { *; }

# Enum valueOf() — used to deserialize PowertrainType and DeviceCapability from JSON strings
-keepclassmembers enum com.rogerneumann.autovakt.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Car App Library — host resolves service classes by name via manifest
-keep class androidx.car.app.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep AutoVakt service classes (Android resolves these by name from the manifest)
-keep class com.rogerneumann.autovakt.auto.AutoVaktCarAppService { *; }
-keep class com.rogerneumann.autovakt.media.AutoVaktMediaBrowserService { *; }
-keep class com.rogerneumann.autovakt.service.OBD2ForegroundService { *; }
-keep class com.rogerneumann.autovakt.media.AutoVaktNotificationListener { *; }
