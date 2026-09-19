# Spesifikasi Desain NevusQuetta V0.9 dan Chromium M01–M04

Tanggal: 19 September 2026  
Status: Menunggu tinjauan akhir pengguna  
Sumber awal: `NevusQuetta-SYSTEM-V0.8.zip`

## 1. Tujuan

Mengembangkan NevusQuetta melalui dua jalur yang terpisah tetapi selaras:

1. Jalur Android WebView untuk menghasilkan APK yang stabil, aman, responsif, dan dapat diuji dalam waktu dekat.
2. Jalur Chromium Fork untuk menghasilkan browser berbasis Chromium publik secara clean-room dan reproducible sebagai sasaran produksi jangka panjang.

Keberhasilan tidak diukur dari banyaknya fitur semata. Setiap tahap harus memiliki bukti build, lint, pengujian, checksum, dan runtime sebelum dinyatakan selesai.

## 2. Ruang Lingkup dan Urutan

Urutan resmi pengembangan:

1. V0.9A + V0.9B: fondasi keamanan, penyimpanan, UI browser, tab, bookmark, dan pemulihan sesi.
2. V0.9C + V0.9D: pengelola unduhan, media non-DRM, optimasi performa, pengerasan, dan kandidat rilis.
3. Chromium M01–M04: builder, checkout reproducible, integrasi NevusQuetta, build, dan runtime test.

WebLab tetap menjadi laboratorium UI dan logika. WebLab tidak boleh disebut PWA produksi atau pengganti engine browser native tanpa bukti yang sesuai.

## 3. Prinsip Data dan Pembersihan

Data yang harus dipertahankan saat pembaruan:

- file unduhan pengguna;
- bookmark;
- preferensi dan data pengguna penting;
- isi brankas terenkripsi;
- metadata yang diperlukan untuk migrasi data.

Data yang dapat dibersihkan:

- cache WebView dan cache jaringan;
- file sementara dan segmen media sementara;
- log lama yang tidak lagi dibutuhkan;
- output build, APK, ZIP, dan artefak versi lama;
- cookie atau sesi yang kedaluwarsa sesuai kebijakan pengguna.

Pembersihan versi lama hanya boleh dilakukan setelah versi baru lolos build, checksum, dan verifikasi instalasi. Satu artefak rollback terakhir dipertahankan sampai kandidat baru lulus seluruh gate. Semua target penghapusan harus berupa path eksplisit yang tervalidasi; direktori home, root workspace, dan repository tidak boleh menjadi target penghapusan rekursif.

## 4. Arsitektur Jalur Android WebView

`MainActivity` tidak lagi menjadi pusat seluruh tanggung jawab. Sistem dibagi menjadi komponen dengan antarmuka yang jelas:

- `BrowserActivity`: koordinasi layar dan lifecycle.
- `TabManager`: membuat, memilih, membekukan, memulihkan, dan menutup tab.
- `NavigationController`: URL/search, back, forward, reload/stop, dan intent eksternal.
- `WebViewFactory`: konfigurasi WebView yang konsisten dan aman.
- `BookmarkRepository`: penyimpanan, pencarian, pengelompokan, dan migrasi bookmark.
- `HistoryRepository`: riwayat lokal dan kebijakan mode privat.
- `DownloadCoordinator`: validasi, antrean, progres, retry, pembatalan, dan penyelesaian unduhan.
- `MediaDetectionBridge`: pesan media tervalidasi dengan cakupan origin terbatas.
- `UserDataRepository`: preferensi dan metadata pengguna.
- `VaultRepository`: enkripsi berbasis Android Keystore.
- `CleanupManager`: pembersihan aman, terukur, dan dapat diaudit.
- `ReleaseVerifier`: pemeriksaan APK, signature, versi, checksum, dan bukti gate.

Komunikasi antarkomponen menggunakan model data terdefinisi, bukan akses bebas terhadap Activity atau WebView.

## 5. V0.9A — Fondasi Aman

### 5.1 Build dan platform

- Menggunakan toolchain Android yang kompatibel dengan target API 36.
- Mempertahankan runtime test pada API 35 dan API 36.
- Signing debug dan release dipisahkan.
- Secret signing berasal dari environment atau secret store, bukan source code.
- Keystore produksi tidak boleh disimpan dalam arsip atau repository.

