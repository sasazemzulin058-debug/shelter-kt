# Shelter Kotlin Rewrite

## ProGuard Rules

# Hilt
-keepclasseswithmembernames class * { @dagger.* <methods>; }
-keepclasseswithmembernames class * { @javax.inject.* <methods>; }

# AIDL
-keep class net.typeblog.shelter.services.** { *; }
-keep class net.typeblog.shelter.util.ApplicationInfoWrapper { *; }
-keep class net.typeblog.shelter.util.UriForwardProxy { *; }

# Compose
-dontwarn androidx.compose.**

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
