#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "nq_ruleset.h"

// Verifier tanda tangan Ed25519 untuk berkas ruleset.
//
// STATUS (jujur, jangan dibaca sebagai "sudah jadi"):
//
//   TERIMPLEMENTASI  : penguraian hex, pemeriksaan well-formedness kunci publik
//                      (y < p) dan tanda tangan (S < L), self-test, integrasi
//                      ke jalur pemuatan ruleset.
//   BELUM ADA        : aritmetika kurva (decompress titik, scalar
//                      multiplication, adisi lengkap). Tanpa itu,
//                      Ed25519Verify() selalu mengembalikan false.
//
// Karena Ed25519Verify() selalu false, setiap ruleset dengan "signed": true
// DITOLAK. Itu perilaku yang BENAR untuk keadaan ini: gagal-tertutup. Aplikasi
// tetap berjalan, tetapi belum bisa memakai ruleset bertanda tangan.
//
// Kontrak lengkap, algoritma, vektor uji RFC 8032, dan checklist sebelum rilis
// bertanda tangan: docs/ED25519-VERIFIER-CONTRACT.md
namespace nq {

// ---------------------------------------------------------------------------
// Inti kripto
// ---------------------------------------------------------------------------

// Memverifikasi tanda tangan Ed25519 (RFC 8032 PureEdDSA).
//
// Mengembalikan true HANYA bila tanda sah. Saat ini SELALU false karena
// aritmetika kurva belum diimplementasikan — lihat nq_ed25519.cpp.
//
// public_key : 32 byte, titik terkompresi (y little-endian, bit 255 = tanda x)
// message    : byte pesan apa adanya
// signature  : 64 byte, R (32 byte) || S (32 byte, little-endian)
bool Ed25519Verify(const std::uint8_t public_key[32],
                   const std::uint8_t* message, std::size_t message_len,
                   const std::uint8_t signature[64]);

// Menjalankan vektor uji RFC 8032 §7.1 TEST 1 (pesan kosong) terhadap
// Ed25519Verify(). WAJIB dipanggil sekali saat startup.
//
// Nilai sekarang: false (kripto belum ada). Nilai ini akan otomatis menjadi
// true begitu aritmetika kurva diimplementasikan dengan benar, karena uji ini
// memakai vektor resmi, bukan vektor buatan sendiri. Inilah cara kontrak ini
// menahan diri dari "kelihatan sudah jadi".
bool Ed25519SelfTest();

// ---------------------------------------------------------------------------
// Pemeriksaan yang TIDAK membutuhkan aritmetika kurva (sudah benar)
// ---------------------------------------------------------------------------

// Memeriksa kunci publik berformat sah: y = LE(pk[0..31]) & (2^255-1) harus
// lebih kecil dari p. Titik yang tidak memenuhi ini tidak mungkin sah.
// CATATAN: ini BUKAN pemeriksaan "titik berada di kurva" (itu butuh aritmetika
// kurva dan belum ada).
bool Ed25519PublicKeyWellFormed(const std::uint8_t public_key[32]);

// Memeriksa tanda tangan berformat sah: S (32 byte little-endian) harus lebih
// kecil dari orde subgroup L.
//
// S >= L membuat tanda tangan dapat diubah tanpa kunci privat (RFC 8032 §8.4),
// sehingga WAJIB ditolak. Pemeriksaan ini murni perbandingan bilangan, jadi
// dapat — dan sudah — diimplementasikan tanpa aritmetika kurva.
bool Ed25519SignatureWellFormed(const std::uint8_t signature[64]);

// ---------------------------------------------------------------------------
// Penguraian hex
// ---------------------------------------------------------------------------

// Mengurai tepat out_len*2 karakter hex. Menolak panjang salah dan karakter
// non-hex. Tidak mengubah out saat gagal.
bool HexToBytes(const std::string& hex, std::uint8_t* out, std::size_t out_len);

// ---------------------------------------------------------------------------
// Verifier yang dipakai jalur pemuatan ruleset
// ---------------------------------------------------------------------------

class Ed25519RulesetVerifier final : public RulesetSignatureVerifier {
 public:
  // Selalu "ed25519"; dibandingkan dengan signature.algorithm di berkas ruleset.
  std::string algorithm() const override;

  // payload         : teks berkas ruleset apa adanya (lihat catatan
  //                   kanonikalisasi di docs/ED25519-VERIFIER-CONTRACT.md §3)
  // signature_value : 128 karakter hex (64 byte)
  // public_key      : 64 karakter hex (32 byte)
  //
  // Setiap kegagalan penguraian atau verifikasi mengembalikan false.
  bool Verify(const std::string& payload, const std::string& signature_value,
              const std::string& public_key) const override;
};

}  // namespace nq
