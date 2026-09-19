# NevusQuetta V0.9A+B hardening — 19 Sep 2026

Branch kandidat: `feat/v09ab-hardening`  
PR: #2 → base `feat/v09ab`  
Package: `com.nevus.quetta`  
Version: `0.9.0-ab` (`versionCode 900`)  
Target/compile SDK: 36 · minSdk: 26

## Gate terbaru

| Gate | Status | Bukti |
|---|---|---|
| Secret scan | PASS | GitHub Actions run #45 |
| Clean | PASS | GitHub Actions run #45 |
| Unit tests | PASS | GitHub Actions run #45 |
| Lint debug | PASS | GitHub Actions run #45 |
| Assemble debug APK | PASS | GitHub Actions run #45 |
| APK checksum | PASS | `136fc812588c09858f34c8e4ab182f4d950cb7d93fd9133af1c23ff9151aa6c4` |
| Artifact upload | BLOCKED | GitHub Actions storage quota penuh |
| Runtime API 35 | NOT RUN | Memerlukan perangkat/emulator |
| Runtime API 36 | NOT RUN | Memerlukan perangkat/emulator |

## Runtime yang sekarang terhubung

- HTTPS-first NavigationController + IDN/scheme filtering.
- Secure WebView settings + origin-bound WebMessage media bridge.
- Room repository untuk bookmark, history, dan session tab.
- Multi-tab state machine dengan WebView runtime terpisah per tab.
- Private tab menggunakan AndroidX WebKit MULTI_PROFILE bila tersedia; jika tidak tersedia mode private ditolak, bukan dipalsukan.
- DownloadCoordinator HTTPS dengan cookie sesi dan referrer privacy policy.
- Media discovery HTML5/CDN/HLS/DASH/common media request tanpa bypass DRM/auth.
- DataVault AES-GCM/Android Keystore dengan snapshot key thread-safe dan corrupt-data handling.
- Cleanup hanya pada cache/code-cache yang diizinkan.
- NativeGuard menggunakan immutable hash snapshot/domain suffix lookup pada read path.
- Edge-to-edge system insets untuk target API 36.
- Termux build diarahkan ke branch hardening dan menjalankan clean + unit + lint + assemble.

## Tombol/UI aktif

Back · Forward · Home · Address/Search · Reload · Bookmark · Tab counter/switcher · New Tab · New Private Tab · History · Close Active Tab · Menu · Media Download confirmation · Clear History · Clear Session · Vault/Lock Vault.

## Lock tahap

V0.9A+B belum ditutup sampai runtime API 35 dan API 36 dibuktikan.  
V0.9C+D dan Chromium M01–M04 tetap terkunci sampai gate runtime V0.9A+B PASS.
