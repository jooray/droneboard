# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# FUTO Swipe JNI (libdroneswipe.so binds to this class name)
-keep class helium314.keyboard.latin.swipe.FutoSwipe { *; }

# Settings export/import (SettingsTransfer) finds pref keys and their default types by reflection
-keepclassmembers class helium314.keyboard.latin.settings.Settings { public static final java.lang.String PREF*; }
-keepclassmembers class helium314.keyboard.latin.settings.DebugSettings { public static final java.lang.String PREF*; }
-keepclassmembers class helium314.keyboard.latin.settings.Defaults { static *** PREF*; }
