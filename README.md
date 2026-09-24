# NevusQuetta-Browser

Browser Peramban Aplikasi.

## Build, Perbaikan, dan Pengembangan

Repository ini sudah disiapkan dengan alur otomatis untuk:
- Build dan quality check pada setiap push/pull request.
- Menjalankan test/build Node.js jika `package.json` tersedia.
- Validasi dasar agar struktur repository tetap sehat.

## Deploy

Deploy menggunakan GitHub Pages melalui workflow `Deploy Site`.
- Trigger otomatis saat push ke branch `main` atau `master`.
- Bisa dijalankan manual lewat `workflow_dispatch`.
- Halaman deploy dibuat dari konten `README.md` sebagai baseline dokumentasi yang terus bisa dikembangkan.

## Fondasi awal Android native

Repositori ini juga berisi fondasi awal aplikasi Android native **NevusQuetta Browser** berbasis **Kotlin + Jetpack Compose + WebView**.

### Arsitektur singkat

- `BrowserViewModel` menjadi **single source of truth** untuk state navigasi:
  - `canGoBack`
  - `canGoForward`
  - `isLoading`
  - `currentUrl`
  - `progress`
- `NevusQuettaWebViewClient` dan `NevusQuettaWebChromeClient` hanya meneruskan event engine ke `BrowserViewModel`, sehingga callback WebView tidak mengikat UI secara langsung.
- Event `progress` dan `URL` diproses dengan `debounce` / `distinctUntilChanged` untuk menekan update berlebih saat redirect chain atau progress callback yang sangat sering. Debounce progress memakai pola *cancel-and-delay* agar tidak ada event yang hilang di buffer.
- Tombol **Reload / Stop** memakai satu handler (`onReloadStopClicked`) agar keputusan aksi selalu membaca state loading terkini dari sumber yang sama.
- Tombol back fisik device dan tombol back UI sama-sama memakai `BrowserViewModel.requestBackNavigation()` untuk menghindari duplikasi logika.

### Struktur utama

- `app/src/main/java/com/nevusquetta/browser/MainActivity.kt`
- `app/src/main/java/com/nevusquetta/browser/ui/BrowserScreen.kt`
- `app/src/main/java/com/nevusquetta/browser/viewmodel/BrowserViewModel.kt`
- `app/src/main/java/com/nevusquetta/browser/engine/NevusQuettaWebViewClient.kt`
- `app/src/main/java/com/nevusquetta/browser/engine/NevusQuettaWebChromeClient.kt`
- `app/src/main/java/com/nevusquetta/browser/model/BrowserUiState.kt`

### Cara build & test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

### Cara menjalankan

1. Buka project ini di Android Studio.
2. Sync Gradle project.
3. Jalankan target `app` ke emulator atau device Android (minSdk 24).

## Peningkatan Optimal (lanjutan)

Untuk peningkatan berikutnya, fokuskan pada:
1. Menambahkan source code aplikasi utama (frontend/backend) agar build lebih spesifik.
2. Menambahkan test otomatis sesuai stack yang dipakai.
3. Menambahkan environment production/staging terpisah bila sudah ada infrastruktur deploy tambahan.
