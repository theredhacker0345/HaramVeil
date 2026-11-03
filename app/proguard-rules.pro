# ONNX Runtime - the dependency coordinates are com.microsoft.onnxruntime:onnxruntime-android
# but the Java packages are ai.onnxruntime.** — both are kept. The engines resolve session
# metadata reflectively (OrtSession.getMetadata / getCustomMetadataMap), which R8 cannot see,
# so the whole Java surface must survive shrinking.
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# ML Kit - the mlkit flavor drives it entirely via reflection, so R8 cannot trace usage.
-keep class com.google.mlkit.** { *; }

# DataStore - Keep preferences classes
-keep class androidx.datastore.** { *; }

# Kotlin reflection if used
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses

# Protobuf which ONNX Runtime depends on
-keep class com.google.protobuf.** { *; }

# Keep R class
-keepclassmembers class **.R$* { public static <fields>; }

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
