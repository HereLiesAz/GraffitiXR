# Add project specific ProGuard rules here.

# OpenCV keep rules to prevent UnsatisfiedLinkError
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** {
    native <methods>;
}
-dontwarn org.opencv.**

# Keep ARCore
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**

# Keep SlamManager JNI methods (CRITICAL FIX)
-keep class com.hereliesaz.graffitixr.slam.** { *; }
-keepclassmembers class com.hereliesaz.graffitixr.slam.** {
    native <methods>;
}

# General native method keep
-keepclasseswithmembernames class * {
    native <methods>;
}

# Serialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}