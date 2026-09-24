# Catatan DNS — nevusquetta.tech

## Keadaan sekarang: TIDAK perlu diubah

`nevusquetta.tech` **sudah di-hosting dan sudah melayani HTTPS**. Artinya:

- Zona DNS sudah ada dan mengarah ke hosting aktif (nameserver Hostinger:
  `dns-parking.com` / `dns-parking.eu`).
- Sertifikat TLS sudah terbit dan valid untuk `nevusquetta.tech` dan
  `www.nevusquetta.tech`.
- Karena itu, untuk mengunggah PWA **tidak ada satu pun record DNS yang perlu
  dibuat atau diubah**. Cukup unggah berkas ke document root (lihat
  `README-DEPLOY.md`).

> Nilai IP `A` yang aktif sengaja tidak dicantumkan di dokumen ini karena nilainya
> dapat berubah dan tidak boleh disalin dari ingatan. Bacalah dari hPanel
> (**DNS Zone Editor**) atau dengan `dig +short A nevusquetta.tech` di komputer
> Anda bila membutuhkannya.

## Bila kelak pindah hosting

Tabel berikut adalah record yang perlu dibuat. TTL `3600` (1 jam) adalah nilai
aman saat migrasi; turunkan ke `300` (5 menit) sehari sebelum pindah, lalu
kembalikan ke `3600` setelah stabil.

### Opsi 1 — tetap di Hostinger, ganti server saja

| Tipe | Nama | Nilai | TTL |
|---|---|---|---|
| `A` | `@` | IP server baru dari hPanel | 3600 |
| `CNAME` | `www` | `nevusquetta.tech.` | 3600 |

### Opsi 2 — pindah ke Netlify

| Tipe | Nama | Nilai | TTL |
|---|---|---|---|
| `A` | `@` | `75.2.60.5` | 3600 |
| `CNAME` | `www` | `<nama-situs>.netlify.app.` | 3600 |

Netlify juga menawarkan `CNAME` untuk apex, tetapi banyak registrar tidak
mengizinkannya. Bila registrar Anda mendukung **ALIAS/ANAME**, pakai itu ke
`<nama-situs>.netlify.app.` — lebih tahan terhadap perubahan IP.

### Opsi 3 — pindah ke Cloudflare Pages

| Tipe | Nama | Nilai | TTL |
|---|---|---|---|
| `CNAME` | `@` | `<proyek>.pages.dev.` | 3600 (Proxy: aktif) |
| `CNAME` | `www` | `<proyek>.pages.dev.` | 3600 (Proxy: aktif) |

Cloudflare menangani apex lewat *CNAME flattening*, jadi `CNAME` di `@` sah.
Ubah nameserver domain ke nameserver yang diberikan Cloudflare.

### Opsi 4 — pindah ke GitHub Pages

| Tipe | Nama | Nilai | TTL |
|---|---|---|---|
| `A` | `@` | `185.199.108.153` | 3600 |
| `A` | `@` | `185.199.109.153` | 3600 |
| `A` | `@` | `185.199.110.153` | 3600 |
| `A` | `@` | `185.199.111.153` | 3600 |
| `CNAME` | `www` | `<akun>.github.io.` | 3600 |

Empat record `A` tersebut adalah alamat resmi GitHub Pages; **wajib keempatnya**
agar salah satu tidak menjadi titik gagal. Untuk mode ini `www` **tidak boleh**
punya `A` — wajib `CNAME` ke `<akun>.github.io.`.

Jangan lupa menaruh berkas `CNAME` berisi `nevusquetta.tech` di root repo Pages.

## Yang JANGAN dilakukan

- **Jangan** menambahkan record `AAAA` tebakan. Bila hosting tidak melayani IPv6,
  record itu membuat sebagian pengguna gagal terhubung tanpa pesan yang jelas.
- **Jangan** memakai `CNAME` di `@` pada registrar yang tidak mendukung
  flattening/ALIAS — banyak registrar diam-diam mengabaikannya.
- **Jangan** mengubah nameserver sekaligus mengubah record. Lakukan bertahap,
  lalu verifikasi.
- **Jangan** menyalakan proxy Cloudflare (awan oranye) sebelum memastikan
  hosting sudah melayani HTTPS, karena dapat memicu loop redirect.

## Verifikasi setelah perubahan DNS

```bash
# 1. Record benar-benar menyebar
dig +short A nevusquetta.tech
dig +short CNAME www.nevusquetta.tech

# 2. Resolusi lewat resolver publik (hindari cache lokal)
dig +short A nevusquetta.tech @1.1.1.1
dig +short A nevusquetta.tech @8.8.8.8

# 3. Situs benar-benar melayani
curl -sSI https://nevusquetta.tech/ | head -3

# 4. Service worker & manifest dapat dijangkau
curl -sS -o /dev/null -w '%{http_code}\n' https://nevusquetta.tech/service-worker.js
curl -sS -o /dev/null -w '%{http_code}\n' https://nevusquetta.tech/manifest.json
```

Harapan: langkah 4 menghasilkan `200` untuk kedua berkas. Bila
`service-worker.js` menghasilkan `404`, berkasnya belum terunggah ke document
root; bila `200` tetapi PWA tetap tidak bisa dipasang, periksa bahwa
`Content-Type`-nya bukan `text/html` (lihat `.htaccess` bagian 3).
