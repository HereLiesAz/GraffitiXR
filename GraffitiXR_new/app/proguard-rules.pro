# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/jules/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep rules here:

# If you use reflection, typically to load classes dynamically, you need to
# tell ProGuard what not to remove.
# -keep class com.your.package.YourClass
# -keep class com.your.package.YourClass {
#   public <init>();
# }

# If you use JNI(Java Native Interface), you might want to keep
# all native class names.
# -keepclasseswithmembernames class * {
#     native <methods>;
# }

# If you use an enum, you should probably keep the default constructor.
# -keepclassmembers enum * {
#     public static **[] values();
#     public static ** valueOf(java.lang.String);
# }

# If you use serialization, you might want to keep all fields.
# -keepclassmembers class * implements java.io.Serializable {
#     static final long serialVersionUID;
#     private static final java.io.ObjectStreamField[] serialPersistentFields;
#     private void writeObject(java.io.ObjectOutputStream);
#     private void readObject(java.io.ObjectInputStream);
#     java.lang.Object writeReplace();
#     java.lang.Object readResolve();
# }