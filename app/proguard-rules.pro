# Gson models are read by reflection
-keep class com.cabfusion.app.data.net.** { *; }
-keep class com.cabfusion.app.data.model.** { *; }
-keepattributes Signature, *Annotation*
# osmdroid
-dontwarn org.osmdroid.**
-keep class com.cabfusion.app.data.LocalBackend$* { *; }
