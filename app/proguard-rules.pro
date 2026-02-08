# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in 'proguard-android-optimize.txt' (part of the SDK).

# --- OpenCV ---
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** {
    native <methods>;
}
-dontwarn org.opencv.**

# --- ARCore ---
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

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