# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.hereliesaz.graffitixr.nativebridge.SlamManager { *; }