Keystore lab V0.8 yang sudah tersebar dianggap tidak layak sebagai kunci produksi. Jika kompatibilitas update dengan APK lama diperlukan, keystore lama hanya dapat dipakai untuk build migrasi yang jelas diberi label non-produksi. Rilis bersih memakai kunci baru yang dikelola di luar source.

### 5.2 Keamanan WebView

- Hanya `http` dan `https` diproses sebagai navigasi web; HTTP ditingkatkan ke HTTPS bila kebijakan memungkinkan.
- File access, universal access from file URLs, cleartext, mixed content, dan third-party cookie dinonaktifkan secara default.
- Safe Browsing diaktifkan bila tersedia.
- Intent eksternal menggunakan allowlist skema dan selalu memerlukan resolusi yang aman.
- Error SSL tidak boleh diabaikan atau diteruskan secara otomatis.
- Permission kamera, mikrofon, lokasi, dan storage mengikuti permintaan kontekstual dan pilihan pengguna.
- Pesan JavaScript tidak menerima perintah arbitrer, path lokal, atau URL yang tidak tervalidasi.
- Bridge hanya aktif pada konteks yang diperlukan, memeriksa origin, skema, ukuran payload, dan tipe pesan.

### 5.3 Penyimpanan dan migrasi

- Room menyimpan tab, bookmark, riwayat, dan metadata unduhan.
- Setiap perubahan skema mempunyai migration test; destructive migration dilarang untuk data pengguna.
- Data sensitif dienkripsi menggunakan kunci Android Keystore.
- Mode privat tidak menulis riwayat, snapshot, atau metadata sesi permanen.
- Prosedur migrasi V0.8 ke V0.9 diuji tanpa menghapus bookmark, unduhan, vault, preferensi, atau data pengguna penting.

### 5.4 Pembersihan aman

- Cleanup berbasis kategori, umur, ukuran, dan path yang tervalidasi.
- Dry-run dan laporan jumlah/ukuran file tersedia untuk proses audit.
- File pengguna dan folder Download tidak pernah menjadi target pembersihan otomatis.
- Kegagalan pembersihan tidak boleh menggagalkan startup aplikasi.

## 6. V0.9B — Pengalaman Browser

### 6.1 Multi-tab

- Membuat, memilih, menduplikasi, dan menutup tab.
- Konfirmasi saat menutup banyak tab bila berisiko kehilangan pekerjaan.
- Pemulihan tab setelah rotasi, penghentian proses, dan pembukaan ulang aplikasi.
- Tab latar belakang dapat dibekukan berdasarkan tekanan memori tanpa kehilangan URL dan state penting.

### 6.2 Navigasi

- Address bar menggabungkan URL dan pencarian.
- Tersedia back, forward, reload/stop, home, share, dan buka di aplikasi eksternal secara aman.
- Predictive back API 35/36 memakai `OnBackPressedDispatcher` dan callback yang sesuai lifecycle.
- Deep link divalidasi sebelum dimuat.

### 6.3 Bookmark, riwayat, dan mode privat

- Bookmark dapat ditambah, diubah, dicari, dikelompokkan, dan dihapus dengan konfirmasi.
- Riwayat dapat dicari serta dibersihkan berdasarkan rentang waktu.
- Mode privat mempunyai indikator visual jelas dan tidak menggunakan sesi reguler secara diam-diam.

### 6.4 UI dan aksesibilitas

- Layout responsif untuk layar ponsel, orientasi portrait/landscape, ukuran font besar, dan navigation bar berbeda.
- Target sentuh, kontras, label aksesibilitas, fokus keyboard, dan TalkBack diperiksa.
- Tampilan tab, bookmark, unduhan, pengaturan, dan informasi privasi menggunakan pola visual konsisten.

## 7. V0.9C — Unduhan dan Media

### 7.1 Pengelola unduhan

- Antrean, progres, batal, retry, dan resume hanya jika server mendukung range request.
- Validasi HTTPS, origin, redirect, MIME, nama file, ukuran, dan ruang penyimpanan.
- Cookie/header sesi hanya diteruskan ke origin yang sama dan tidak ditulis ke log.
- Pencegahan nama file berbahaya, path traversal, dan duplikasi tidak disengaja.
- File sementara dihapus setelah berhasil, dibatalkan, atau gagal.
- Riwayat unduhan membedakan status selesai, gagal, dibatalkan, dan file hilang.

