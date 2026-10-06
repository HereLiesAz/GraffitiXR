# Consumer ProGuard rules for :core:nativebridge.
#
# These previously lived only in `proguard-rules.pro`, which a *library* module's `proguardFiles`
# only applies when the library itself is minified (it isn't — isMinifyEnabled = false here). With
# no `consumerProguardFiles` declaration, these rules never reached a minified app's R8 run; release
# builds survived only because app/proguard-rules.pro happened to duplicate them by hand.

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.hereliesaz.graffitixr.nativebridge.SlamManager { *; }

# GraffitiJNI.cpp resolves org.opencv.core.KeyPoint's constructor by name via JNI reflection
# (FindClass + GetMethodID for the 7-arg ctor) to build List<KeyPoint> results. R8 has no
# reachability edge for that JNI lookup, so without this a minified consumer app that doesn't
# separately declare this rule would strip the constructor and GetMethodID would return null at
# runtime ("JNI DETECTED ERROR IN APPLICATION: mid == null") -- same failure mode as the
# Fingerprint.fromNative binding documented in core/common/consumer-rules.pro.
-keep class org.opencv.core.KeyPoint { *; }
