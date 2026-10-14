# R8 shrinks and optimizes, but keeps names: crash traces from the owner's phone stay readable, and the baseline
# profile (collected from the non-minified `profiling` build) matches the code as written.
-dontobfuscate

# ONNX Runtime's native code creates and reads its Java classes by name.
-keep class ai.onnxruntime.** { *; }
