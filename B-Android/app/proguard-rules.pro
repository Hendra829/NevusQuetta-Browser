# AUDIT-REPORT.md A-PG-01: aturan lama menyebut class com.nevus.quetta.BrowserBridge
# yang sudah tidak ada (dihapus saat migrasi ke androidx.webkit WebMessageListener),
# sehingga tidak melindungi apa pun dan menyembunyikan asumsi lama bahwa bridge
# masih memakai addJavascriptInterface. Bridge sekarang TIDAK memakai
# addJavascriptInterface sama sekali; tidak ada entrypoint JS yang perlu dijaga.
# Bila addJavascriptInterface ditambahkan kembali di masa depan, aturan keep harus
# ditulis ulang dan disertai pengujian rilis ter-minify.

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
