# Kontrak Verifier Tanda Tangan Ruleset (Ed25519)

Status: **kerangka terpasang, aritmetika kurva belum diimplementasikan** — sengaja,
dan gagal-tertutup. Dokumen ini adalah kontrak yang harus dipenuhi bila inti
tersebut nanti diisi.

Berkas terkait:

| Berkas | Peran |
|---|---|
| `C-Chromium/src/nq_ed25519.h/.cpp` | Antarmuka + kerangka verifier |
| `C-Chromium/src/nq_ed25519_constants.h` | Konstanta kurva (DIBANGKITKAN, bukan diketik manual) |
| `C-Chromium/src/nq_sha512.h/.cpp` | Primitif SHA-512 (RFC 8032 membutuhkannya) |
| `C-Chromium/src/nq_ruleset.cpp` | Titik panggil verifier pada jalur pemuatan ruleset |
| `C-Chromium/tools/gen_ed25519_constants.py` | Pembangkit + pemeriksa konstanta |

---

## 1. Status verifikasi

Dipisahkan tegas antara yang **terbukti lewat eksekusi** dan yang **belum ada**.

| Komponen | Status | Bukti |
|---|---|---|
| Primitif SHA-512 (FIPS 180-4) | ✅ **Teruji eksekusi** | 18/18 pemeriksaan lulus; vektor dari `hashlib.sha512` pada panjang 0,1,55,56,111,112,113,127,128,129,255,256,1000 + uji umpan bertahap + uji idempoten |
| Konstanta kurva Ed25519 | ✅ **Teruji eksekusi** | `tools/gen_ed25519_constants.py`: 15/15 pemeriksaan lulus (d pada kurva, √−1² = −1, titik basis `5866…66`, eksponen inversi & akar kuadrat) |
| Konstanta SHA-512 `K[t]` | ✅ **Teruji eksekusi** | Dihitung eksak via akar kubik bilangan besar; seluruh implementasi SHA-512 kemudian dibuktikan lewat vektor |
| **Aritmetika kurva Ed25519** (decompress titik, scalar multiplication, adisi lengkap) | ❌ **BELUM ADA** | Tidak diimplementasikan pada rilis ini |
| Verifikasi tanda tangan end-to-end | ❌ **BELUM ADA** | Bergantung pada baris di atas |
| Integrasi verifier ke jalur pemuatan ruleset | ✅ **Terpasang** | `nq_ruleset.cpp` memanggil `RulesetSignatureVerifier::Verify()`; hasil `false` → `kSignatureInvalid` |

**Konsekuensi praktis:** setiap ruleset dengan `"signed": true` **DITOLAK**.
Aplikasi tetap aman (gagal-tertutup), tetapi belum dapat memakai ruleset
bertanda tangan. Ini keadaan yang jujur, bukan bug.

---

## 2. Antarmuka yang harus dipenuhi

### 2.1 Inti kripto (belum diimplementasikan)

```cpp
namespace nq {

// Memverifikasi tanda tangan Ed25519 (RFC 8032, varian PureEdDSA).
//
// Parameter:
//   public_key : 32 byte. Titik A terkompresi (y little-endian; bit 255 =
//                tanda x). Titik di luar kurva WAJIB ditolak.
//   message    : byte pesan apa adanya.
//   signature  : 64 byte: R (32 byte, titik terkompresi) || S (32 byte, LE).
//
// Mengembalikan true HANYA bila tanda sah. Semua kasus meragukan -> false.
bool Ed25519Verify(const std::uint8_t public_key[32],
                   const std::uint8_t* message, std::size_t message_len,
                   const std::uint8_t signature[64]);

// True bila primitif yang dibutuhkan verifier sudah benar.
// WAJIB dipanggil sekali saat startup; bila false, verifier harus dianggap
// tidak tersedia dan ruleset bertanda tangan DITOLAK.
bool Ed25519SelfTest();

}  // namespace nq
```

### 2.2 Kelas yang dipakai jalur pemuatan ruleset (sudah ada)

```cpp
class Ed25519RulesetVerifier final : public RulesetSignatureVerifier {
 public:
  std::string algorithm() const override;               // "ed25519"
  bool Verify(const std::string& payload,
              const std::string& signature_value,
              const std::string& public_key) const override;
};
```

`Verify()` menerima **hex**, karena itu bentuk yang dipakai di dalam JSON.
Aturan penguraian:

| Masukan | Diterima | Ditolak |
|---|---|---|
| `public_key` | 64 karakter hex (32 byte) | panjang lain, karakter non-hex, string kosong |
| `signature_value` | 128 karakter hex (64 byte) | panjang lain, karakter non-hex, string kosong |
| `payload` | teks apa pun, termasuk kosong | — |

Setiap kegagalan penguraian mengembalikan `false` (gagal-tertutup), **tidak**
melempar eksepsi dan tidak menulis apa pun ke stdout.

---

## 3. Titik panggil di jalur pemuatan ruleset

`nq_ruleset.cpp` → fungsi `Validate()`, cabang `if (ruleset.declared_signed)`:

