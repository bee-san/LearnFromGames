# Native method names are kept by the default rules, which is what ties lfg_jni.cpp to
# Hoshidicts, Vocr, ScreenAi and AnimatedWebp.

# ONNX Runtime's native side reads and builds these classes by name.
-keep class ai.onnxruntime.** { *; }

# Line numbers in a crash report from a handheld are worth more than the bytes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
