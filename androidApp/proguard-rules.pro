# Keep ExifInterface attributes & constructor
-keep class androidx.exifinterface.media.ExifInterface { *; }
-keepclassmembers class androidx.exifinterface.media.ExifInterface { *; }

# Keep Compose generated resources
-keep class snapchat_memories_downloader.composeapp.generated.resources.** { *; }

# Keep kotlinx.serialization classes
-keepattributes *Annotation*,Signature,InnerClasses
-dontwarn kotlinx.serialization.**
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    *** INSTANCE;
}
