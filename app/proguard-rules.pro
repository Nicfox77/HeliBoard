# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# The embedded Parakeet worker invokes these callbacks by name through JNI, while
# ParakeetNativeContext also serves as the Android Context used by the model loader.
# R8 cannot see those native call sites, so retain the bridge and all of its members.
-keep class helium314.keyboard.latin.ParakeetNativeContext { *; }

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
