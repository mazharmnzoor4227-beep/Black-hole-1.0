# youtubedl-android uses reflection/Jackson/native wrappers that release R8 can otherwise strip.
# Keep the full wrapper namespace and Apache ZIP classes used by the embedded runtime.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.* { *; }
-keepattributes *Annotation*
