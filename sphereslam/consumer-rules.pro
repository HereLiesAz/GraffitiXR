# Consumer rules for the public SphereSLAM API.
#
# The native JNI bridge itself is protected by :core:nativebridge's native-method rules. Keep the
# small SphereSLAM facade package as well so release R8 cannot discard/rename the standalone session
# contract that is used to rebuild KPM state after process/project restoration.
-keep class com.hereliesaz.sphereslam.** { *; }
