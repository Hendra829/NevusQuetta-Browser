# C-Chromium — Aplikasi Chromium (CEF)

Target pertama dari tiga target proyek: aplikasi browser desktop berbasis
**CEF (Chromium Embedded Framework)**.

> ## ⚠️ Status verifikasi
>
> **KONFIGURASI SAJA — BELUM PERNAH DIKONFIGURASI/DIBANGUN.** Lingkungan
> penyusunan tidak memiliki `cmake`, `ninja`, `clang`/`g++`, maupun distribusi
> biner CEF (juga tidak ada `java`/`gradle`/NDK). Yang dapat diverifikasi secara
> mekanis di lingkungan ini hanya **sintaks dan konsistensi berkas**, bukan hasil
> kompilasi. Jangan menganggap berkas di direktori ini sebagai "sudah terbukti
> bisa di-build".
>
> Matriks status: lihat `LAPORAN-STATUS-FINAL.md` pada akar repo.

---

## 1. Prasyarat mesin build

Berdasarkan spesifikasi `docs/superpowers/specs/2026-09-19-nevusquetta-v09-chromium-design.md`:

| Kebutuhan | Minimum | Catatan |
|---|---|---|
| RAM | 16 GB (32 GB disarankan) | Tautan Chromium mudah menghabiskan RAM |
| Disk kosong | 100 GB+ | Checkout + output build sangat besar |
| CPU | 4 core (8 disarankan) | |
| OS | Linux x86_64 | Distribusi biner CEF tersedia untuk Linux/Windows/macOS |
| Perkakas | `cmake ≥ 3.22.1`, `ninja`, `git`, `python3` | |
| Lain | `curl`, `unzip`, `nproc` | |

**Perhatian khusus Android/Termux:** membangun Chromium di Termux **tidak
realistis** (RAM dan ruang penyimpanan ponsel tidak cukup, dan Termux tidak
mendukung lingkungan build Chromium resmi). Untuk target ini gunakan VPS
Linux atau mesin build terpisah. Ini konsisten dengan keputusan proyek bahwa
workstation/CI adalah Linux.

---

## 2. Mengunduh distribusi biner CEF

