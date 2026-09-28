# NevusQuetta — Panduan Pemasangan Tiga Bentuk Aplikasi

Repositori: <https://github.com/Hendra829/NevusQuetta-Browser>

Proyek ini menghasilkan **tiga bentuk aplikasi** dari satu basis kode. Dokumen ini
menjelaskan cara memasang/menjalankan masing-masing, langkah demi langkah.

| Bentuk | Direktori | Jenis | Status paket |
|---|---|---|---|
| **A** | `D-PWA/` | PWA / web siap-deploy | **BERHASIL DIKEMAS** — `nevusquetta-pwa-web.zip` |
| **B** | `B-Android/` | Aplikasi Android native (Kotlin + JNI C++17) | **BERHASIL DIKEMAS** — `nevusquetta-android-debug.apk` |
| **C** | `C-Chromium/` | Aplikasi desktop Chromium (CEF) | **TERBLOKIR** — butuh CEF + ≥100 GiB; skrip VPS disediakan |

> **Pemetaan direktori dikonfirmasi dari isi repositori**, bukan diasumsikan:
> direktori PWA bernama **`D-PWA`** (bukan `A-*`), Android **`B-Android`**,
> Chromium **`C-Chromium`**. Tidak ada direktori `A-*` di repositori.

---

## A — PWA / Web (siap-deploy)

### Isi paket

`nevusquetta-pwa-web.zip` berisi 9 berkas: `index.html`, `app.js`, `styles.css`,
`offline.html`, `service-worker.js`, `manifest.json`, dan 3 ikon
(`icons/icon-192.png`, `icons/icon-512.png`, `icons/icon-512-maskable.png`).
Direktori `tests/` **tidak** disertakan (harness pengembangan, bukan aset deploy).

### Prasyarat

- Web server statis apa pun (nginx, Apache, Caddy, GitHub Pages, Netlify, Vercel).
- **HTTPS wajib** — service worker dan pemasangan PWA hanya aktif pada origin aman
  (`https://` atau `http://localhost`).

### Langkah pemasangan

```bash
# 1. Ekstrak paket
unzip nevusquetta-pwa-web.zip -d nevusquetta-pwa

# 2. Salin ke document root web server
sudo cp -r nevusquetta-pwa/* /var/www/html/

# 3. Pastikan MIME type benar (nginx contoh)
#    application/manifest+json  -> manifest.json
#    text/javascript            -> service-worker.js
```

Contoh blok nginx:

```nginx
location = /manifest.json { types { } default_type application/manifest+json; }
location = /service-worker.js {
    types { } default_type text/javascript;
    add_header Cache-Control "no-cache";
}
```

### Memasang sebagai aplikasi

- **Android/Chrome**: buka situs → menu ⋮ → **"Instal aplikasi"** / **"Tambahkan ke layar utama"**.
- **iOS/Safari**: tombol Bagikan → **"Tambahkan ke Layar Utama"**.
- **Desktop/Chrome**: ikon instal di address bar.

### Verifikasi

```bash
node D-PWA/tests/sw-harness.mjs
# Harapan: "HASIL: 19 lulus, 0 gagal"
```

---

## B — Aplikasi Android Native

### Prasyarat toolchain

| Kebutuhan | Versi | Catatan |
|---|---|---|
| JDK | **17** | AGP 8.10.1 mensyaratkan JDK 17 |
| Android SDK | **platforms;android-36** | `compileSdk = 36`, `targetSdk = 36` |
| Build tools | **36.0.0** | |
| NDK | **27.2.12479018** | persis versi di `app/build.gradle.kts` |
| CMake | **3.22.1** | untuk `externalNativeBuild` |
| Gradle | **8.11.1** | sudah ada wrapper — tidak perlu dipasang |
| minSdk | 26 | Android 8.0+ |

### Langkah pemasangan (dari paket APK)

```bash
# 1. Pasang APK ke perangkat/emulator
adb install -r nevusquetta-android-debug.apk

# 2. Atau salin APK ke perangkat lalu buka dari File Manager
#    (aktifkan "Instal dari sumber tidak dikenal" bila diminta)
```

### Membangun sendiri dari sumber

