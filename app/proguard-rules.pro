# ---------------------------------------------------------------------------
# R8 (full mode). Built with proguard-android-optimize.txt + the rules below.
# ---------------------------------------------------------------------------

# Keep readable stack traces in crash reports (mapping.txt is uploaded by the workflow)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hardening + size: strip debug/verbose logging, flatten package names, let R8 widen access to inline more
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-repackageclasses ''
-allowaccessmodification

# Kotlin null-check helpers are pure overhead in release builds (libVLC / media3 pull Kotlin in)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# libVLC talks to native code through JNI
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }

# Optional annotation / tooling classes that are referenced but not shipped
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.annotations.**
