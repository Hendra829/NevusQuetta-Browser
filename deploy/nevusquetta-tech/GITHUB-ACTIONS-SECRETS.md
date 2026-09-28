# Kunci SSH GitHub Actions untuk deploy ke VPS srv1990895

Dokumen ini menjelaskan cara memasang kunci SSH Ed25519 khusus GitHub Actions
dan mengisinya sebagai rahasia repositori, sehingga workflow
`.github/workflows/deploy-vps.yml` dapat men-deploy PWA NevusQuetta ke VPS
Hostinger **srv1990895** (`nevusquetta.tech`).

> **Kunci privat TIDAK PERNAH ditulis di dokumen ini, di workflow, atau di
> berkas mana pun yang di-commit.** Kunci privat hanya diisi manual oleh
> pemilik repositori di antarmuka GitHub.

---

## 1. Kunci publik (boleh ditampilkan)

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOEooshpxA3gDMxBKbO8ksoBcWZfWoZppfDH3ZwA1ev7 github-actions-deploy-nevusquetta
```

| | |
|---|---|
| Tipe | Ed25519 |
| Fingerprint | `SHA256:1Sm4ryIjC/Qw4MzYukA+VwLYBnpQcgW/Mul9bjD3KLk` |
| Komentar | `github-actions-deploy-nevusquetta` |

Kunci publik ini harus ada di `/root/.ssh/authorized_keys` pada VPS.
`install-vps.sh --kunci-publik "..."` melakukannya secara **idempoten**
(tidak menggandakan baris yang sudah ada).

---

## 2. Rahasia yang harus diisi di GitHub

Buka: **Settings → Secrets and variables → Actions → New repository secret**

| Nama | Nilai | Keterangan |
|---|---|---|
| `VPS_SSH_KEY` | *(kunci privat Ed25519 — diisi manual oleh pemilik repo)* | **Jangan pernah tempel di issue/PR/chat.** Salin isi berkas kunci privat **lengkap**, termasuk baris pembuka dan penutup penanda kunci. |
| `VPS_HOST` | `srv1990895.hstgr.cloud` | Boleh juga `187.53.143.103` |
| `VPS_USER` | `root` | |
| `VPS_PATH` | `/var/www/nevusquetta.tech` | Webroot nginx |

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
sudo bash install-vps.sh --kunci-publik \
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOEooshpxA3gDMxBKbO8ksoBcWZfWoZppfDH3ZwA1ev7 github-actions-deploy-nevusquetta"
```

Skrip memastikan `~/.ssh` bermode `700` dan `authorized_keys` bermode `600`,
lalu menambahkan kunci hanya bila belum ada.

### Cara B — manual

```bash
mkdir -p /root/.ssh && chmod 700 /root/.ssh
touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys
grep -qF 'github-actions-deploy-nevusquetta' /root/.ssh/authorized_keys || \
  echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOEooshpxA3gDMxBKbO8ksoBcWZfWoZppfDH3ZwA1ev7 github-actions-deploy-nevusquetta' \
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
| Ada rahasia yang kosong | **berhenti** sebelum menyentuh VPS |
| Koneksi SSH gagal | **berhenti** sebelum menyalin berkas |
| Sumber `public_html/index.html` tidak ada | **berhenti** |
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
- **Kunci publik ini belum terpasang di VPS** — pemasangan otomatis terblokir
  karena kata sandi root ditolak server (lihat `VPS-SRV1990895.md`).
- **`ssh-keyscan` tidak mem-verifikasi sidik jari host.** Untuk keamanan lebih
  tinggi, ganti dengan host key yang di-pin di rahasia `VPS_KNOWN_HOSTS`.
