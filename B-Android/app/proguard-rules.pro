# WebView JavaScript entrypoints.
-keepclassmembers class com.nevus.quetta.BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# JNI symbols referenced by the native guard layer.
-keep class com.nevus.quetta.NativeGuard { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# WorkManager can instantiate workers by class name across process recreation.
-keep class com.nevus.quetta.download.HlsVodDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room database implementations and schema metadata.
-keep class * extends androidx.room.RoomDatabase { *; }
