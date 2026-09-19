# NevusQuetta-Browser

Browser Peramban Aplikasi.

## Fondasi awal Android native

Repositori ini sekarang berisi fondasi awal aplikasi Android native **NevusQuetta Browser** berbasis **Kotlin + Jetpack Compose + WebView**.

### Arsitektur singkat

- `BrowserViewModel` menjadi **single source of truth** untuk state navigasi:
  - `canGoBack`
  - `canGoForward`
  - `isLoading`
  - `currentUrl`
  - `progress`
- `NevusQuettaWebViewClient` dan `NevusQuettaWebChromeClient` hanya meneruskan event engine ke `BrowserViewModel`, sehingga callback WebView tidak mengikat UI secara langsung.
- Event `progress` dan `URL` diproses dengan `debounce` / `distinctUntilChanged` untuk menekan update berlebih saat redirect chain atau progress callback yang sangat sering.
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