### 7.2 Deteksi media

- Deteksi dibatasi pada media yang dapat diakses secara sah oleh halaman.
- Dukungan mencakup unduhan HTTPS langsung, MP4/audio/gambar terbuka, dan HLS non-DRM.
- DRM, paywall, autentikasi yang dilindungi, token lintas-origin, atau pembatasan pemilik situs tidak boleh dilewati.
- Deteksi tidak menjanjikan tombol universal untuk setiap situs karena format, izin, dan kebijakan situs berbeda.

### 7.3 Pemrosesan lanjutan

- Pemrosesan media dijalankan sebagai pekerjaan latar belakang terpisah dari WebView.
- Fitur peningkatan kualitas hanya boleh tersedia bila implementasi dan perangkat mampu melakukannya tanpa klaim resolusi/detail palsu.
- Output mempertahankan file asli sampai hasil baru lolos verifikasi.

## 8. V0.9D — Optimasi dan Kandidat Rilis

### 8.1 Performa

- Startup, frame time, RAM per tab, penggunaan storage, dan waktu pemulihan sesi diukur.
- Lifecycle WebView mencegah kebocoran Activity, timer, callback, dan native resource.
- Kebijakan tab latar belakang menyesuaikan kelas memori perangkat.
- R8, resource shrinking, dan baseline profile digunakan setelah hasil pengukuran membuktikan manfaat.
- Optimasi tidak boleh mengorbankan integritas data atau keamanan.

### 8.2 Pengerasan release

- Audit manifest, exported component, FileProvider, backup rules, network security, deep link, permission, dan native library.
- Debugging WebView dinonaktifkan pada release.
- Build release tidak memakai kunci lab atau password hard-coded.
- Dependency report dan SBOM dibuat untuk audit.
- Crash report lokal menyensor URL sensitif, cookie, token, dan path pribadi.

### 8.3 Artefak

- APK debug untuk pengujian.
- APK kandidat release setelah seluruh gate lulus.
- SHA-256, version manifest, changelog, laporan audit, dan petunjuk instalasi.
- APK/ZIP lama dibersihkan hanya setelah kandidat baru diverifikasi.

## 9. Chromium M01–M04

### 9.1 M01 — Builder dan preflight

- Termux berfungsi sebagai pengendali melalui SSH.
- Checkout dan build dilakukan pada Linux/VPS.
- Minimum yang disarankan: 150 GB ruang kosong dan RAM 16 GB dengan swap memadai.
- Preflight memeriksa CPU, RAM, disk, filesystem, Git, Python, depot_tools, Java, koneksi, dan kemampuan build Android.
- Kegagalan prasyarat menghentikan proses sebelum checkout besar dimulai.

### 9.2 M02 — Checkout reproducible

- Source diambil dari upstream Chromium resmi.
- Commit/tag, dependency revision, konfigurasi, dan checksum dikunci serta dicatat.
- Source bersih diverifikasi sebelum patch NevusQuetta diterapkan.
- Sinkronisasi yang tidak lengkap tidak boleh diteruskan ke tahap patch.

### 9.3 M03 — Integrasi NevusQuetta

- Branding clean-room: nama, ikon, package, start page, warna, dan product configuration.
- Modul navigasi, tab, bookmark, unduhan, privasi, adblock, media, session, dan UI mempunyai batas jelas.
- GPC, HTTPS-first, permission control, dan perlindungan tracking diterapkan melalui patch yang dapat diaudit.
- Filter adblock harus mempunyai sumber, versi, lisensi, dan mekanisme update yang jelas.
- Patch diberi nomor, diterapkan berurutan, idempotent bila relevan, dan gagal-keras saat context tidak cocok.
- Secret dan keystore tidak pernah dimasukkan ke patch atau repository.

### 9.4 M04 — Build dan runtime

- Canary/debug dibangun sebelum release.
- Artefak diperiksa berdasarkan package, versi, signature, ABI, native library, ukuran, dan checksum.
- Runtime API 35/36 mencakup startup, navigasi, tab, bookmark, unduhan, media, permission, rotasi, lifecycle, tekanan memori, dan crash recovery.
- Chromium build tidak dianggap selesai hanya karena proses kompilasi menghasilkan file; instalasi dan runtime harus dibuktikan.

## 10. Data Flow Utama

### Navigasi

