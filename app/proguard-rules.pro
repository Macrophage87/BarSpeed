# #274: neither proguard-android-optimize.txt (AGP's default release ruleset,
# confirmed against the AOSP source: android.googlesource.com/platform/sdk
# files/proguard-android-optimize.txt) nor this file previously kept source
# file names or line numbers, so a mapping.txt could not retrace an
# obfuscated release stack back to a line -- only to a class and method.
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.macrophage.barspeed.**$$serializer { *; }
-keepclassmembers class com.macrophage.barspeed.** { *** Companion; }
-keepclasseswithmembers class com.macrophage.barspeed.** { kotlinx.serialization.KSerializer serializer(...); }
