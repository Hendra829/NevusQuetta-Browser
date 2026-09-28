# Verifikasi Deploy PWA — nevusquetta.tech

Dokumen ini mencatat **hasil pemeriksaan nyata** atas domain `nevusquetta.tech`
dan kesiapan paket PWA untuk diunggah. Semua angka berasal dari eksekusi
perintah, bukan perkiraan.

Tanggal pemeriksaan: **2026-09-29** (UTC+7).

---

## 1. Status domain saat ini

```
$ curl -sS -I -m 25 https://nevusquetta.tech/
HTTP/1.1 200 OK
Server: nginx
Date: Mon, 28 Sep 2026 19:14:25 GMT
Content-Type: text/html
Content-Length: 349
Last-Modified: Sat, 19 Sep 2026 10:52:28 GMT
ETag: "6aae696c-15d"
Accept-Ranges: bytes
```

Domain **aktif dan melayani HTTPS**. Yang di-host saat ini hanyalah halaman
placeholder 349 byte:

```html
<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>NevusQuetta</title>
</head>
<body>
  <main>
    <h1>NevusQuetta</h1>
    <p>Native Android browser release service.</p>
    <p><a href="/download/">Download build terbaru</a></p>
  </main>
</body>
</html>
```

Halaman itu menyebut layanan rilis Android dan menautkan `/download/` — tetapi
tautan tersebut **404**:

```
$ curl -sS -m 20 https://nevusquetta.tech/download/ -w "\nHTTP=%{http_code}\n"
<html><head><title>404 Not Found</title></head>...
HTTP=404
```

### Berkas PWA belum ada di domain

```
manifest.json          HTTP=404 SIZE=146 CT=text/html
sw.js                  HTTP=404 SIZE=146 CT=text/html
service-worker.js      HTTP=404 SIZE=146 CT=text/html
icon-192.png           HTTP=404 SIZE=146 CT=text/html
icon-512.png           HTTP=404 SIZE=146 CT=text/html
favicon.ico            HTTP=404 SIZE=146 CT=text/html
.htaccess              HTTP=404 SIZE=146 CT=text/html
```

**Kesimpulan:** PWA **belum tersinkron** ke `nevusquetta.tech`. Yang tersaji
adalah halaman placeholder lama dari 19 Sep 2026.

### DNS

```
$ getent hosts nevusquetta.tech
187.53.143.103  nevusquetta.tech
```

`www` melayani isi yang identik (Content-Length 349, ETag sama), jadi kedua nama
host mengarah ke document root yang sama. **Tidak ada perubahan DNS yang
diperlukan** — sesuai catatan `README-DNS.md`.

---

## 2. Kredensial deploy — TIDAK TERSEDIA

Ini dilaporkan apa adanya. Tidak ada satu pun kredensial hosting di lingkungan
agen, dan hal itu **dibuktikan dengan percobaan autentikasi nyata**, bukan
diasumsikan.

### 2.1 Pencarian kredensial

```
$ env | grep -iE 'hostinger|ftp|ssh_|deploy|nevus'
(kosong)

$ ls -la ~/.ssh/
total 0
drwx------ 2 root root 10 Aug 12 03:54 .
drwx------ 2 root root 71 Sep 28 18:42 ..

$ ls -la ~/.netrc ~/.git-credentials
ls: cannot access '/root/.netrc': No such file or directory
ls: cannot access '/root/.git-credentials': No such file or directory

$ find / -maxdepth 4 -name 'id_*' -not -path '/proc/*'
(kosong — tidak ada kunci SSH sama sekali)
```

Profil kredensial yang terhubung di platform hanya: **GitHub, Gmail, Google
Maps, Google BigQuery**. **Tidak ada Hostinger.**

### 2.2 Percobaan autentikasi nyata

```
$ ssh -o BatchMode=yes -o ConnectTimeout=10 root@nevusquetta.tech 'echo MASUK'
root@nevusquetta.tech: Permission denied (publickey,password).
SSH_EXIT=255

$ sftp -o BatchMode=yes -o ConnectTimeout=10 root@nevusquetta.tech
root@nevusquetta.tech: Permission denied (publickey,password).
Connection closed

$ curl -sS -m 12 --connect-timeout 8 ftp://nevusquetta.tech/
curl: (28) Failed to connect to nevusquetta.tech port 21 after 8001 ms: Timeout was reached
```

### 2.3 Port yang terbuka

```
port 21   tertutup
port 22   TERBUKA
port 2222 tertutup
port 990  tertutup
```

Port 22 terbuka, tetapi **menolak kunci kita** (`Permission denied`). Jadi
server memang menerima SSH, hanya saja kita tidak punya kredensialnya.

**Kesimpulan:** unggah otomatis **TIDAK DAPAT DILAKUKAN** dari lingkungan ini.
Langkah unggah harus dijalankan oleh pemilik domain.

---

## 3. Kesiapan paket — SIAP

### 3.1 Paket ZIP terverifikasi

```
$ curl -sSL -o nevusquetta-pwa-web.zip "<url paket A>"
HTTP=200 SIZE=422193

$ md5sum nevusquetta-pwa-web.zip
bea2dadcaf408ad6397fe117f377714c  nevusquetta-pwa-web.zip
harapan: bea2dadcaf408ad6397fe117f377714c   -> COCOK
```

Isi (9 berkas, 442.337 byte tak-terkompresi):

```
    10907  app.js
    33525  icons/icon-192.png
   154466  icons/icon-512-maskable.png
   224090  icons/icon-512.png
     4588  index.html
     1404  manifest.json
     1217  offline.html
     6490  service-worker.js
     5650  styles.css
```

### 3.2 `public_html/` identik dengan `D-PWA/`

Kesembilan berkas dibandingkan md5 satu per satu:

