# Retrofit is not used; OkHttp + kotlinx.serialization only.
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization keeps generated serializers via companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ai.retrobot.** {
    *** Companion;
}
-keepclasseswithmembers class ai.retrobot.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Keep enum values used by the manifest / navigation.
-keepclassmembers enum ai.retrobot.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
