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

## Peningkatan Optimal (lanjutan)

Untuk peningkatan berikutnya, fokuskan pada:
1. Menambahkan source code aplikasi utama (frontend/backend) agar build lebih spesifik.
2. Menambahkan test otomatis sesuai stack yang dipakai.
3. Menambahkan environment production/staging terpisah bila sudah ada infrastruktur deploy tambahan.
