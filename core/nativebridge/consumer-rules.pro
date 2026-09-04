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
