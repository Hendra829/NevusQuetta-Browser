# 2026-09-29 — Verifikasi Ed25519 (libsodium), gate ctest di CI, dan gate VPS

Branch: `hardening/ed25519-libsodium-ci-ctest`
Basis: `main` @ `9e2a044`

Dokumen ini mencatat apa yang **dikerjakan dan dibuktikan lewat eksekusi**, apa
yang **terblokir beserta alasannya**, dan apa yang **belum terverifikasi**.
Tidak ada angka di sini yang tidak berasal dari perintah yang benar-benar
dijalankan.

---

## 1. Yang ditutup pada rilis ini

### 1.1 Verifikasi tanda tangan Ed25519 — dari "selalu false" menjadi nyata

**Sebelum:** `Ed25519Verify()` selalu mengembalikan `false` karena aritmetika
kurva belum ada. Setiap ruleset `"signed": true` ditolak (gagal-tertutup, benar
tetapi tidak berguna).

**Sesudah:** verifikasi memakai **libsodium** (`crypto_sign_verify_detached`,
sudah diaudit) — bukan aritmetika kurva buatan sendiri, sesuai rekomendasi
`docs/ED25519-VERIFIER-CONTRACT.md` §4.

Perubahan:

| Berkas | Perubahan |
|---|---|
| `C-Chromium/src/nq_ed25519.cpp` | Verifikasi nyata lewat libsodium; `Ed25519BackendName()`; self-test 3 vektor RFC 8032 §7.1 (positif + negatif) |
| `C-Chromium/src/nq_ed25519.h` | Status diperbarui; deklarasi `Ed25519BackendName()` |
| `C-Chromium/tests/nq_ed25519_test.cpp` | Uji backend-aware: verifikasi nyata bila libsodium ada, gagal-tertutup bila tidak |
| `C-Chromium/CMakeLists.txt` | Deteksi libsodium; `NQ_REQUIRE_LIBSODIUM`; tautkan ke target yang relevan |

**Dua mode kompilasi, keduanya diuji:**

- `NQ_HAVE_LIBSODIUM` aktif → verifikasi nyata. **25 pemeriksaan, 0 gagal.**
- tanpa libsodium → gagal-tertutup. **22 pemeriksaan, 0 gagal**, backend `none`,
  `nm` menunjukkan **0** referensi simbol sodium.

Pemeriksaan bentuk (`y < p`, `S < L`) tetap dijalankan lebih dulu di kedua mode,
sehingga malleability (RFC 8032 §8.4) ditutup eksplisit.

### 1.2 Gate ctest di CI — celah yang sebelumnya tidak ada

**Temuan:** sebelum rilis ini **tidak ada satu pun workflow yang membangun dan
menjalankan uji C++**. `ci.yml` hanya menangani proyek Node.js; workflow
`android-*.yml` hanya modul Android. Jadi klaim "ctest 4/4" dan "verifikasi
Ed25519" **tidak pernah dibuktikan oleh CI**.

**Perbaikan:** `.github/workflows/chromium-ctest.yml` — memasang libsodium,
membangun, menjalankan ctest, lalu **menegaskan backend Ed25519 benar-benar
aktif** (bukan gagal-tertutup) dan **menguji jalur gagal-tertutup** secara
terpisah. Dengan begitu "hijau" tidak bisa berarti "tidak diuji".

Validasi: YAML sah (`jobs: ['ctest']`), `actionlint` **exit 0**.

### 1.3 Temuan yang sudah tertutup di `main` (dikonfirmasi, bukan dikerjakan ulang)

| Temuan lama | Status di `main` @ `9e2a044` | Bukti |
|---|---|---|
| `nq_assets_test` belum terdaftar di CMakeLists | **SUDAH tertutup** | `add_executable(nq_assets_test …)` + `add_test(NAME nq_assets_test …)` ada |
| Harness pakai `kMissing` alih-alih status diharapkan | **SUDAH tertutup** | `Check(r.status == nq::RulesetStatus::kChecksumMissing, …)` |
| `g_fail` naik tanpa `g_total` | **SUDAH tertutup** | `Check(WriteFile(…), "menulis berkas uji sementara (sidecar)")` |
| Harness tidak hermetik | **SUDAH tertutup** | `ResetTmpDir()` + `WORKING_DIRECTORY` di `set_tests_properties` |

