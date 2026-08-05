# ProGuard rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.focusguard.db.** { *; }
-keep class com.focusguard.db.entity.** { *; }

# TensorFlow Lite / task-text (native + Java reflection)
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**
-keep class com.focusguard.ai.ProductivityClassifier { *; }
-keep class com.focusguard.ai.LocalTfliteProductivityClassifier { *; }

# ML Kit OCR (Path B text recognition)
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.vision.** { *; }