```bash
cd B-Android
echo "sdk.dir=/path/ke/Android/Sdk" > local.properties   # jangan di-commit

# Gate lengkap (clean + unit test + lint + assembleDebug)
ANDROID_HOME=/path/ke/Android/Sdk ./scripts/run_android_gate.sh --quick

# Hanya assemble
./gradlew --no-daemon assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Build rilis (APK/AAB bertanda tangan)

Signing bersifat **gagal-tertutup**: `assembleRelease`/`bundleRelease` akan
**menolak berjalan** tanpa keystore. Set keempat variabel berikut:

```bash
export NEVUS_RELEASE_STORE=/path/ke/release.jks
export NEVUS_RELEASE_STORE_PASSWORD=...
export NEVUS_RELEASE_ALIAS=...
export NEVUS_RELEASE_KEY_PASSWORD=...
./gradlew --no-daemon assembleRelease bundleRelease
```

### Verifikasi

```bash
# Unit test
./gradlew --no-daemon testDebugUnitTest

# Lint
./gradlew --no-daemon lintDebug

# Uji runtime (butuh perangkat/emulator) — TIDAK dapat dijalankan di CI tanpa perangkat
./gradlew --no-daemon connectedDebugAndroidTest
```

---

## C — Aplikasi Chromium (CEF)

### Status: TERBLOKIR di lingkungan berbatas

Build CEF memerlukan **distribusi biner CEF** dan **≥ 100 GiB ruang disk**.
Lingkungan penyusunan hanya memiliki ~3,5 GiB dan tidak ada CEF. Yang **dapat**
diverifikasi di sini adalah modul keamanan tanpa CEF (lihat di bawah).

### Prasyarat VPS

| Kebutuhan | Minimum |
|---|---|
| RAM | 16 GB (32 GB disarankan) |
| Disk kosong | **100 GB+** |
| CPU | 4 core (8 disarankan) |
| OS | Linux x86_64 |
| Paket | `cmake ≥ 3.22.1`, `ninja-build`, `git`, `python3`, `curl`, `unzip`, `bzip2`, `build-essential`, `libgtk-3-dev`, `libnss3-dev`, `libasound2-dev` |

```bash
sudo apt-get update
sudo apt-get install -y cmake ninja-build git python3 curl unzip bzip2 \
     build-essential libgtk-3-dev libnss3-dev libasound2-dev
```

### Langkah build di VPS

```bash
# Skrip siap-jalan: memeriksa prasyarat, mengunduh CEF + verifikasi SHA-1,
# mengonfigurasi, membangun, lalu memverifikasi hasilnya.
C-Chromium/scripts/build_cef_vps.sh

# Pakai CEF yang sudah ada
C-Chromium/scripts/build_cef_vps.sh --cef-root "$HOME/cef_binary_120.1.10+g6b9c1d1+chromium-120.0.6099.129"

# Build lab (mengizinkan ruleset tanpa tanda tangan)
C-Chromium/scripts/build_cef_vps.sh --lab
```

### Verifikasi modul tanpa CEF (dapat dijalankan di mana saja)

```bash
cmake -S C-Chromium -B C-Chromium/build
cmake --build C-Chromium/build -j"$(nproc)"
ctest --test-dir C-Chromium/build --output-on-failure
# Harapan: 5/5 lulus (ruleset, sha512, ed25519, jcs, assets)
```

### Verifikasi setelah build penuh

Lihat `C-Chromium/README-BUILD.md` §6. Ringkasnya: `ldd` tanpa `not found`,
seluruh berkas sumber daya CEF ada di samping biner, `--version` menampilkan
versi Chromium yang diharapkan, dan tidak ada `failed to load resources`.

---

## Ringkasan perintah cepat

```bash
# A — uji PWA
node D-PWA/tests/sw-harness.mjs

# B — gate Android
cd B-Android && ANDROID_HOME=/opt/android-sdk ./scripts/run_android_gate.sh --quick

# C — uji modul Chromium (tanpa CEF)
cmake -S C-Chromium -B C-Chromium/build && cmake --build C-Chromium/build -j4 \
  && ctest --test-dir C-Chromium/build --output-on-failure

# C — build penuh (VPS saja)
C-Chromium/scripts/build_cef_vps.sh
```

---

## Yang belum terverifikasi

- **APK rilis bertanda tangan** belum dibangun (butuh keystore; signing gagal-tertutup).
- **AAB** belum dibangun.
- **Uji runtime Android** (`connectedDebugAndroidTest`) belum dijalankan — butuh perangkat/emulator.
- **Build CEF penuh** belum pernah berhasil di lingkungan mana pun yang tersedia.
- **Ruleset produksi bertanda tangan** belum diverifikasi end-to-end.
