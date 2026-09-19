# NevusQuetta VPS + domain deployment

Target VPS: `187.53.143.103`  
Domain: `nevusquetta.tech`

## Security model

- `root` dipakai hanya untuk provisioning awal.
- Deployment rutin memakai user `nevusdeploy`.
- GitHub Actions harus memakai SSH private key melalui repository secrets.
- Jangan menyimpan password, private key, token, atau keystore signing di repository.

## DNS

Di pengelola DNS domain, buat:

| Name | Type | Value | TTL |
|---|---|---|---|
| `@` | A | `187.53.143.103` | 300 |
| `www` | A | `187.53.143.103` | 300 |

Setelah DNS sudah resolve ke VPS, TLS dapat diaktifkan dengan `enable-tls.sh`.

## Bootstrap sekali

Dari komputer/Termux yang memiliki izin SSH:

```bash
ssh root@187.53.143.103
```

Clone repository privat atau salin folder `infra/vps`, lalu sebagai root:

```bash
chmod +x infra/vps/*.sh
NEVUS_DOMAIN=nevusquetta.tech ./infra/vps/bootstrap-nevusquetta.sh
```

Tambahkan public key deployment ke:

```text
/home/nevusdeploy/.ssh/authorized_keys
```

Kemudian aktifkan TLS:

```bash
NEVUS_DOMAIN=nevusquetta.tech \
NEVUS_TLS_EMAIL='ADMIN_EMAIL' \
./infra/vps/enable-tls.sh
```

## Struktur server

```text
/srv/nevusquetta/
├── current -> /srv/nevusquetta/releases/<version>
├── releases/
└── site/
```

Release baru dipasang secara atomik: upload ke direktori versi baru, validasi checksum, lalu pindahkan symlink `current`.

## GitHub repository secrets yang diperlukan

- `NEVUS_VPS_HOST` = `187.53.143.103`
- `NEVUS_VPS_USER` = `nevusdeploy`
- `NEVUS_VPS_SSH_KEY` = private key deployment khusus
- `NEVUS_VPS_KNOWN_HOSTS` = output `ssh-keyscan -H 187.53.143.103`

Private key deployment tidak boleh sama dengan private key pribadi utama.
