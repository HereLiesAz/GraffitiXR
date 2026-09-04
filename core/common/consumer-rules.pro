# Consumer Proguard Rules for core:common
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# GraffitiJNI.cpp (in :core:nativebridge) builds Fingerprint instances directly via the frozen
# static factory Fingerprint.fromNative (see Fingerprint.JNI_FACTORY_DESCRIPTOR). R8 has no
# reachability edge for that JNI call, so without this it strips the factory/ctor and
# GetStaticMethodID returns null at runtime ("JNI DETECTED ERROR IN APPLICATION: mid == null") —
# this binding has broken this way before. Declared here (the module Fingerprint actually lives in)
# rather than only in the app's own proguard-rules.pro, so it travels with the class.
-keep class com.hereliesaz.graffitixr.common.model.Fingerprint { *; }
-keepclassmembers class com.hereliesaz.graffitixr.common.model.Fingerprint {
    <init>(...);
    public static ** fromNative(...);
}

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