ctest menjalankan **4/4** suite, bukan 3/4.

---

## 2. Bukti eksekusi (mentah)

```
$ cmake -S C-Chromium -B C-Chromium/build
-- libsodium: DITEMUKAN -> verifikasi Ed25519 AKTIF (/usr/lib/x86_64-linux-gnu/libsodium.so)
--   ed25519     : libsodium (verifikasi AKTIF)
cmake exit=0

$ cmake --build C-Chromium/build -j4
build exit=0        (0 error, 0 warning dengan -Wall -Wextra -Werror)

$ ctest --test-dir C-Chromium/build --output-on-failure
1/4 Test #1: nq_ruleset_test ..................   Passed    0.02 sec
2/4 Test #2: nq_sha512_test ...................   Passed    0.00 sec
3/4 Test #3: nq_ed25519_test ..................   Passed    0.00 sec
4/4 Test #4: nq_assets_test ...................   Passed    0.01 sec
100% tests passed, 0 tests failed out of 4

$ ./C-Chromium/build/nq_ed25519_test
  backend verifikasi: libsodium
  OK    libsodium: tanda tangan RFC 8032 TEST 1 DITERIMA (verifikasi nyata)
  OK    Ed25519SelfTest() == true (backend nyata + vektor resmi lulus)
  OK    libsodium: pesan berbeda -> tanda tangan DITOLAK
  OK    libsodium: byte S diubah -> tanda tangan DITOLAK
  OK    libsodium: S >= L -> tanda tangan DITOLAK (malleability)
  ---
  25 pemeriksaan, 0 gagal

$ g++ -std=c++17 -Wall -Wextra -Werror -Isrc -o /tmp/ed_nofail \
      tests/nq_ed25519_test.cpp src/nq_ed25519.cpp      # TANPA NQ_HAVE_LIBSODIUM
  backend verifikasi: none
  OK    tanpa backend: Ed25519Verify menolak (gagal-tertutup)
  OK    tanpa backend: Ed25519SelfTest() == false -> ruleset bertanda tangan ditolak
  ---
  22 pemeriksaan, 0 gagal
$ nm -C /tmp/ed_nofail | grep -ci sodium
0

$ /tmp/actionlint .github/workflows/chromium-ctest.yml
actionlint exit=0

$ bash B-Android/scripts/test_android_gate.sh
lulus : 12
gagal : 0
HASIL : LULUS
```

---

## 3. Yang TERBLOKIR (beserta alasan dan bukti)

### 3.1 Gate Android — exit 2 (prasyarat toolchain tidak ada)

```
$ bash B-Android/scripts/run_android_gate.sh --quick
== Pemeriksaan prasyarat toolchain Android ==
  [X] java tidak ditemukan (pasang: sudo apt-get install -y openjdk-17-jdk)
  [X] ANDROID_HOME / ANDROID_SDK_ROOT tidak diset
  [v] gradle wrapper: /workspace/nq-work/repo/B-Android/gradlew
  [v] ruang disk: 7919 MiB tersisa (cukup untuk --quick)

GAGAL PRASYARAT: 2 masalah di atas. Tidak ada tahap build yang dijalankan.
GATE_ANDROID_EXIT=2
```

**Alasan:** JDK 17, Android SDK (platform 36, build-tools 36.0.0), NDK
27.2.12479018, dan CMake 3.22.1 tidak tersedia di sandbox ini. Skrip gate
**sengaja** menolak berjalan (exit 2) daripada melaporkan hasil palsu.

**Cara menjalankan di VPS:** lihat §4.

### 3.2 Build CEF penuh — exit 1 (distribusi CEF tidak ada)

```
$ cmake -S C-Chromium -B /tmp/cefbuild -DCEF_ROOT=/tmp/tidak-ada-cef
  CEF_ROOT='/tmp/tidak-ada-cef' tidak berisi cmake/cef_variables.cmake.
  Direktori ini bukan distribusi biner CEF yang lengkap.
CEF_CMAKE_EXIT=1
```

