# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.macrophage.barspeed.**$$serializer { *; }
-keepclassmembers class com.macrophage.barspeed.** { *** Companion; }
-keepclasseswithmembers class com.macrophage.barspeed.** { kotlinx.serialization.KSerializer serializer(...); }