Input pengguna → `NavigationController` → normalisasi/validasi → `WebView` aktif → pembaruan state tab → penyimpanan Room bila bukan mode privat.

### Unduhan

Permintaan halaman/pengguna → validasi origin dan URL → konfirmasi bila diperlukan → `DownloadCoordinator` → file sementara → verifikasi → pemindahan atomik ke folder tujuan → pembaruan metadata.

### Pembaruan aplikasi

Build baru → test/lint → checksum/signature → instalasi uji → migrasi data → runtime gate → penetapan kandidat → pembersihan artefak lama.

## 11. Error Handling

- Error jaringan menampilkan status dan opsi retry tanpa loop otomatis tak terbatas.
- Renderer WebView yang mati dipulihkan dengan state minimal dan laporan lokal yang disensor.
- Database migration gagal harus menghentikan migrasi dan mempertahankan data asli.
- Unduhan gagal tidak meninggalkan file seolah-olah selesai.
- Storage penuh menghentikan pekerjaan dengan pesan jelas dan cleanup terbatas pada file sementara milik aplikasi.
- Patch Chromium gagal menghentikan build; patch tidak dilewati secara diam-diam.

## 12. Strategi Pengujian dan Gate

### Gate V0.9A + V0.9B

- `clean`, unit test, lint, dan `assembleDebug` lulus.
- Tidak ada secret atau keystore produksi dalam source/arsip.
- Instalasi bersih dan jalur migrasi dari 1.2.2 diuji.
- Bookmark, unduhan, vault, preferensi, dan data pengguna tetap tersedia.
- Multi-tab, rotasi, lifecycle, predictive back, dan process-death recovery diuji.
- Runtime API 35 dan API 36 lulus.

### Gate V0.9C + V0.9D

- Unit test, instrumentation test, lint, debug/release build, dan dependency check lulus.
- Unduhan langsung, HLS non-DRM, cancel, retry, resume, jaringan putus, storage penuh, dan process death diuji.
- Tidak ada cookie, token, secret, atau path pribadi pada log.
- Multi-tab dengan media, rotasi, layar mati/menyala, foreground/background, dan low-memory diuji.
- APK kandidat, SHA-256, audit report, changelog, dan panduan instalasi tersedia.

### Gate Chromium

- M01: host memenuhi preflight.
- M02: checkout/sync terverifikasi dan reproducible.
- M03: patch series diterapkan bersih serta lolos audit.
- M04: build, instalasi, dan runtime API 35/36 terbukti.

Tahap berikutnya tetap terkunci bila gate tahap aktif belum lulus. Status harus menggunakan `PASS`, `FAIL`, `BLOCKED`, atau `NOT RUN`; tidak boleh menandai selesai tanpa bukti.

## 13. Batasan dan Non-Goals

- Tidak menyalin kode, aset, merek, atau secret milik Quetta.
- Tidak melewati DRM, paywall, autentikasi, atau kontrol akses situs.
- Tidak menjanjikan deteksi/unduhan media universal pada semua situs.
- Tidak mengklaim WebView sebagai Chromium Fork.
- Tidak mengklaim peningkatan video 4K/8K sebagai detail asli tanpa model, pipeline, benchmark, dan bukti kualitas.
- Tidak menghapus data pengguna demi optimasi atau pembersihan.

## 14. Deliverable

1. Source V0.9A+B yang telah diuji.
2. Source V0.9C+D yang telah diuji.
3. APK kandidat beserta checksum dan laporan audit.
4. Builder Chromium M01, metadata checkout M02, patch series M03, serta artefak dan runtime report M04.
5. Dokumentasi Termux sebagai pengendali build jarak jauh.
6. Paket proyek terbaru yang bersih; versi lama dibersihkan hanya sesuai kebijakan pada spesifikasi ini.

## 15. Keputusan yang Dikunci

- Mengembangkan jalur WebView dan Chromium.
- Urutan kerja: V0.9A+B → V0.9C+D → Chromium M01–M04.
- Seluruh fungsi utama masuk cakupan, tetapi digabungkan melalui gate bertahap.
- Unduhan, bookmark, vault, preferensi, dan data pengguna dipertahankan.
- Cache, file sementara, log, serta artefak versi lama dibersihkan secara aman setelah versi baru terverifikasi.
- Keamanan, performa, kerapian kode, audit, dan bukti runtime merupakan syarat rilis.