Distribusi biner resmi tersedia di indeks build CEF
(<https://cef-builds.spotifycdn.com/index.html>). Pilih varian **Standard**,
platform Linux 64-bit, lalu versi Chromium yang diinginkan (proyek ini
mensyaratkan CEF ≥ 120).

Contoh langkah (jalankan di luar repo, direktori besar):

```bash
CEF_VERSION=120.1.10+g6b9c1d1+chromium-120.0.6099.129   # ganti sesuai pilihan
CEF_DIR="$HOME/cef_binary_${CEF_VERSION}_linux64"

curl -fL -o /tmp/cef.tar.bz2 \
  "https://cef-builds.spotifycdn.com/cef_binary_${CEF_VERSION}_linux64.tar.bz2"
tar -xjf /tmp/cef.tar.bz2 -C "$HOME"

# Distribusi CEF WAJIB diverifikasi checksum-nya dari berkas SHA1 resmi:
curl -fL -o /tmp/cef.sha1 \
  "https://cef-builds.spotifycdn.com/cef_binary_${CEF_VERSION}_linux64.tar.bz2.sha1"
sha1sum -c <(echo "$(cut -d' ' -f1 /tmp/cef.sha1)  /tmp/cef.tar.bz2")
```

> Jangan menyalin daftar aturan pemblokiran pihak ketiga tanpa memeriksa
> lisensinya. `assets/nevus_ruleset.json` adalah daftar minimal yang disusun
> manual dan **bertanda `signed: false`** — build rilis menolaknya.

---

## 3. Konfigurasi & build

```bash
cmake -S C-Chromium -B C-Chromium/build \
      -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DCEF_ROOT="$HOME/cef_binary_${CEF_VERSION}_linux64"

cmake --build C-Chromium/build --config Release -j"$(nproc)"
```

Build lab (mengizinkan ruleset tanpa tanda tangan):

```bash
cmake -S C-Chromium -B C-Chromium/build-debug \
      -G Ninja -DCMAKE_BUILD_TYPE=Debug \
      -DCEF_ROOT="$CEF_ROOT" \
      -DNQ_ALLOW_UNSIGNED_RULESET=ON
```

CMake akan **berhenti dengan pesan jelas** bila `CEF_ROOT` tidak diset, tidak
berisi `cmake/cef_variables.cmake`, atau versi CEF < 120. Itu disengaja:
bangun yang salah konfigurasi lebih baik gagal di awal daripada menghasilkan
artefak yang tidak dapat dilacak.

---

## 4. Struktur berkas

```
C-Chromium/
├── CMakeLists.txt              # konfigurasi build + gerbang prasyarat
├── README-BUILD.md             # berkas ini
├── assets/
│   └── nevus_ruleset.json      # daftar pemblokiran (signed:false)
└── src/
    ├── cef_app_main.cpp        # titik masuk + switch keamanan + CefSettings
    ├── nevus_app.h/.cpp        # CefApp: skema kustom, proses anak
    ├── nevus_client.h/.cpp     # handler request/permission/lifespan/display
    ├── nq_privacy_policy.h/.cpp# kebijakan GPC, HTTPS-only, izin, daftar blokir
    └── nq_version.h            # identitas produk (di-override CMake)
```

### Pemisahan tanggung jawab

| Berkas | Tanggung jawab | Alasan dipisah |
|---|---|---|
| `nq_privacy_policy.*` | Aturan murni (tanpa CEF) | Dapat diuji tanpa menjalankan CEF |
| `nevus_client.*` | Menegakkan aturan pada setiap permintaan | Satu tempat untuk seluruh gerbang jaringan |
| `nevus_app.*` | Siklus hidup proses & skema kustom | Terpisah dari kebijakan jaringan |
| `cef_app_main.cpp` | Startup, `CefSettings`, switch | Titik masuk tunggal yang mudah diaudit |

---

## 5. Invarian yang ditegakkan kode ini

1. **Hanya HTTPS untuk navigasi tingkat-atas.** `http://`, `file://`, dan skema
   lain dibatalkan di `OnBeforeBrowse`.
2. **Sandbox tidak boleh dimatikan.** `CefSettings.no_sandbox = false`
   ditegaskan, dan `--no-sandbox` dibuang dari baris perintah proses anak.
3. **Global Privacy Control pada setiap permintaan.** Header `Sec-GPC: 1`
   disuntikkan bila belum ada.
4. **Izin ditolak secara default dan tidak "lengket".** Setiap permintaan izin
   membatalkan callback tanpa menyimpan keputusan.
5. **Daftar blokir sebelum keluar jaringan.** Permintaan ke host terblokir
   dibatalkan (`RV_CANCEL`).
6. **Ruleset tanpa tanda tangan ditolak pada build rilis.**

---

## 6. Verifikasi setelah build (wajib)

```bash
# 6.1 Biner ada dan dapat dijalankan
file C-Chromium/build/NevusQuetta
ldd  C-Chromium/build/NevusQuetta | grep -i "not found" && echo "GAGAL: pustaka hilang"

# 6.2 Seluruh sumber daya CEF ada di samping biner
for f in icudtl.dat chrome_100_percent.pak chrome_200_percent.pak resources.pak \
         snapshot_blob.bin v8_context_snapshot.bin; do
  test -e "C-Chromium/build/$f" || echo "HILANG: $f"
done

# 6.3 Versi Chromium yang benar-benar tertaut
C-Chromium/build/NevusQuetta --version

# 6.4 Uji perilaku (jalankan dengan layar/Xvfb)
timeout 20 C-Chromium/build/NevusQuetta --user-data-dir=/tmp/nq-verify 2>&1 | tee /tmp/nq.log
grep -Ei "sandbox|gpu process|failed to load resources" /tmp/nq.log || true
```

**Kriteria lulus:** tidak ada `not found` pada `ldd`; seluruh berkas sumber daya
ada; `--version` menampilkan versi Chromium yang diharapkan; tidak ada pesan
`failed to load resources`; proses dapat dibuka dan ditutup tanpa crash.

---

## 7. Yang belum ada (jangan mengklaim sebaliknya)

- Jendela platform (Shell) — dipakai `CEF_STANDARD_SOURCES`; integrasi UI final
  memerlukan lapisan `views`/GTK yang belum disesuaikan untuk branding.
- Lapisan tab (multi-tab), riwayat, unduhan → belum diimplementasikan.
- Importer bookmark, autofill, sinkronisasi → belum ada.
- Atribusi merek & aset ikon Chromium (spec §9.2, clean-room) → belum dibuat.
- Pemuatan `nevus_ruleset.json` saat runtime → berkas ada dan aturan
  tercermin di `nq_privacy_policy.cpp`, tetapi pembacaan berkas runtime belum
  diimplementasikan; ini celah yang diketahui, bukan kelalaian yang tersembunyi.
- CI untuk Chromium → belum ada (build Chromium tidak mungkin di runner gratis
  GitHub dengan batas waktu/waktu penyimpanan normal).
