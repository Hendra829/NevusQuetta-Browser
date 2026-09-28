# Kunci SSH GitHub Actions untuk deploy ke VPS srv1990895

Dokumen ini menjelaskan cara memasang kunci SSH Ed25519 khusus GitHub Actions
dan mengisinya sebagai rahasia repositori, sehingga workflow
`.github/workflows/deploy-vps.yml` dapat men-deploy PWA NevusQuetta ke VPS
Hostinger **srv1990895** (`nevusquetta.tech`).

> **Kunci privat TIDAK PERNAH ditulis di dokumen ini, di workflow, atau di
> berkas mana pun yang di-commit.** Kunci privat hanya diisi manual oleh
> pemilik repositori di antarmuka GitHub.

---

## 0. PERINGATAN — ada DUA kunci berbeda, jangan tertukar

Verifikasi pengguna menemukan bahwa kunci yang dibuat di ronde sebelumnya
**tidak pernah terpasang** di VPS. Saat ini ada dua kunci dengan nama mirip:

| Kunci | Komentar | Terpasang di VPS? | Boleh dipakai? |
|---|---|---|---|
| A | `github-actions-nevusquetta` | **YA** — ada di `/root/.ssh/authorized_keys` | ✅ **pakai yang ini** |
| B | `github-actions-deploy-nevusquetta` | **TIDAK** | ❌ **jangan dipakai** |

Kunci **B** adalah kunci yang dibuat di sandbox pada ronde sebelumnya:

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOEooshpxA3gDMxBKbO8ksoBcWZfWoZppfDH3ZwA1ev7 github-actions-deploy-nevusquetta
Fingerprint: SHA256:1Sm4ryIjC/Qw4MzYukA+VwLYBnpQcgW/Mul9bjD3KLk
```

> ⚠️ **JANGAN isi rahasia `VPS_SSH_KEY` dengan kunci privat pasangan kunci B.**
> Kunci publik B **tidak ada** di VPS, sehingga login akan ditolak
> (`Permission denied (publickey)`). Kunci privat B juga sudah dihapus dari
> sandbox, jadi memang tidak dapat dipakai lagi.

**Yang benar:** pakai kunci **A** (`github-actions-nevusquetta`) — kunci yang
memang sudah terpasang di VPS. Kunci privat pasangan A harus dipegang oleh
pemilik repo (dibuat di mesin sendiri, lihat §2).

### Cara memastikan kunci mana yang terpasang di VPS

Jalankan di VPS (lewat hPanel → Terminal, atau SSH dengan kunci yang sudah ada):

```bash
awk '{print $3, $2}' /root/.ssh/authorized_keys
```

Baris yang muncul adalah komentar + tipe kunci yang benar-benar terpasang.
Bila muncul `github-actions-nevusquetta`, itulah kunci yang harus dipakai.

---

## 1. Kunci publik yang benar-benar terpasang (boleh ditampilkan)

Kunci **A** — `github-actions-nevusquetta` — sudah ada di
`/root/.ssh/authorized_keys` VPS.

> **Nilai kunci publik A belum dapat dibaca dari lingkungan agen** karena akses
> SSH ke VPS tidak tersedia (kata sandi root ditolak server; lihat
> `VPS-SRV1990895.md`). Ambil nilainya langsung dari VPS dengan perintah di §0,
> atau dari mesin tempat kunci privat A dibuat (`cat ~/.ssh/<nama>.pub`).

Bila Anda membuat pasangan kunci **baru** untuk menggantikan A, tambahkan kunci
publiknya ke VPS lebih dulu (§3) **sebelum** mengisi rahasia `VPS_SSH_KEY`.

---

## 2. Rahasia yang harus diisi di GitHub

Buka: **Settings → Secrets and variables → Actions → New repository secret**

| Nama | Nilai | Keterangan |
|---|---|---|
| `VPS_SSH_KEY` | *(kunci privat Ed25519 — diisi manual oleh pemilik repo)* | **Jangan pernah tempel di issue/PR/chat.** Salin isi berkas kunci privat **lengkap**, termasuk baris pembuka dan penutup penanda kunci. Harus pasangan dari kunci **A** (`github-actions-nevusquetta`). |
| `VPS_HOST` | `srv1990895.hstgr.cloud` | Boleh juga `187.53.143.103` |
| `VPS_USER` | `root` | |
| `VPS_PATH` | *(opsional)* | **Default `/var/www/html`** — webroot nginx yang benar-benar ada di VPS. Isi hanya bila webroot Anda berbeda. |

### Cara mengisi `VPS_SSH_KEY`

1. Di mesin tempat kunci privat dibuat, jalankan:
   ```bash
   cat ~/.ssh/gha_deploy
   ```
2. Salin **seluruh** keluaran (5 baris: header, isi, footer).
3. Tempel ke kolom **Secret** untuk nama `VPS_SSH_KEY`.
4. Klik **Add secret**.

> GitHub menyimpan rahasia terenkripsi dan **tidak menampilkannya kembali**
> setelah disimpan. Bila hilang, buat kunci baru dan ulangi langkah 1–4.

---

## 3. Memasang kunci publik di VPS

### Cara A — lewat `install-vps.sh` (disarankan)

```bash
sudo bash install-vps.sh --kunci-publik "ssh-ed25519 AAAA... github-actions-nevusquetta"
```

Skrip memastikan `~/.ssh` bermode `700` dan `authorized_keys` bermode `600`,
lalu menambahkan kunci hanya bila belum ada (idempoten).

### Cara B — manual

```bash
mkdir -p /root/.ssh && chmod 700 /root/.ssh
touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys
grep -qF 'github-actions-nevusquetta' /root/.ssh/authorized_keys || \
  echo 'ssh-ed25519 AAAA... github-actions-nevusquetta' \
  >> /root/.ssh/authorized_keys
