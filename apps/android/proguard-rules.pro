# R8 shrinks and optimizes, but keeps names: crash traces from the owner's phone stay readable, and the baseline
# profile (collected from the non-minified `profiling` build) matches the code as written.
-dontobfuscate

# sherpa-onnx's native code reads its Kotlin config classes field by field and builds result objects by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
