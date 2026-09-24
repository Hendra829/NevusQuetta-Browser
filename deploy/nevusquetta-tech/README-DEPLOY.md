# Deploy PWA NevusQuetta ke nevusquetta.tech

Paket ini berisi aplikasi PWA (target ketiga proyek) beserta konfigurasi hosting
yang dibutuhkan. Semuanya **belum diunggah** — lihat bagian Status di bawah.

## Status (jujur)

| Hal | Keadaan |
|---|---|
| Domain `nevusquetta.tech` | **SUDAH LIVE dan di-hosting** (Hostinger; nameserver `dns-parking.*`) |
| Kredensial deploy (FTP/SFTP/hPanel API/token hosting) | **TIDAK TERSEDIA** di lingkungan kerja |
| Unggahan ke domain | **BELUM DILAKUKAN** |
| Isi paket ini | Teruji lokal: service worker 19/19 lulus, manifest + 3 ikon valid |

Karena tidak ada kredensial, langkah unggah harus dijalankan manual. Konfigurasi
di paket ini sudah disiapkan supaya unggahan itu cukup **salin-tempel**, tanpa
menyusun aturan cache/redirect dari nol.

## Isi paket

```
public_html/                 -> unggah SELURUH isi folder ini ke document root
  index.html                 aplikasi
  app.js
  styles.css
  service-worker.js
  manifest.json
  offline.html
  icons/
    icon-192.png             192x192
    icon-512.png             512x512  (any)
    icon-512-maskable.png    512x512  (maskable)
  .htaccess                  aturan redirect + cache + header keamanan (Hostinger)
nginx-nevusquetta.tech.conf  padanan nginx bila memakai VPS
README-DNS.md                catatan DNS
```

Folder `tests/` **tidak** diikutkan ke paket unggah (hanya untuk CI lokal).

## Langkah unggah — Hostinger (hPanel)

1. Masuk **hPanel** → **File Manager** (atau sambungkan lewat FTP/SFTP).
2. Buka document root domain: biasanya `public_html/` untuk domain utama, atau
   `domains/nevusquetta.tech/public_html/` bila domain tambahan.
3. **Cadangkan** isi lama bila ada (unduh atau ubah nama menjadi
   `public_html_lama/`) sebelum menimpa.
4. Unggah **seluruh isi** `public_html/` dari paket ini, termasuk `.htaccess`.
   Aktifkan opsi "tampilkan berkas tersembunyi" di File Manager — berkas
   bertitik di depan sering tidak terlihat.
5. Pastikan struktur akhirnya: `public_html/index.html` (bukan
   `public_html/public_html/index.html` — kesalahan paling umum).
6. **Aktifkan SSL** di hPanel → **SSL** → Let's Encrypt (bila belum), lalu
   paksa HTTPS.
7. Buka `https://nevusquetta.tech/`, lalu cek:
   - `https://nevusquetta.tech/manifest.json` → harus tampil sebagai JSON
   - `https://nevusquetta.tech/service-worker.js` → harus tampil sebagai teks
   - `https://nevusquetta.tech/icons/icon-192.png` → gambar tampil

Verifikasi cepat dari terminal (jalankan dari komputer Anda):

```bash
for p in / /index.html /manifest.json /service-worker.js /offline.html \
         /icons/icon-192.png /icons/icon-512.png /icons/icon-512-maskable.png; do
  printf "%-32s " "$p"
  curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' "https://nevusquetta.tech$p"
done
```

Harapan: semuanya `200`, dengan `manifest.json` ber-`Content-Type`
`application/manifest+json` (atau `application/json`) dan ikon ber-`image/png`.

8. Uji installability di Chrome Android: buka situs → menu ⋮ → **Tambahkan ke
   layar utama**. Bila entri itu muncul, manifest + service worker + HTTPS sudah
   memenuhi syarat pemasangan.

## Langkah unggah — alternatif VPS (nginx)

```bash
sudo mkdir -p /var/www/nevusquetta.tech
sudo cp -r public_html/* /var/www/nevusquetta.tech/
sudo cp nginx-nevusquetta.tech.conf /etc/nginx/sites-available/nevusquetta.tech
sudo ln -sf /etc/nginx/sites-available/nevusquetta.tech \
            /etc/nginx/sites-enabled/nevusquetta.tech
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d nevusquetta.tech -d www.nevusquetta.tech
```

## Setelah unggah: memperbarui aplikasi

Service worker memakai strategi **cache-first untuk aset** dan **network-first
untuk navigasi**. Artinya:

- Menaruh berkas baru di server **tidak otomatis** membuat pengguna lama
  menerimanya (itu memang tujuannya: aplikasi tetap jalan offline).
- Untuk memaksa pembaruan, **naikkan versi cache** di `service-worker.js`:

  ```js
  const CACHE_NAME = 'nq-pwa-v1.0.1';   // ubah setiap rilis
  ```

  Saat nilai ini berubah, service worker baru akan membuang cache lama,
  meng-`skipWaiting()`, dan halaman memuat aset baru.
- Karena itu pula `service-worker.js` **tidak boleh** di-cache lama oleh server —
  `.htaccess`/nginx di paket ini sudah mengirim `Cache-Control: no-cache`
  untuk berkas tersebut.

## Catatan keamanan

- Tidak ada satu pun kredensial di paket ini; tidak ada token, kunci, atau
  kata sandi. Semua unggahan dilakukan oleh Anda sendiri.
- `.htaccess` mengirim `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin`, dan `Permissions-Policy`
  yang mematikan geolokasi/kamera/mikrofon. Sesuaikan bila aplikasi nanti
  memang butuh salah satunya.
- Tidak ada `Content-Security-Policy` di paket ini. Aplikasi memakai skrip
  eksternal? Tidak — `index.html` dan `app.js` seluruhnya lokal, sehingga CSP
  ketat dapat ditambahkan. Aktifkan setelah diuji, karena CSP yang salah
  memasukkan aset sendiri adalah penyebab umum halaman putih.
