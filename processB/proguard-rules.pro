# Keep AIDL-generated classes
-keep class com.example.toolhub.** { *; }
-keep interface com.example.toolhub.** { *; }

# Keep service and receiver components
-keep class com.example.toolhub.ToolHubService { *; }
-keep class com.example.toolhub.BootCompletedReceiver { *; }