```

---

## 4. Verifikasi

### 4.1 Uji login tanpa kata sandi dari mesin lain

```bash
ssh -i ~/.ssh/gha_deploy -o BatchMode=yes root@srv1990895.hstgr.cloud 'hostname; id -un'
```

Harus mencetak hostname VPS dan `root`, **tanpa** meminta kata sandi.

### 4.2 Jalankan workflow secara manual

**Actions → Deploy to srv1990895 → Run workflow** (branch `main`).

Workflow akan berhenti dengan pesan jelas bila ada rahasia yang belum diisi —
tidak akan men-deploy setengah jalan.

### 4.3 Periksa hasil

```bash
curl -sS -o /dev/null -w 'HTTP=%{http_code} CT=%{content_type}\n' https://nevusquetta.tech/
curl -sS -o /dev/null -w 'HTTP=%{http_code} CT=%{content_type}\n' https://nevusquetta.tech/manifest.json
curl -sS -o /dev/null -w 'HTTP=%{http_code} CT=%{content_type}\n' https://nevusquetta.tech/service-worker.js
```

Yang diharapkan:

| URL | HTTP | Content-Type |
|---|---|---|
| `/` | 200 | `text/html` |
| `/manifest.json` | 200 | `application/manifest+json` |
| `/service-worker.js` | 200 | `application/javascript` |

---

## 5. Perilaku gagal-tertutup workflow

| Kondisi | Perilaku |
|---|---|
| Ada rahasia wajib yang kosong | **berhenti** sebelum menyentuh VPS |
| Koneksi SSH gagal | **berhenti** sebelum menyalin berkas |
| Sumber `public_html/index.html` tidak ada | **berhenti** |
| Webroot lama > 2 GB | **berhenti** — cadangan dibatalkan demi keamanan |
| `nginx -t` gagal | **berhenti** — nginx **tidak** direload |
| Verifikasi URL tidak 200 | job **gagal** (agar terlihat) |

---

## 6. Mencabut kunci

Bila kunci bocor atau tidak dipakai lagi:

1. Hapus barisnya dari `/root/.ssh/authorized_keys` di VPS.
2. Hapus rahasia `VPS_SSH_KEY` di GitHub.
3. Buat pasangan kunci baru dan ulangi langkah 2–4.

---

## 7. Yang belum terverifikasi

- **Workflow ini belum pernah dijalankan di GitHub Actions.** Yang terbukti:
  YAML sah, `actionlint` lulus, dan langkah-langkahnya dijalankan manual di
  sandbox.
- **Nilai kunci publik A (`github-actions-nevusquetta`) belum dapat dibaca**
  dari lingkungan agen — akses SSH ke VPS tidak tersedia. Keberadaannya
  dilaporkan oleh verifikasi pengguna, bukan dibaca langsung oleh agen.
- **`ssh-keyscan` tidak mem-verifikasi sidik jari host.** Untuk keamanan lebih
  tinggi, ganti dengan host key yang di-pin di rahasia `VPS_KNOWN_HOSTS`.
- **`/var/www/html` belum pernah diuji sebagai webroot deploy sungguhan** —
  hanya diuji di sandbox.
