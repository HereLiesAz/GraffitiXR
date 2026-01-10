# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/jules/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# OpenCV keep rules to prevent UnsatisfiedLinkError
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** {
    native <methods>;
}
-dontwarn org.opencv.**

# Keep ARCore
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**

# Keep SphereSLAM
-keep class com.hereliesaz.sphereslam.** { *; }
-keepclassmembers class com.hereliesaz.sphereslam.** {
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
