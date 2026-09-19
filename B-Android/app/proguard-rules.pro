# WebView JavaScript entrypoints.
-keepclassmembers class com.nevus.quetta.BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# JNI symbols referenced by the native guard layer.
-keep class com.nevus.quetta.NativeGuard { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# WorkManager reconstructs workers by class name after process recreation.
-keep class com.nevus.quetta.download.ResumableDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.nevus.quetta.download.HlsVodDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room database implementation metadata must survive shrinking.
-keep class * extends androidx.room.RoomDatabase { *; }
