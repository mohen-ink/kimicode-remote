# ML Kit（含反射与 Dynamite 动态加载，需整包保留）
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# kotlinx-coroutines-play-services (Task.await)
-keep class kotlinx.coroutines.tasks.** { *; }
