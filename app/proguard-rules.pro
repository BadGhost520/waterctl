# R8 / ProGuard rules for the release build.
#
# The release build enables minification and resource shrinking:
#
#     release {
#         isMinifyEnabled = true
#         isShrinkResources = true
#         proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
#     }
#
# The app itself needs no keep rules: there is no reflection, no `Class.forName`, no
# serialisation and no JNI anywhere in it, and AndroidX (AppCompat, Material, core-ktx) ships
# its own consumer rules which R8 applies automatically. The entries below are the usual
# release-build hygiene rather than workarounds for anything in this code base; add a rule
# here only when something actually fails at runtime in a release build.

# Keep line numbers so a stack trace from a release build still points at the source. R8
# renames classes but keeps this attribute, which is what makes crash reports actionable.
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name in stack traces (it would otherwise leak local paths).
-renamesourcefileattribute SourceFile

# Warnings about optional dependencies that are never present at runtime on Android.
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn java.lang.instrument.IllegalClassFormatException