```
SAMA   index.html
SAMA   app.js
SAMA   styles.css
SAMA   service-worker.js
SAMA   manifest.json
SAMA   offline.html
SAMA   icons/icon-192.png
SAMA   icons/icon-512.png
SAMA   icons/icon-512-maskable.png
```

Jadi `deploy/nevusquetta-tech/public_html/` adalah salinan setia sumber `D-PWA/`
di repositori — tidak ada berkas yang tertinggal atau menyimpang.

### 3.3 Uji service worker

```
$ node D-PWA/tests/sw-harness.mjs
Harness service worker NevusQuetta
sumber: /workspace/nq-work/repo/D-PWA/service-worker.js

  PASS  U1.01 handler install terdaftar
  PASS  U1.02 shell cache berisi 6 berkas wajib
  PASS  U1.03 index.html ada di precache
  PASS  U1.04 offline.html ada di precache
  PASS  U1.05 ikon masuk cache ASET (3 berkas)
  PASS  U1.06 skipWaiting TIDAK dipanggil saat install
  PASS  U2.01 install menyelesaikan waitUntil tanpa error saat normal
  PASS  U3.01 handler fetch terdaftar
  PASS  U3.02 navigasi online disajikan dari jaringan
  PASS  U3.03 permintaan benar-benar dikirim ke jaringan
  PASS  U4.01 navigasi offline tidak melempar
  PASS  U4.02 navigasi offline disajikan dari cache precache
  PASS  U5.01 lintas-origin tidak ditangani SW
  PASS  U6.01 respons ber-Set-Cookie TIDAK di-cache
  PASS  U7.01 respons 404 TIDAK di-cache
  PASS  U8.01 cache versi lama dihapus
  PASS  U9.01 SKIP_WAITING memanggil skipWaiting
  PASS  U9.02 CLEAR_CACHES mengosongkan semua cache
  PASS  U9.03 konfirmasi CACHES_CLEARED dikirim

HASIL: 19 lulus, 0 gagal
SW_EXIT=0
```

---

## 4. Cara mengunggah

### 4.1 Cara tercepat — skrip siap-jalan

```bash
cd deploy/nevusquetta-tech

# 1. Lihat dulu apa yang AKAN diunggah (tidak menulis apa pun)
./deploy-pwa.sh --metode rsync --host user@server --kering

# 2. Unggah sungguhan
./deploy-pwa.sh --metode rsync --host user@server --tujuan /var/www/nevusquetta.tech/

# 3. Verifikasi saja, tanpa unggah
./deploy-pwa.sh --metode periksa
```

Metode yang didukung: `rsync`, `scp`, `ftp` (lewat `lftp`), `lokal`, `periksa`.
Skrip akan menolak berjalan (exit 2) bila perkakas atau argumen kurang, dan
keluar dengan exit 1 bila verifikasi pasca-unggah gagal.

### 4.2 Cara manual — Hostinger hPanel

Ikuti `README-DEPLOY.md` bagian "Langkah unggah — Hostinger (hPanel)".
Ringkasnya: hPanel → File Manager → document root → unggah **seluruh isi**
`public_html/` (termasuk `.htaccess`, aktifkan "tampilkan berkas tersembunyi").

### 4.3 Cara manual — VPS nginx

```bash
sudo mkdir -p /var/www/nevusquetta.tech
sudo cp -r public_html/* /var/www/nevusquetta.tech/
sudo cp nginx-nevusquetta.tech.conf /etc/nginx/sites-available/nevusquetta.tech
sudo ln -sf /etc/nginx/sites-available/nevusquetta.tech \
            /etc/nginx/sites-enabled/nevusquetta.tech
sudo nginx -t && sudo systemctl reload nginx
```

---

## 5. Verifikasi setelah unggah

```bash
for p in / /index.html /manifest.json /service-worker.js /offline.html \
         /icons/icon-192.png /icons/icon-512.png /icons/icon-512-maskable.png; do
  printf "%-32s " "$p"
  curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' "https://nevusquetta.tech$p"
done
```

Harapan: semuanya `200`, `manifest.json` ber-`Content-Type`
`application/manifest+json`, ikon ber-`image/png`.

Lalu bandingkan isi:

```bash
cd deploy/nevusquetta-tech/public_html
for f in $(find . -type f ! -name '.htaccess' | sort); do
  a=$(md5sum "$f" | cut -d' ' -f1)
  b=$(curl -sS "https://nevusquetta.tech/${f#./}" | md5sum | cut -d' ' -f1)
  [ "$a" = "$b" ] && echo "cocok  $f" || echo "BEDA   $f"
done
```

Terakhir, uji pemasangan di Chrome Android: buka `https://nevusquetta.tech/` →
menu ⋮ → **Tambahkan ke layar utama**. Bila entri itu muncul, manifest +
service worker + HTTPS sudah memenuhi syarat pemasangan.

---

## 6. Yang belum terverifikasi

1. **PWA belum terunggah ke `nevusquetta.tech`** — tidak ada kredensial.
2. **`deploy-pwa.sh` belum pernah dijalankan sampai selesai** — hanya dibuat dan
   diperiksa sintaksnya (`bash -n`). Jalur unggah rsync/scp/ftp belum diuji nyata.
3. **Pemasangan PWA di perangkat belum diuji** — butuh Chrome Android dan domain
   yang sudah terisi.
4. **`beforeinstallprompt` belum pernah teramati** — hanya bisa muncul setelah
   domain melayani manifest + service worker yang sah.
5. **Halaman placeholder lama akan tertimpa** — belum ada cadangan isi lama
   karena kita tidak punya akses baca ke document root.
6. **`/download/` yang ditautkan halaman lama tetap 404** — tidak ada berkas
   APK di sana; itu di luar lingkup PWA.
