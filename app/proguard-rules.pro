# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in 'proguard-android-optimize.txt' (part of the SDK).

# --- AzNavRail ---
# Keep all classes in the aznavrail model package to prevent NoClassDefFoundError
-keep class com.hereliesaz.aznavrail.model.** { *; }
-keep class com.hereliesaz.aznavrail.annotation.** { *; }

# --- OpenCV ---
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** {
    native <methods>;
}
-dontwarn org.opencv.**

# --- ARCore ---
# Keep all classes and interfaces in the com.google.ar.core package as-is.
# Many of these are obfuscated internally (e.g., com.google.ar.core.x)
# and their bytecode has stack map tables that are easily corrupted
# by aggressive optimizations in AGP 9.1.0's R8/D8.
-keep class com.google.ar.core.** { *; }
-keep interface com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# Disable the new AGP 9.1.0 default repackaging and enum unboxing behaviors
# which break the ARCore JNI layer and cause "Constructor mismatch" errors.
-dontrepackage
-keepattributes Code,StackMapTable
-keepclassmembers enum com.google.ar.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <init>(java.lang.String, int);
}

# --- Native Engine (MobileGS & SlamManager) ---
# Keep the JNI wrapper class and its native methods
-keep class com.hereliesaz.graffitixr.nativebridge.SlamManager { *; }
-keepclassmembers class com.hereliesaz.graffitixr.nativebridge.SlamManager {
    native <methods>;
}

# General Safety for Native Methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Serialization ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.AndroidDispatcherFactory {
    <init>();
}

# --- Telemetry & Privacy Hardening ---
# Silence debug and verbose logs in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
