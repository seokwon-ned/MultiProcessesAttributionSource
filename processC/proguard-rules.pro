# Keep AIDL-generated classes
-keep class com.example.toolhub.** { *; }
-keep interface com.example.toolhub.** { *; }

# Keep plugin provider and delegates
-keep class com.example.vendor.sampleplugin.** { *; }
-keepclasseswithmembernames class com.example.vendor.sampleplugin.** { *; }