**Alasan:** distribusi biner CEF (≥120) tidak ada, dan ruang disk sandbox
**7,8 GiB** — jauh di bawah kebutuhan ≥100 GiB untuk build CEF penuh. Pemeriksaan
prasyarat gagal-keras ini memang disengaja.

### 3.3 APK/AAB — terblokir berantai

Bergantung pada §3.1 (toolchain Android). Tidak dapat dijalankan di sini.

---

## 4. Cara menjalankan gate di VPS Ubuntu

### 4.1 Gate Android

```bash
# 1. JDK 17
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip curl
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 2. Android SDK + NDK (versi persis yang dipakai repo)
mkdir -p "$HOME/android-sdk/cmdline-tools" && cd "$HOME/android-sdk/cmdline-tools"
curl -fsSLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest 2>/dev/null || true
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1"

# 3. Jalankan gate (dari root repo)
cd B-Android
./scripts/run_android_gate.sh --quick          # tanpa assembleRelease/bundleRelease
./scripts/run_android_gate.sh                  # gate lengkap
./scripts/run_android_gate.sh --log-dir /tmp/nq-gate
```

Kode keluar: `0` semua lulus · `1` ada tahap gagal · `2` prasyarat kurang ·
`3` argumen salah. Log per tahap ditulis ke `B-Android/build/gate-logs/`.

**Kirimkan** `B-Android/build/gate-logs/*.log` sebagai bukti.

### 4.2 Gate modul Chromium (ctest)

```bash
sudo apt-get install -y build-essential cmake libsodium-dev
cmake -S C-Chromium -B C-Chromium/build -DNQ_REQUIRE_LIBSODIUM=ON
cmake --build C-Chromium/build -j"$(nproc)"
ctest --test-dir C-Chromium/build --output-on-failure
./C-Chromium/build/nq_ed25519_test        # harus: backend libsodium, 0 gagal
```

`-DNQ_REQUIRE_LIBSODIUM=ON` membuat konfigurasi **gagal** bila libsodium tidak
ada — dipakai untuk rilis bertanda tangan agar tidak diam-diam turun ke
gagal-tertutup.

### 4.3 Build CEF penuh (butuh ≥100 GiB)

```bash
# Unduh distribusi CEF ≥120 (contoh: cef_binary_120.x_linux64.tar.bz2)
tar -xjf cef_binary_*.tar.bz2
cmake -S C-Chromium -B C-Chromium/build-cef -DCEF_ROOT="$PWD/cef_binary_*"
cmake --build C-Chromium/build-cef --config Release -j"$(nproc)"
```

---

## 5. Yang BELUM terverifikasi (tegas)

1. **Workflow `chromium-ctest.yml` belum pernah dijalankan di GitHub Actions.**
   Yang terbukti di sini hanya YAML sah + `actionlint` exit 0 + langkah-langkahnya
   dijalankan manual di sandbox. Hasil run CI sebenarnya belum ada.
2. **Gate Android belum pernah lulus.** Hanya terbukti bahwa skripnya menolak
   dengan benar (exit 2) saat prasyarat kurang, dan uji skripnya 12/12.
3. **Build CEF penuh belum pernah berhasil.** Hanya terbukti pemeriksaan
   prasyaratnya gagal-keras dengan pesan yang benar.
4. **APK/AAB belum pernah dibangun.**
5. **Kanonikalisasi RFC 8785 belum dikerjakan.** Verifier masih menerima teks
   berkas apa adanya, sehingga penyuntingan kosmetik membatalkan tanda tangan.
   **Jangan menandatangani ruleset produksi sebelum ini ditutup** (kontrak §3).
6. **Belum ada jalur pembangkitan tanda tangan** (signing) di luar biner
   aplikasi, dan belum ada prosedur penyimpanan kunci privat.
7. **Ruleset produksi bertanda tangan belum pernah dibuat/diverifikasi
   end-to-end.** Yang diuji adalah vektor RFC 8032, bukan ruleset nyata
   bertanda tangan.
8. **libsodium yang dipakai adalah 1.0.18 (Debian 12).** Versi ini sudah diaudit,
   tetapi belum ada pemeriksaan versi minimum di CMake.
9. **Uji runtime Android (API 35/36) tetap NOT RUN** — butuh perangkat/emulator.
