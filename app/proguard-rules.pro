# Keep ML Kit classes safe if R8/ProGuard is enabled later
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-dontwarn com.google.mlkit.**
