# NevusQuetta — status rilis

Diperbarui: 25 Sep 2026

Package: `com.nevus.quetta`  
Version: `0.9.0-cd-rc1` (`versionCode 910`)  
Target/compile SDK: 36 · minSdk: 26  
Situs produksi: https://nevusquetta.tech (Hostinger — *bukan* GitHub Pages)

> **Catatan kebijakan versi.** Dokumen ini sebelumnya menyatakan `0.9.0-ab`
> (`versionCode 900`) sementara `B-Android/app/build.gradle.kts` sudah
> `0.9.0-cd-rc1` (`versionCode 910`). Dua nilai yang saling bertentangan membuat
> label versi tidak bisa dipercaya. Sumber kebenaran versi **hanya**
> `B-Android/app/build.gradle.kts`; seluruh tempat lain wajib mengikutinya, dan
> `B-Android/scripts/check_version_policy.sh` menegakkannya secara otomatis
> (gagal-keras bila ada yang menyimpang).

## Sumber kebenaran versi

| Tempat | Nilai | Dijaga oleh |
|---|---|---|
| `B-Android/app/build.gradle.kts` | `versionCode 910` / `0.9.0-cd-rc1` | sumber kebenaran |
| `.github/workflows/android-v09cd-release.yml` (aapt + dumpsys) | idem | `check_version_policy.sh` |
| `STATUS.md` (dokumen ini) | idem | `check_version_policy.sh` |

## Gate terbaru

### C-Chromium — terverifikasi lewat eksekusi

| Gate | Status | Bukti |
|---|---|---|
| Konfigurasi CMake (tanpa CEF) | PASS | `cmake -S C-Chromium -B build` → exit 0 |
| Build | PASS | 0 error, 0 warning (`-Wall -Wextra -Werror`) |
| `ctest` | PASS | **4/4 (100%)** — termasuk `nq_assets_test` yang sebelumnya tidak terdaftar |
| `nq_ruleset_test` | PASS | **126 pemeriksaan, 0 gagal** |
| `nq_sha512_test` | PASS | **18 pemeriksaan, 0 gagal** |
| `nq_ed25519_test` | PASS | **22 pemeriksaan, 0 gagal** |
| `nq_assets_test` (direktori kerja kosong) | PASS | **44 pemeriksaan, 0 gagal** (hermetik, identik antar run) |

### Android

| Gate | Status | Bukti |
|---|---|---|
| Secret scan | PASS | GitHub Actions |
| Clean / Unit tests / Lint / Assemble debug | **BELUM HIJAU** | Gate V0.9CD merah; akar masalah `BrowserRuntimeViewModelTest` |
| Runtime API 35 | NOT RUN | Memerlukan perangkat/emulator |
| Runtime API 36 | NOT RUN | Memerlukan perangkat/emulator |
| Build rilis bertanda tangan (APK/AAB) | NOT RUN | Memerlukan keystore + secrets |

### Situs & paket deploy

| Gate | Status | Bukti |
|---|---|---|
| Validasi paket `deploy/nevusquetta-tech/public_html/` | PASS | `validate-deploy-package` di `deploy.yml` |
| GitHub Pages | **DILEWATI (opsional)** | Repositori belum mengaktifkan Pages; situs produksi ada di Hostinger |

> **Temuan yang sudah ditutup.** `deploy.yml` sebelumnya **selalu gagal** pada
> setiap push ke `main` dengan `HttpError: Not Found` dari
> `actions/configure-pages`, semata-mata karena GitHub Pages tidak pernah
> diaktifkan. Kegagalan itu tidak ada hubungannya dengan kualitas kode dan
> membuat sinyal CI menyesatkan. Kini workflow tersebut selalu memvalidasi paket
> deploy (pekerjaan nyata) dan hanya mencoba publish Pages bila Pages benar-benar
> aktif — jika tidak, langkahnya dilewati dengan catatan jelas, bukan gagal palsu.

> **Temuan yang masih terbuka.** `.github/workflows/android-v09cd-release.yml`
> hanya dipicu oleh `workflow_dispatch` dan push ke `release/**`. Karena
> kebijakan repo adalah rilis dari `main`, workflow itu **mati** — gate build
> rilis bertanda tangan tidak pernah berjalan otomatis. Perbaikan disengaja
> ditunda sampai gate CD dan runtime benar-benar hijau, agar tidak menyalakan
> gate yang pasti merah. Jalankan manual dari tab Actions untuk menguji.

## Runtime yang sekarang terhubung

- HTTPS-first NavigationController + IDN/scheme filtering.
- Secure WebView settings + origin-bound WebMessage media bridge.
- Room repository untuk bookmark, history, dan session tab.
- Multi-tab state machine dengan WebView runtime terpisah per tab.
- Private tab memakai AndroidX WebKit MULTI_PROFILE bila tersedia; bila tidak
  tersedia, mode private **ditolak**, bukan dipalsukan.
- DownloadCoordinator HTTPS dengan cookie sesi dan referrer privacy policy.
- Unduhan terkelola: preflight range/redirect, resume, queue/progress/cancel.
- HLS VOD: parser ketat + worker non-DRM + pembersihan berkas sementara.
- Media discovery HTML5/CDN/HLS/DASH tanpa bypass DRM/auth.
- DataVault AES-GCM/Android Keystore dengan snapshot key thread-safe dan
  penanganan data rusak.
- Cleanup hanya pada cache/code-cache yang diizinkan.
- NativeGuard memakai immutable hash snapshot/domain suffix lookup pada read path.
- Edge-to-edge system insets untuk target API 36.
- State UI terpusat + debounce progres/URL (`BrowserRuntimeViewModel`).

## Kebijakan Chromium

- `C-Chromium/assets/nevus_ruleset.json` bentuk **v2** + sidecar `.sha256`.
- Kebijakan (daftar blokir, HTTPS-only, `Sec-GPC`, izin) dibaca dari berkas,
  **bukan** konstanta di kode. Terverifikasi: 56 host terblokir dari berkas.
- Pemuatan **gagal-tertutup**: berkas hilang, checksum tidak cocok, JSON rusak,
  skema asing, atau permintaan pelonggaran kebijakan → aplikasi **menolak jalan**.
- Pemasangan kebijakan hanya boleh **sekali** — tidak dapat diturunkan saat runtime.
- **Verifier Ed25519 belum berisi aritmetika kurva.** `Ed25519Verify()` selalu
  `false`, sehingga ruleset bertanda tangan **DITOLAK**. Aplikasi aman tetapi
  belum dapat memakai ruleset bertanda tangan. Kontrak lengkap ada di
  `docs/ED25519-VERIFIER-CONTRACT.md`.

## Tombol/UI aktif

Back · Forward · Home · Address/Search · Reload · Bookmark · Tab counter/switcher ·
New Tab · New Private Tab · History · Close Active Tab · Menu · Media Download
confirmation · Clear History · Clear Session · Vault/Lock Vault.

## Lock tahap

V0.9C+D belum boleh ditandai rilis sampai gate Android hijau **dan** runtime API
35/36 dibuktikan di perangkat/emulator. Chromium M01–M04 tetap terkunci sampai
keduanya selesai.
