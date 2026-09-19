# NevusQuetta V0.1

Implementasi browser Android mandiri (clean-room), bukan salinan kode atau merek Quetta.

## Tiga bahasa utama

1. Kotlin: lifecycle Android, WebView, navigasi, izin dan unduhan.
2. JavaScript: deteksi elemen media pada halaman yang mengizinkan akses.
3. C++17/JNI: pemeriksaan host pelacak berbiaya rendah.

XML hanya dipakai untuk layout dan resource Android.

## Build di Termux

Syarat: JDK 17, Android SDK API 35, NDK, CMake 3.22.1, dan Gradle 8.9.

```sh
cd NevusQuetta
gradle --no-daemon clean assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Batas V0.1

- Browser satu tab, address/search bar, back, reload, HTTPS-first.
- JavaScript dan DOM storage aktif; file access, cleartext, mixed content, dan cookie pihak ketiga dimatikan.
- DownloadManager hanya menerima URL HTTPS yang diberikan situs.
- Pemblokiran host dasar melalui C++; daftar kecil ini bukan pengganti filter list terverifikasi.
- Belum ada engine Chromium fork, sinkronisasi akun, ekstensi Chrome, DRM bypass, atau pengunduhan media yang dilarang situs.

## Audit wajib sebelum rilis

Jalankan `clean`, `assembleDebug`, `lintDebug`, instal APK, lalu uji navigasi, rotasi, back gesture API 35/36, unduhan HTTPS, pemutaran video, penghapusan sesi, lifecycle, dan kebocoran resource.
