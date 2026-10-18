# Proguard rules for OpenTV Launcher
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
