# ---------------------------------------------------------------------------
# R8 (full mode). Built with proguard-android-optimize.txt + the rules below.
# ---------------------------------------------------------------------------

# Keep readable stack traces in crash reports (mapping.txt is uploaded by the workflow)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hardening + size: strip debug/verbose logging (warnings and errors stay so CrashLog keeps its context), flatten package names, let R8 widen access to inline more
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

# Tesseract (JNI)
-keep class com.googlecode.tesseract.android.TessBaseAPI { *; }
-keep class com.googlecode.tesseract.android.TessBaseAPI$* { *; }
-keep class com.googlecode.tesseract.android.ResultIterator { *; }
-keep class com.googlecode.tesseract.android.PageIterator { *; }
-keep class com.googlecode.tesseract.android.TessPdfRenderer { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.google.mlkit.**

# Media3 loads its effect / video-graph classes by reflection; without these the editor preview and export ignore every edit
-keep class androidx.media3.effect.** { *; }
-keep class androidx.media3.transformer.** { *; }
-keep class androidx.media3.exoplayer.video.** { *; }
-keep class androidx.media3.exoplayer.ExoPlayerImpl { *; }
-dontwarn androidx.media3.**

# BlurView (vendored module) uses no reflection, so R8 may shrink and inline it freely (no blanket keep needed)

# Tesseract: JNI reads/writes these fields and calls these methods by name; everything else may be shrunk
-keepclasseswithmembernames class * { native <methods>; }

# Live Text language data is read from assets/tessdata by path, never by resource id
# ML Kit / Play Services tasks ship their own consumer rules; only the unavoidable optional references are silenced
-dontwarn com.google.android.gms.internal.**