1. Blok `signature` wajib ada dan berupa objek → jika tidak: `kSignatureMissing`.
2. `options.verifier == nullptr` → `kSignatureAlgorithmUnsupported`.
3. `signature.algorithm` wajib sama (tanpa beda besar/kecil) dengan
   `verifier->algorithm()` → jika tidak: `kSignatureAlgorithmUnsupported`.
4. `verifier->Verify(text, signature.value, options.public_key)` → bila `false`:
   `kSignatureInvalid`.
5. Lolos keempat langkah → ruleset diterima.

**Penting — checksum diperiksa lebih dulu.** Ketidakcocokan checksum selalu fatal
dan **tidak dapat dilonggarkan** oleh `allow_unsigned`, sehingga berkas yang
berubah tidak akan pernah sampai ke tahap verifikasi tanda tangan.

**Kanonikalisasi payload (kontrak saat ini):** verifier menerima **teks berkas
apa adanya**. Konsekuensinya, tanda tangan harus dibuat atas byte identik dengan
isi berkas — termasuk urutan kunci, spasi, dan baris baru.

> ⚠️ **Cacat yang harus ditutup sebelum rilis bertanda tangan:** teks apa adanya
> berarti ruleset yang setara secara semantik tetapi berbeda format menghasilkan
> tanda tangan berbeda, dan penyuntingan kosmetik membatalkan tanda tangan. Untuk
> rilis bertanda tangan, ganti dengan **kanonikalisasi JSON** (RFC 8785), tandatangani
> hasil kanonikalisasi, dan keluarkan blok `signature` dari payload sebelum
> menghitung tanda tangan. Sampai itu dilakukan, jangan menandatangani ruleset
> produksi.

---

## 4. Algoritma verifikasi (untuk implementasi berikutnya)

RFC 8032 §5.1.7, varian PureEdDSA:

1. `A = decode_point(public_key)` — `y = LE(pk[0..31]) & (2^255 − 1)`,
   `sign = pk[31] >> 7`. Tolak bila `y >= p` atau titik tidak berada di kurva.
2. `R = decode_point(signature[0..31])` — aturan sama.
3. `S = LE(signature[32..63])`. **Tolak bila `S >= L`** (tanpa ini tanda tangan
   menjadi dapat diubah — lihat RFC 8032 §8.4).
4. `k = SHA512(R || A || M) mod L`.
5. Terima bila `[S]B == R + [k]A`, dengan `B` = titik basis.

Penyederhanaan yang lazim dan sah (cofactorless): bandingkan
`[8][S]B == [8]R + [8][k]A`. RFC 8032 §8.8 membahas konsekuensi keamanannya.

Implementasi yang **tidak** memerlukan penulisan ulang aritmetika kurva:
menautkan **libsodium** (`crypto_sign_verify_detached`, sudah diaudit),
**OpenSSL/BoringSSL** `EVP_DigestVerify`, atau **ref10** dari SUPERCOP.
Untuk rilis produksi, menautkan pustaka yang sudah diaudit adalah pilihan yang
lebih dapat dipertahankan daripada aritmetika kurva buatan sendiri.

---

## 5. Vektor uji wajib (RFC 8032 §7.1)

Ambil teks RFC dari `https://www.rfc-editor.org/rfc/rfc8032.txt` (bagian 7.1).

### TEST 1 — pesan kosong

```
SECRET KEY : 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
PUBLIC KEY : d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
MESSAGE    : (0 byte)
SIGNATURE  : e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155
             5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b
```

### TEST 2 — pesan 1 byte

```
SECRET KEY : 4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb
PUBLIC KEY : 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c
MESSAGE    : 72
SIGNATURE  : 92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da
             085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00
```

### TEST 3 — pesan 2 byte

```
PUBLIC KEY : fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025
MESSAGE    : af82
SIGNATURE  : 6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac
             18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a
```

### Vektor negatif yang wajib ditolak

| Kasus | Harapan |
|---|---|
| Tanda tangan TEST 1 dengan 1 bit pesan diubah | `false` |
| Tanda tangan TEST 1 dengan byte S diubah | `false` |
| Tanda tangan TEST 1 dengan `S >= L` | `false` (bukan diterima) |
| `public_key` bukan titik kurva | `false` |
| `public_key` panjang 31 atau 33 byte | `false` |
| `signature` panjang 63 atau 65 byte | `false` |

Vektor negatif ini dapat dibuat tanpa pustaka kripto (cukup membalik byte),
sehingga **wajib** ada lebih dulu sebelum implementasi dianggap layak rilis.

---

## 6. Checklist sebelum rilis bertanda tangan

- [ ] Isi `Ed25519Verify()` (pustaka yang diaudit lebih disukai daripada buatan sendiri).
- [ ] `Ed25519SelfTest()` mengembalikan `true` hanya setelah ketiga vektor TEST 1–3 lulus.
- [ ] Seluruh vektor negatif di §5 ditolak.
- [ ] Ganti payload "teks apa adanya" dengan kanonikalisasi RFC 8785 (§3).
- [ ] Tambahkan jalur unit test yang menjalankan vektor di atas di CI.
- [ ] Sediakan jalur pembangkitan tanda tangan (di luar biner aplikasi) beserta
      prosedur penyimpanan kunci privat yang terpisah dari repo.
- [ ] Naikkan `schema`/`policy` bila semantik verifikasi berubah.
