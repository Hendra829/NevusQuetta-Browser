# Catatan hardening 2026-09-25

Merekam hasil review **PR #4** dan bukti eksekusi nyata untuk perbaikan di branch
`hardening/main-ci-ctest-actions`.

> Disimpan sebagai berkas karena tool komentar PR tidak tersedia di lingkungan
> agen, dan PR #4 sudah di-merge sehingga base/body-nya sudah tidak dapat diubah.

## 1. Status PR #4 — sudah MERGED

| Bidang | Nilai |
|---|---|
| Judul | V0.9C+D: managed downloads, media hardening, release |
| base | `feat/v09ab` (bukan `main`) |
| head | `feat/v09cd` @ `5c0f2d4` |
| state | **merged** |
| merged_at | 2026-09-24T17:26:30Z |
| merge_commit | `f8f5783` |
| mergeable_state | `unknown` |
| commits / berkas | 4 · 33 |
| additions / deletions | +3.389 / −98 |
| CI gate | `host-gate` ×2, `runtime-api-35` ×2, `runtime-api-36` ×2 — **6 kegagalan** |

Review `pullrequestreview-5307689329` **bukan review teknis**: itu pesan bot
Copilot berisi pemberitahuan batas kuota, tanpa temuan kode. Tidak ada review
manusia dan tidak ada komentar percakapan di PR #4.

Karena PR sudah merged, temuan tidak dapat ditutup dengan mengubah base atau
mengedit body. Perbaikan dikirim sebagai branch + PR baru terhadap `main`.

## 2. Akar kegagalan gate (dibuktikan dari log job asli)

Log job `107749442186` (Android V0.9AB Gate, run `36033996199`):

```
BrowserRuntimeViewModelTest > progressIsDebouncedAndLateProgressCannotRegressFinishedPage FAILED
    java.lang.AssertionError at BrowserRuntimeViewModelTest.kt:56
37 tests completed, 1 failed
```

Langkah **`Unit tests`** (`./gradlew --no-daemon testDebugUnitTest`) gagal setelah
94 detik (exit 1). Karena langkah itu gagal, langkah **berikutnya di-SKIP**:
`Lint`, `Assemble debug APK`, `Record checksum`. Akibatnya APK tidak pernah
terbentuk, dan kegagalan sebenarnya tersembunyi di balik galat langkah unggah:

```
No files were found with the provided path: B-Android/app/build/outputs/apk/debug/app-debug.apk
```

Enam pemeriksaan gate merah, tetapi sebab aslinya **tidak terlihat** dari
ringkasan CI. Merge commit dibuat 25 detik SEBELUM gate selesai gagal.

Catatan: `Deploy Site` pada `main` juga berstatus `failure`; log-nya tidak
diunduh karena butuh hak admin. Tidak diklaim sebagai terverifikasi.

## 3. Bukti eksekusi — C-Chromium (cmake + ctest + g++ -Werror)

Otodidak diuji pada clone bersih `C-Chromium/`.

| Uji | Sebelum | Sesudah |
|---|---|---|
| Konfigurasi + build CMake | 0 error, 0 warning | 0 error, 0 warning |
| `ctest` | **3/3** (100%) | **4/4** (100%) |
| Biner terbangun | 3 (`nq_assets_test` **tidak ada**) | 4 |
| `nq_assets_test` dari direktori kosong | **42 pemeriksaan, 7 gagal, exit 1** | **44 pemeriksaan, 0 gagal, exit 0** |
| Hermetisme (2 run berturut) | — | identik, 44/0 |

Akar masalah harness: `tmp_dir = "nq-assets-tmp"` **tidak pernah dibuat**. Pada
clone bersih seluruh penulisan gagal, `WriteFile()` mengembalikan `false`
**tanpa dilaporkan**, dan uji jalur 4b–4g melaporkan status generik. Harness itu
juga "lulus" hanya bila ada sisa direktori dari run sebelumnya — tidak hermetik.
Selain itu cabang 4b menaikkan `g_fail` tanpa `g_total`, sehingga jumlah akhir
berbeda antar run (42 vs 43).

Kode produksi (`nq_ruleset`, `nq_bootstrap`, `nq_privacy_policy`) terbukti BENAR.

## 4. Verifikasi workflow

`actionlint` pada seluruh 7 berkas workflow yang disunting: **exit 0**.

Seluruh **32** rujukan aksi kini ter-pin ke commit SHA 40 karakter — **0 tag
mengapung**. `setup-java` dimigrasikan `v4.9.1` → `v5.0.0`
(`dded0888837ed1f317902acf8a20df0ad188d165`) di 5 workflow.

## 5. Batasan yang tidak diklaim

- **Gate Android TIDAK diselesaikan.** JDK 17 Temurin + SDK android-36 +
  build-tools 36.0.0 berhasil dipasang, tetapi `testDebugUnitTest` di sandbox
  gagal karena Gradle daemon crash ("daemon disappeared"), sehingga kegagalan uji
  yang sama **tidak dapat direproduksi secara lokal**. Satu-satunya bukti adalah
  log CI di atas.
- **APK/AAB, 75+ unit test Android, lint, dan assembleRelease/bundleRelease
  belum pernah dijalankan.**
- **Build CEF penuh tidak dijalankan** — sandbox hanya 8,0 GiB, syaratnya ≥100 GiB.
- **Verifier Ed25519 masih mengembalikan `false`**, sehingga ruleset `signed:true`
  ditolak (aman, tetapi belum dapat dipakai).

## 6. Belum ditangani

- `BrowserRuntimeViewModelTest.kt:56` — uji yang gagal belum diperbaiki; root
  cause di kode produksi belum ditemukan.
- Kebijakan versi: `0.9.0-cd-rc1` / `versionCode 910` sudah ada di `main` padahal
  gate V0.9C+D merah.
